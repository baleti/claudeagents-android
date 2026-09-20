package dev.local.claudeagents

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
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
 * app-launcher entry ("shows no columns, offers /fv, /s, /rv only"). This
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

    enum class Field(val fieldName: String) { TITLE("title"), ACCOUNT("account"), TOKENS("tokens"), AGE("age") }

    data class Suggestion(val label: String, val alias: String?, val description: String, val insertText: String)

    private data class VerbInfo(val short: String, val long: String, val description: String)

    // Only the verbs that actually do something for this fixed-column
    // table -- /ft, /at, /rt are still parsed by apply() but left out of
    // the popup, matching the app-launcher precedent in the doc.
    private val ACTIVE_VERBS = listOf(
        VerbInfo("fv", "filter-value", "keep rows whose value matches (substring)"),
        VerbInfo("s", "sort", "order rows by one field, optional asc / desc"),
        VerbInfo("rv", "reverse", "flip the current order")
    )

    private data class FieldInfo(val field: Field, val description: String)
    private val FIELD_INFO = listOf(
        FieldInfo(Field.TITLE, "conversation title"),
        FieldInfo(Field.ACCOUNT, "which of the 3 accounts (1/2/3)"),
        FieldInfo(Field.TOKENS, "context-window token count"),
        FieldInfo(Field.AGE, "time since last activity")
    )

    private val VERB_ALIASES = mapOf(
        "fv" to "fv", "filter-value" to "fv",
        "ft" to "ft", "filter-type" to "ft",
        "at" to "at", "add-type" to "at",
        "rt" to "rt", "remove-type" to "rt",
        "s" to "s", "sort" to "s",
        "rv" to "rv", "reverse" to "rv"
    )

    private data class Token(val raw: String, val isQuoted: Boolean, val spanStart: Int, val unterminated: Boolean = false)

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
            val spanStart = i
            if (query[i] == '"') {
                val end = query.indexOf('"', i + 1)
                if (end < 0) {
                    out.add(Token(query.substring(i + 1), true, spanStart, unterminated = true))
                    i = n
                } else {
                    out.add(Token(query.substring(i + 1, end), true, spanStart))
                    i = end + 1
                }
            } else {
                val start = i
                while (i < n && !query[i].isWhitespace()) i++
                out.add(Token(query.substring(start, i), false, spanStart))
            }
        }
        return out
    }

    // Verb names are exact-matched (never substring/fuzzy) against the 12
    // fixed short/long spellings; an unrecognized /xyz is inert (never a
    // literal filter term). Returns (verb, viaPath) -- viaPath is whatever
    // followed a second "/" glued onto the verb, e.g. "/fv/account" -> (fv,
    // "account"); "/sort/tokens/title" -> (s, "tokens/title").
    private fun parseVerbToken(tok: Token): Pair<String, String?>? {
        if (tok.isQuoted || !tok.raw.startsWith("/")) return null
        val rest = tok.raw.substring(1)
        val secondSlash = rest.indexOf('/')
        val verbPart = if (secondSlash >= 0) rest.substring(0, secondSlash) else rest
        val via = if (secondSlash >= 0) rest.substring(secondSlash + 1) else null
        // "//path": the empty verb slot before the via "/" defaults to /fv
        // (query-dsl.md "Default verb")
        val verb = (if (secondSlash >= 0 && verbPart.isEmpty()) "fv" else VERB_ALIASES[verbPart]) ?: return null
        return verb to via
    }

    // Each path segment is substring-matched case-insensitively against
    // known field names; every match is unioned (an ambiguous segment acts
    // on every field it could mean).
    private fun resolveFields(pathSegment: String): List<Field> {
        if (pathSegment.isEmpty()) return emptyList()
        val needle = pathSegment.lowercase()
        return Field.values().filter { it.fieldName.contains(needle) }
    }

    private fun isVerbArgToken(tok: Token?): Boolean = tok != null && parseVerbToken(tok) != null

    private fun displayValue(row: ConversationRow, field: Field): String = when (field) {
        Field.TITLE -> row.title
        Field.ACCOUNT -> ConversationColumns.accountNumber(row.account)
        Field.TOKENS -> Fmt.tokens(row.tokens)
        Field.AGE -> Fmt.ago(row.mtime)
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
    }

    private class FilterTerm(val fields: List<Field>?, val alwaysFalse: Boolean, val value: String) {
        fun matches(row: ConversationRow): Boolean {
            if (alwaysFalse) return false
            val flds = fields ?: return row.title.contains(value, ignoreCase = true)
            return flds.any { displayValue(row, it).contains(value, ignoreCase = true) }
        }
    }

    fun apply(all: List<ConversationRow>, query: String): List<ConversationRow> {
        if (query.isBlank()) return all
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return all

        val filterTerms = mutableListOf<FilterTerm>()
        var sortChain: List<Field>? = null
        var sortInert = false
        var hasSort = false
        var sortDescending = false
        var reverse = false

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
                if (!(tok.isQuoted && tok.unterminated)) filterTerms.add(FilterTerm(null, false, tok.raw))
                idx += 1
                continue
            }
            val (verb, via) = parsed
            when (verb) {
                "fv" -> {
                    if (via != null) {
                        val valueTok = tokens.getOrNull(idx + 1)
                        if (valueTok != null && !isVerbArgToken(valueTok)) {
                            val fields = resolveFields(via)
                            if (fields.isEmpty()) {
                                filterTerms.add(FilterTerm(null, true, ""))
                            } else {
                                filterTerms.add(FilterTerm(fields, false, valueTok.raw))
                            }
                            idx += 2
                        } else {
                            idx += 1
                        }
                    } else {
                        val argTok = tokens.getOrNull(idx + 1)
                        if (argTok != null && !isVerbArgToken(argTok)) {
                            val colon = if (!argTok.isQuoted) argTok.raw.indexOf(':') else -1
                            if (colon > 0) {
                                val fields = resolveFields(argTok.raw.substring(0, colon))
                                val value = argTok.raw.substring(colon + 1)
                                if (fields.isEmpty()) {
                                    filterTerms.add(FilterTerm(null, true, ""))
                                } else {
                                    filterTerms.add(FilterTerm(fields, false, value))
                                }
                            } else {
                                filterTerms.add(FilterTerm(null, false, argTok.raw))
                            }
                            idx += 2
                        } else {
                            idx += 1
                        }
                    }
                }
                "s" -> {
                    hasSort = true
                    var consumed = 1
                    val chainSegs: List<String>? = if (via != null) {
                        via.split("/")
                    } else {
                        val pathTok = tokens.getOrNull(idx + 1)
                        if (pathTok != null && !isVerbArgToken(pathTok)) {
                            consumed = 2
                            listOf(pathTok.raw)
                        } else null
                    }
                    if (chainSegs == null || chainSegs.any { it.isEmpty() }) {
                        sortInert = true
                    } else {
                        val resolved = chainSegs.map { resolveFields(it) }
                        if (resolved.any { it.size != 1 }) {
                            sortInert = true
                        } else {
                            sortChain = resolved.map { it[0] }
                        }
                    }
                    val dirTok = tokens.getOrNull(idx + consumed)
                    if (dirTok != null && !isVerbArgToken(dirTok)) {
                        val d = dirTok.raw.lowercase()
                        if (d.isNotEmpty() && ("ascending".contains(d) || "descending".contains(d))) {
                            sortDescending = "descending".contains(d)
                            consumed += 1
                        }
                    }
                    idx += consumed
                }
                "rv" -> {
                    reverse = true
                    idx += 1
                }
                else -> { // "ft" / "at" / "rt" -- parsed for correct arity, inert (fixed columns)
                    if (via != null) {
                        idx += 1
                    } else {
                        val argTok = tokens.getOrNull(idx + 1)
                        idx += if (argTok != null && !isVerbArgToken(argTok)) 2 else 1
                    }
                }
            }
        }

        var rows = if (filterTerms.isEmpty()) all else all.filter { row -> filterTerms.all { it.matches(row) } }

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
        if (reverse) rows = rows.reversed()
        return rows
    }

    // --- Autocompletion (see the class doc for how this deviates from the
    // desktop pickers' Tab-gated model) ---

    private fun bareVerbToken(t: Token?, names: Set<String>): Boolean =
        t != null && !t.isQuoted && t.raw.startsWith("/") && names.contains(t.raw.substring(1))

    // If `tok` is a via-form /fv token ("/fv/account") with a resolvable
    // path, returns the resolved fields -- used to detect "the value slot
    // right after this token is now open".
    private fun viaFvFieldsOf(tok: Token?): List<Field>? {
        if (tok == null || tok.isQuoted || !tok.raw.startsWith("/")) return null
        val rest = tok.raw.substring(1)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val verbPart = rest.substring(0, slash)
        if (verbPart != "fv" && verbPart != "filter-value" && verbPart.isNotEmpty()) return null
        val fields = resolveFields(rest.substring(slash + 1))
        return fields.ifEmpty { null }
    }

    private fun isCompleteSortPath(lastComplete: Token?, prevOfLastComplete: Token?): Boolean {
        if (lastComplete == null || lastComplete.isQuoted) return false
        if (!lastComplete.raw.startsWith("/")) {
            return prevOfLastComplete != null && !prevOfLastComplete.isQuoted &&
                (prevOfLastComplete.raw == "/s" || prevOfLastComplete.raw == "/sort") &&
                resolveFields(lastComplete.raw).size == 1
        }
        val rest = lastComplete.raw.substring(1)
        val slash = rest.indexOf('/')
        if (slash < 0) return false
        val verbPart = rest.substring(0, slash)
        if (verbPart != "s" && verbPart != "sort") return false
        val chain = rest.substring(slash + 1).split("/")
        return chain.isNotEmpty() && chain.all { resolveFields(it).size == 1 }
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
        val trailingSpace = query.isNotEmpty() && query.last().isWhitespace()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val current: Token? = if (trailingSpace) null else tokens.last()
        val lastComplete: Token? = if (trailingSpace) tokens.lastOrNull() else tokens.getOrNull(tokens.size - 2)
        val prevOfLastComplete: Token? = if (trailingSpace) tokens.getOrNull(tokens.size - 2) else tokens.getOrNull(tokens.size - 3)

        // Stage 1: a fresh command -- current token starts with "/", no via yet.
        if (current != null && !current.isQuoted && current.raw.startsWith("/") && !current.raw.substring(1).contains("/")) {
            val frag = current.raw.substring(1).lowercase()
            return ACTIVE_VERBS.filter { it.short.contains(frag) }
                .map { Suggestion("/${it.short}", "/${it.long}", it.description, "/${it.short} ") }
        }

        // Stage 2: type path -- via form being typed ("/fv/acc", "/s/tok").
        run {
            val t = current ?: return@run
            if (t.isQuoted || !t.raw.startsWith("/")) return@run
            val slash = t.raw.indexOf('/', 1)
            if (slash < 0) return@run
            val verbPart = t.raw.substring(1, slash)
            if (verbPart !in setOf("", "fv", "filter-value", "s", "sort")) return@run
            val afterVerb = t.raw.substring(slash + 1)
            val lastSeg = afterVerb.substringAfterLast('/')
            val headLen = t.raw.length - lastSeg.length
            val frag = lastSeg.lowercase()
            return FIELD_INFO.filter { it.field.fieldName.contains(frag) }
                .map { fi -> Suggestion(fi.field.fieldName, null, fi.description, t.raw.substring(0, headLen) + fi.field.fieldName + " ") }
        }
        // Stage 2: type path -- space form right after a bare verb token.
        if (bareVerbToken(lastComplete, setOf("fv", "filter-value", "s", "sort")) &&
            (current == null || (!current.isQuoted && !current.raw.contains(':')))
        ) {
            val frag = (current?.raw ?: "").lowercase()
            return FIELD_INFO.filter { it.field.fieldName.contains(frag) }
                .map { Suggestion(it.field.fieldName, null, it.description, "${it.field.fieldName}:") }
        }

        // Stage 3: filter value -- "/fv path:frag" (space form, glued) or
        // "/fv/path frag" (via form, value as its own following token).
        if (current != null && !current.isQuoted && current.raw.contains(':') &&
            bareVerbToken(lastComplete, setOf("fv", "filter-value"))
        ) {
            val path = current.raw.substringBefore(':')
            val frag = current.raw.substringAfter(':')
            val fields = resolveFields(path)
            if (fields.isNotEmpty()) return valueSuggestions(all, fields, frag) { v -> "$path:$v " }
        }
        viaFvFieldsOf(lastComplete)?.let { fields ->
            if (current == null || !current.isQuoted) {
                val frag = (current?.raw ?: "").lowercase()
                return valueSuggestions(all, fields, frag) { v -> "$v " }
            }
        }

        // Stage 4: sort direction -- right after a complete "/s <path>" or
        // "/s/path..." command.
        if (isCompleteSortPath(lastComplete, prevOfLastComplete) && (current == null || !current.isQuoted)) {
            val frag = (current?.raw ?: "").lowercase()
            return listOf("ascending", "descending").filter { it.contains(frag) }
                .map { Suggestion(it, null, "", "$it ") }
        }

        return emptyList()
    }

    // Where a completion's insertText should splice in -- see suggestions().
    fun replaceFrom(query: String): Int {
        val trailingSpace = query.isNotEmpty() && query.last().isWhitespace()
        if (trailingSpace) return query.length
        val tokens = tokenize(query)
        return tokens.lastOrNull()?.spanStart ?: query.length
    }
}
