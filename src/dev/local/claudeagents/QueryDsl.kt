package dev.local.claudeagents

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Selection
import android.text.TextUtils
import android.text.TextWatcher
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Three-column suggestion row (label / greyed alias / greyed
 * description), the "Suggestion row anatomy" from query-dsl.md. Shared
 * by MainActivity's and ChatActivity's drawer search boxes (both use the
 * same QueryDsl.suggestions() shape) rather than copy-pasted twice.
 */
class SuggestionRowAdapter(private val context: Context, private val data: List<QueryDsl.Suggestion>) : BaseAdapter() {
    override fun getCount() = data.size
    override fun getItem(position: Int) = data[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val dp = { v: Int -> Theme.dp(context, v) }
        val row: LinearLayout
        val label: TextView
        val meta: TextView
        if (convertView == null) {
            row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.setBackgroundColor(Theme.surface)
            val padH = dp(14)
            val padV = dp(10)
            row.setPadding(padH, padV, padH, padV)
            label = TextView(context)
            label.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            label.textSize = 14f
            label.setTextColor(Theme.onBackground)
            row.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            meta = TextView(context)
            meta.textSize = 12f
            meta.setTextColor(Theme.muted)
            meta.maxLines = 1
            meta.ellipsize = TextUtils.TruncateAt.END
            val metaParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            metaParams.marginStart = dp(10)
            row.addView(meta, metaParams)
            row.tag = arrayOf(label, meta)
        } else {
            row = convertView as LinearLayout
            @Suppress("UNCHECKED_CAST")
            val tag = row.tag as Array<TextView>
            label = tag[0]
            meta = tag[1]
        }
        val s = data[position]
        label.text = s.label
        meta.text = listOfNotNull(s.alias, s.description.ifEmpty { null }).joinToString("  ")
        return row
    }
}

/**
 * The shared picker query DSL (~/.config/docs/query-dsl.md), applied to the
 * conversation list's search box. This table has no dynamic columns (fixed
 * ACCT/TITLE/TKNS/AGE), so /filter-type, /add-type, /remove-type are still
 * *parsed* by apply() (shared grammar -- their arity must still be consumed
 * correctly so they don't leak stray tokens into a following /fv) but are
 * inert here and left out of suggestions(), same precedent as the doc's
 * app-launcher entry ("shows no columns, offers /fv, /s only"). This
 * is the 9th copy-pasted implementation the doc's own header describes
 * (window-search, claude-history, focus-picker, winswitch, clipboard-
 * picker, notification-picker, app-launcher/rss-reader share one
 * QueryDsl.qml, claude-usage) -- added as its own row there.
 *
 * Only what this flat 4-field schema needs: no groups/subfields (every
 * field is flat, so `.` subset-path resolution never applies), single
 * free-text haystack (title).
 *
 * Autocompletion deviates from the doc on purpose: the desktop pickers
 * gate their popup on Tab (nothing shown just from typing) because a
 * physical Tab key exists there; a phone's soft keyboard has none, so this
 * follows the same *live* popup pattern already built for ChatActivity's
 * slash-command completion instead -- suggestions() is meant to be called
 * on every keystroke, always assuming the cursor sits at the end of the
 * string (a plain single-line EditText typed left to right, same
 * assumption the caller already makes for filtering). It also always
 * completes to the *plain* form actually being typed (colon or via,
 * whichever the user started) rather than steering every acceptance to
 * the via spelling the way rss-reader/claude-history do -- both spellings
 * parse identically either way (see the doc's Via paths section), and
 * steering would need cross-token replacement this single-span model
 * doesn't do.
 */
object QueryDsl {

    enum class Field(val fieldName: String) { TITLE("title"), STATUS("status"), TOKENS("tokens"), MSGS("msgs"), AGE("age"), PATH("path"), ACCOUNT("account") }

    /** `/fts` hits fetched from the relay: lower-case term -> session ids (absent = not fetched yet). */
    val ftsHits = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    data class Suggestion(val label: String, val alias: String?, val description: String, val insertText: String)

    private data class VerbInfo(val short: String, val long: String, val description: String)

    // Only the verbs that actually do something for this fixed-column
    // table -- /ft, /at, /rt are still parsed by apply() but left out of
    // the popup, matching the app-launcher precedent in the doc.
    private val ACTIVE_VERBS = listOf(
        VerbInfo("fv", "filter-value", "keep rows whose value matches (substring)"),
        VerbInfo("s", "sort", "order rows by the keys, /s/key,key/desc"),
        VerbInfo("fts", "full-text-search", "full text of every conversation, live and archived: /fts words up to the next /verb"),
        VerbInfo("bq", "base-query", "base query: rest of the line runs as usual; Enter saves it as the query run in front of every query (/bq alone + Enter shows the current one)")
    )

    // ---- base query (/base-query, see query-dsl.md) ----
    private const val DEFAULT_PREFS = "claudeagents_query_default"
    private val DEFAULT_CMD = Regex("^\\s*/(?:base-query|bq)(?:(\\s+)(.*))?$", RegexOption.DOT_MATCHES_ALL)

    /** The stored base query: written in front of every typed query, as if the user had typed it. */
    @Volatile var defaultQuery: String = ""
    /** Every non-empty base query ever set, most recent first. */
    @Volatile var baseHistory: List<String> = emptyList()
    private val BQ_REST = Regex("^(\\s*/(?:base-query|bq)\\s+)(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** Current base query first (if set), then the previous ones. */
    fun baseCandidates(): List<String> {
        val cur = defaultQuery.trim()
        return (if (cur.isEmpty()) emptyList() else listOf(cur)) + baseHistory.filter { it != cur }
    }

    fun loadDefault(context: Context) {
        val sp = context.getSharedPreferences(DEFAULT_PREFS, Context.MODE_PRIVATE)
        defaultQuery = sp.getString("query", "") ?: ""
        baseHistory = (sp.getString("history", "") ?: "").split("\n").filter { it.isNotEmpty() }
    }

    /**
     * What actually runs. As the first token, `/base-query` is transparent: the rest of the line is the query and the
     * stored [defaultQuery] is NOT applied (Enter replaces it). Otherwise [defaultQuery] + typed text.
     */
    fun effective(typed: String): String {
        DEFAULT_CMD.matchEntire(typed)?.let { return it.groupValues[2] }
        return if (defaultQuery.isBlank()) typed else if (typed.isBlank()) defaultQuery else defaultQuery.trim() + " " + typed
    }

    /**
     * Enter in a search box. If [typed] is the `/base-query` command (first token only): bare `/bq` shows the
     * current base query in a read-only dialog (selectable text, Copy button) and returns ""; `/bq <q>` stores q as
     * the base query (empty clears) and returns "". Either way the caller clears the box with the returned text.
     * Returns null if [typed] is not the command (nothing done).
     */
    fun runDefaultCommand(activity: android.app.Activity, typed: String): String? {
        val m = DEFAULT_CMD.matchEntire(typed) ?: return null
        if (m.groupValues[1].isEmpty()) {
            val tv = android.widget.TextView(activity)
            tv.text = if (defaultQuery.isEmpty()) "(none set)\n\nType /bq <query> and Enter to set one." else defaultQuery
            tv.setTextIsSelectable(true)
            tv.setPadding(48, 24, 48, 0)
            tv.textSize = 16f
            android.app.AlertDialog.Builder(activity)
                .setTitle("Base query")
                .setView(tv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy") { _, _ ->
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("base query", defaultQuery))
                }
                .show()
            return ""
        }
        val prev = defaultQuery.trim()
        defaultQuery = m.groupValues[2].trim()
        if (defaultQuery.isNotEmpty() && prev.isNotEmpty() && prev != defaultQuery && prev !in baseHistory) baseHistory = listOf(prev) + baseHistory
        if (defaultQuery.isNotEmpty()) baseHistory = (listOf(defaultQuery) + baseHistory.filter { it != defaultQuery }).take(100)
        activity.getSharedPreferences(DEFAULT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("query", defaultQuery).putString("history", baseHistory.joinToString("\n")).apply()
        return ""
    }

    private data class FieldInfo(val field: Field, val description: String)
    private val FIELD_INFO = listOf(
        FieldInfo(Field.TITLE, "conversation title"),
        FieldInfo(Field.STATUS, "busy / idle / waiting / archive"),
        FieldInfo(Field.TOKENS, "context-window token count"),
        FieldInfo(Field.MSGS, "message count"),
        FieldInfo(Field.AGE, "time since last activity"),
        FieldInfo(Field.PATH, "working directory"),
        FieldInfo(Field.ACCOUNT, "which of the 3 accounts (1/2/3)")
    )

    private val VERB_ALIASES = mapOf(
        "fv" to "fv", "filter-value" to "fv",
        "ft" to "ft", "filter-type" to "ft",
        "at" to "at", "add-type" to "at",
        "rt" to "rt", "remove-type" to "rt",
        "s" to "s", "sort" to "s",
        "fts" to "fts", "full-text-search" to "fts"
    )

    private data class Token(val raw: String, val isQuoted: Boolean, val spanStart: Int, val unterminated: Boolean = false, val neg: Boolean = false)

    // "..." is checked first at every position -- a token starting with "
    // is never a command, even if what's inside looks like one. An
    // unterminated trailing quote swallows the rest of the string as one
    // pending token rather than erroring. spanStart is the offset in the
    // original string where this token (including its opening quote, if
    // any) begins -- suggestions() uses it to know how much trailing text
    // a completion replaces.
    private fun tokenize(query: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        val n = query.length
        while (i < n) {
            while (i < n && query[i].isWhitespace()) i++
            if (i >= n) break
            // A leading "!" negates the term (query-dsl.md "Negation"). It is
            // not part of the token: spanStart sits after it, so a completion
            // replaces only what follows and keeps the "!". A lone "!" (nothing
            // glued after it yet) is still forming: inert, no token at all.
            var neg = false
            if (query[i] == '!') {
                if (i + 1 >= n || query[i + 1].isWhitespace()) { i++; continue }
                neg = true
                i++
            }
            val spanStart = i
            if (query[i] == '"') {
                val end = query.indexOf('"', i + 1)
                if (end < 0) {
                    out.add(Token(query.substring(i + 1), true, spanStart, unterminated = true, neg = neg))
                    i = n
                } else {
                    out.add(Token(query.substring(i + 1, end), true, spanStart, neg = neg))
                    i = end + 1
                }
            } else {
                val start = i
                while (i < n && !query[i].isWhitespace()) i++
                out.add(Token(query.substring(start, i), false, spanStart, neg = neg))
            }
        }
        return out
    }

    // Verb names are exact-matched (never substring/fuzzy) against the 12
    // fixed short/long spellings; an unrecognized /xyz is inert (never a
    // literal filter term). Returns (verb, viaPath) -- viaPath is whatever
    // followed a second "/" glued onto the verb, e.g. "/fv/account" -> (fv,
    // "account"); "/sort/tokens/title" -> (s, "tokens/title").
    private data class VerbTok(val verb: String, val via: String?, val dir: String?, val bad: Boolean)

    // One syntax (query-dsl.md "Grammar"): `/verb/keys[/dir]`, `//keys` for /fv.
    // via = the keys part (comma list), dir = the third part (/s only), bad =
    // too many parts (inert). An unrecognized /xyz is not a verb token.
    private fun parseVerbToken(tok: Token): VerbTok? {
        if (tok.isQuoted || !tok.raw.startsWith("/")) return null
        val parts = tok.raw.substring(1).split("/")
        val verb = (if (parts[0].isEmpty()) (if (parts.size >= 2) "fv" else null) else VERB_ALIASES[parts[0]]) ?: return null
        return VerbTok(verb, parts.getOrNull(1), parts.getOrNull(2), parts.size > (if (verb == "s") 3 else 2))
    }

    // Keys: a comma list; per key the field named exactly that wins alone, else every
    // field containing it; unioned.
    private fun resolveFields(keys: String): List<Field> {
        val hit = linkedSetOf<Field>()
        for (key in keys.lowercase().split(",")) {
            if (key.isEmpty()) continue
            val exact = Field.values().filter { it.fieldName == key }
            hit.addAll(if (exact.isNotEmpty()) exact else Field.values().filter { it.fieldName.contains(key) })
        }
        return Field.values().filter { it in hit }
    }

    private fun isVerbArgToken(tok: Token?): Boolean = tok != null && parseVerbToken(tok) != null

    private fun displayValue(row: ConversationRow, field: Field): String = when (field) {
        Field.TITLE -> row.title
        Field.ACCOUNT -> ConversationColumns.accountNumber(row.account)
        Field.TOKENS -> Fmt.tokens(row.tokens)
        Field.AGE -> Fmt.ago(row.mtime)
        Field.STATUS -> if (row.isLive) (row.liveStatus ?: "live") else "archive"
        Field.MSGS -> row.msgs?.toString() ?: ""
        Field.PATH -> (row.cwd ?: "").replace(Regex("^/home/[^/]+(?=/|$)"), "~")
    }

    // Plain integers/timestamps compare numerically, not lexicographically
    // ("10" must sort after "2") -- title/account stay plain string
    // compares. mtime is a real timestamp (not a pre-formatted age
    // bucket), so ascending genuinely means oldest-first with no
    // direction inversion needed the way an already-"seconds ago" stored
    // value would require.
    private fun fieldComparator(field: Field): Comparator<ConversationRow> = when (field) {
        Field.TITLE -> compareBy { it.title.lowercase() }
        Field.ACCOUNT -> compareBy { ConversationColumns.accountNumber(it.account) }
        Field.TOKENS -> compareBy { it.tokens ?: -1 }
        Field.AGE -> compareBy { it.mtime }
        Field.STATUS -> compareBy { if (it.isLive) (it.liveStatus ?: "live") else "archive" }
        Field.MSGS -> compareBy { it.msgs ?: -1 }
        Field.PATH -> compareBy { (it.cwd ?: "").lowercase() }
    }

    /** Substring containment; with [exact] (a terminated "quoted" term) the value must stand alone - no
     *  letter/digit/underscore directly before or after it, so "5" matches 5 but not 15 or 599. */
    private fun containsTerm(hay: String, value: String, exact: Boolean): Boolean {
        // An unquoted `a||b` matches when any alternative does (empty parts dropped; none left = empty needle)
        if (!exact && value.contains("||")) {
            val alts = value.split("||").filter { it.isNotEmpty() }
            return alts.isEmpty() || alts.any { containsTerm(hay, it, false) }
        }
        var i = hay.indexOf(value, ignoreCase = true)
        if (!exact || value.isEmpty()) return i >= 0
        fun word(c: Char) = c.isLetterOrDigit() || c == '_'
        val lead = word(value.first())
        val trail = word(value.last())
        while (i >= 0) {
            val beforeOk = !lead || i == 0 || !word(hay[i - 1])
            val afterOk = !trail || i + value.length >= hay.length || !word(hay[i + value.length])
            if (beforeOk && afterOk) return true
            i = hay.indexOf(value, i + 1, ignoreCase = true)
        }
        return false
    }

    private class FilterTerm(val fields: List<Field>?, val alwaysFalse: Boolean, val value: String, val neg: Boolean = false, val exact: Boolean = false,
                             val fts: Boolean = false) {
        // A negated term keeps exactly the rows its positive form drops
        // (so an unresolvable path, which matches nothing, negates to "keep").
        fun matches(row: ConversationRow): Boolean = positive(row) != neg
        private fun positive(row: ConversationRow): Boolean {
            if (alwaysFalse) return false
            if (fts) return ftsHits[value.lowercase()]?.contains(row.id) == true
            val flds = fields ?: return containsTerm(row.title, value, exact)
            return flds.any { containsTerm(displayValue(row, it), value, exact) }
        }
    }

    /** Terms of every `/fts` in [query] that still need fetching from the relay (not in [ftsHits] yet). */
    fun pendingFtsTerms(typed: String): List<String> {
        val tokens = tokenize(effective(typed))
        val out = mutableListOf<String>()
        var idx = 0
        while (idx < tokens.size) {
            val p = parseVerbToken(tokens[idx])
            if (p != null && p.verb == "fts") {
                val words = mutableListOf<String>()
                var j = idx + 1
                while (j < tokens.size && !isVerbArgToken(tokens[j])) { words.add((if (tokens[j].neg) "!" else "") + tokens[j].raw); j++ }
                val t = words.joinToString(" ").lowercase()
                if (t.isNotEmpty() && !ftsHits.containsKey(t) && t !in out) out.add(t)
                idx = j
            } else idx++
        }
        return out
    }

    private val ftsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Fetches any `/fts` terms in [query] the relay hasn't answered yet, then calls [onDone] on the UI thread so the caller re-applies. */
    fun fetchFts(activity: android.app.Activity, query: String, onDone: () -> Unit) {
        for (term in pendingFtsTerms(query)) {
            if (!ftsInFlight.add(term)) continue
            Thread {
                val ids = try { RelayClient(activity).fts(term).keys } catch (e: Exception) { null }
                if (ids != null) ftsHits[term] = ids   // a failed fetch is left unset so the next keystroke retries
                ftsInFlight.remove(term)
                activity.runOnUiThread { onDone() }
            }.apply { isDaemon = true; name = "QueryDslFts"; start() }
        }
    }

    fun apply(all: List<ConversationRow>, typed: String): List<ConversationRow> {
        val query = effective(typed)
        if (query.isBlank()) return all
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return all

        val filterTerms = mutableListOf<FilterTerm>()
        var sortChain: List<Field>? = null
        var sortInert = false
        var hasSort = false
        var sortDescending = false
        var ftsLive = false
        var ftsArchive = false

        var idx = 0
        while (idx < tokens.size) {
            val tok = tokens[idx]
            val parsed = parseVerbToken(tok)
            if (parsed == null) {
                // A bare word or a *terminated* quoted phrase is a complete,
                // valid filter term at every length -- narrowing further
                // with each keystroke is exactly what a live search box
                // wants, so this is never suppressed. Only a still-open
                // quote ("log with no closing ") is genuinely incomplete
                // (its own contents aren't even settled yet) and stays
                // inert until it's closed.
                if (!(tok.isQuoted && tok.unterminated)) filterTerms.add(FilterTerm(null, false, tok.raw, tok.neg, tok.isQuoted))
                idx += 1
                continue
            }
            val (verb, via, dirPart, bad) = parsed
            if (verb == "fts") {
                // `/fts[/live|/archive] words`: every word up to the next /verb is one full-text query
                // (same rule as the web page and the desktop panel, query-dsl.md "App verbs")
                val words = mutableListOf<String>()
                var j = idx + 1
                while (j < tokens.size && !isVerbArgToken(tokens[j])) { words.add((if (tokens[j].neg) "!" else "") + tokens[j].raw); j++ }
                val known = listOf("claude-live", "claude-archive", "tmux")
                val keys = (via ?: "").lowercase().split(",").filter { it.isNotEmpty() }
                val hit = known.filter { n -> keys.any { k -> if (known.any { it == k }) n == k else n.contains(k) } }
                if (keys.isNotEmpty()) { ftsLive = ftsLive || "claude-live" in hit; ftsArchive = ftsArchive || "claude-archive" in hit } else { ftsLive = true; ftsArchive = true }
                if (words.isNotEmpty()) filterTerms.add(FilterTerm(null, false, words.joinToString(" "), tok.neg, false, true))
                idx = j
                continue
            }
            // keyless or malformed: inert, takes no argument ("/fv foo" is just "foo")
            if (via == null || bad) {
                idx += 1
                continue
            }
            when (verb) {
                "fv" -> {
                    val valueTok = tokens.getOrNull(idx + 1)
                    if (valueTok != null && !isVerbArgToken(valueTok)) {
                        val fields = resolveFields(via)
                        val neg = tok.neg || valueTok.neg
                        if (fields.isEmpty()) {
                            filterTerms.add(FilterTerm(null, true, "", neg))
                        } else {
                            filterTerms.add(FilterTerm(fields, false, valueTok.raw, neg, valueTok.isQuoted))
                        }
                        idx += 2
                    } else {
                        idx += 1
                    }
                }
                "s" -> {
                    // "!/s" is inert (negation only means something on a row filter).
                    if (!tok.neg) {
                        val keys = via.split(",").filter { it.isNotEmpty() }
                        val d = dirPart?.lowercase()
                        val dirOk = d == null || (d.isNotEmpty() && ("ascending".startsWith(d) || "descending".startsWith(d)))
                        if (dirOk) {
                            hasSort = true
                            // fuzzy keys expand to every matching field, alphabetical (query-dsl.md "Keys")
                            val resolved = keys.map { k -> resolveFields(k).sortedBy { it.fieldName } }
                            if (keys.isEmpty() || resolved.any { it.isEmpty() }) {
                                sortInert = true
                            } else {
                                sortInert = false
                                sortChain = resolved.flatten().distinct()
                            }
                            sortDescending = d == null || "descending".startsWith(d) && !"ascending".startsWith(d)
                        }
                    }
                    idx += 1
                }
                else -> idx += 1 // "ft" / "at" / "rt" -- parsed, inert (fixed columns)
            }
        }

        var rows = if (filterTerms.isEmpty()) all else all.filter { row -> filterTerms.all { it.matches(row) } }
        if (ftsLive || ftsArchive) rows = rows.filter { if (it.isLive) ftsLive else ftsArchive }

        if (hasSort && !sortInert && sortChain != null) {
            val chain = sortChain
            val combined = Comparator<ConversationRow> { a, b ->
                for (f in chain) {
                    val c = fieldComparator(f).compare(a, b)
                    if (c != 0) return@Comparator c
                }
                0
            }
            rows = if (sortDescending) rows.sortedWith(combined.reversed()) else rows.sortedWith(combined)
        }
        return rows
    }

    // --- Autocompletion (see the class doc for how this deviates from the
    // desktop pickers' Tab-gated model) ---

    // If `tok` is a /fv token ("/fv/account", "//account,title") with resolvable
    // keys, returns the resolved fields -- used to detect "the value slot right
    // after this token is now open".
    private fun viaFvFieldsOf(tok: Token?): List<Field>? {
        if (tok == null || tok.isQuoted || !tok.raw.startsWith("/")) return null
        val vt = parseVerbToken(tok) ?: return null
        if (vt.verb != "fv" || vt.via == null || vt.bad) return null
        return resolveFields(vt.via).ifEmpty { null }
    }

    private fun valueSuggestions(
        all: List<ConversationRow>,
        fields: List<Field>,
        frag: String,
        insert: (String) -> String
    ): List<Suggestion> {
        val values = sortedSetOf<String>()
        for (row in all) for (f in fields) {
            val v = displayValue(row, f)
            if (v.isNotEmpty()) values.add(v)
        }
        return values.filter { it.contains(frag, ignoreCase = true) }.take(30)
            .map { Suggestion(it, null, "", insert(it)) }
    }

    // Suggestions for whatever is being typed right at the end of `query`
    // (always assumes the cursor is there -- see class doc). Every
    // Suggestion.insertText replaces the query from `current`'s span start
    // (or the end of the string, if nothing's being typed yet -- e.g. right
    // after "/fv ") to the end -- a single contiguous replacement, never a
    // cross-token rewrite.
    fun suggestions(all: List<ConversationRow>, query: String): List<Suggestion> {
        // "/bq <frag>": the base-query history (current first), prefix-filtered by <frag>; falls through when nothing matches
        BQ_REST.matchEntire(query)?.let { m ->
            val frag = m.groupValues[2].trim()
            val cur = defaultQuery.trim()
            val hits = baseCandidates().filter { it.startsWith(frag) }
                .map { Suggestion(it, null, if (it == cur) "current" else "previous", it) }
            if (hits.isNotEmpty()) return hits
        }
        val trailingSpace = query.isNotEmpty() && query.last().isWhitespace()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val current: Token? = if (trailingSpace) null else tokens.last()
        val lastComplete: Token? = if (trailingSpace) tokens.lastOrNull() else tokens.getOrNull(tokens.size - 2)

        // Stage 1: a fresh command -- current token starts with "/", no second "/" yet.
        if (current != null && !current.isQuoted && current.raw.startsWith("/") && !current.raw.substring(1).contains("/")) {
            val frag = current.raw.substring(1).lowercase()
            // /bq is a command, not a pipeline stage: only the very first token of the query
            return ACTIVE_VERBS.filter { it.short.contains(frag) && (tokens.size == 1 || it.short != "bq") }
                .map { Suggestion("/${it.short}", "/${it.long}", it.description, if (it.short == "bq") "/${it.short} " else "/${it.short}/") }
        }

        // Stage 2: keys -- being typed after "/fv/", "//" or "/s/" (the fragment after the last comma),
        // or the direction part of "/s/keys/<dir>".
        run {
            val t = current ?: return@run
            if (t.isQuoted || !t.raw.startsWith("/")) return@run
            val parts = t.raw.substring(1).split("/")
            if (parts.size < 2) return@run
            val verbPart = parts[0]
            if (verbPart !in setOf("", "fv", "filter-value", "s", "sort")) return@run
            val isSort = verbPart == "s" || verbPart == "sort"
            if (parts.size == 3) {
                if (!isSort) return@run
                val d = parts[2].lowercase()
                val head = t.raw.substring(0, t.raw.length - parts[2].length)
                return listOf("asc" to "ascending", "desc" to "descending").filter { it.second.startsWith(d) }
                    .map { Suggestion(it.first, null, "", head + it.first + " ") }
            }
            if (parts.size > 3) return@run
            val frag = parts[1].substringAfterLast(',').lowercase()
            val headLen = t.raw.length - frag.length
            return FIELD_INFO.filter { it.field.fieldName.contains(frag) }
                .map { fi -> Suggestion(fi.field.fieldName, null, fi.description,
                    t.raw.substring(0, headLen) + fi.field.fieldName + (if (isSort) "" else " ")) }
        }

        // Stage 3: filter value -- "/fv/path frag" (value as its own following token).
        viaFvFieldsOf(lastComplete)?.let { fields ->
            if (current == null || !current.isQuoted) {
                val frag = (current?.raw ?: "").lowercase()
                return valueSuggestions(all, fields, frag) { v -> "$v " }
            }
        }

        return emptyList()
    }

    // Where a completion's insertText should splice in -- see suggestions().
    fun replaceFrom(query: String): Int {
        BQ_REST.matchEntire(query)?.let { m ->
            if (baseCandidates().any { it.startsWith(m.groupValues[2].trim()) }) return m.groupValues[1].length
        }
        val trailingSpace = query.isNotEmpty() && query.last().isWhitespace()
        if (trailingSpace) return query.length
        val tokens = tokenize(query)
        return tokens.lastOrNull()?.spanStart ?: query.length
    }
}
