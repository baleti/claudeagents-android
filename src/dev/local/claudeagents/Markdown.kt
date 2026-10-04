package dev.local.claudeagents

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

sealed class MdSegment {
    data class Text(val spanned: SpannableStringBuilder) : MdSegment()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdSegment()
}

/**
 * Minimal hand-rolled markdown -> Spannable renderer. No markdown library
 * is reachable from this build (no dependency resolver -- see Theme.kt's
 * own note on why this whole app is pure-platform), so this covers just
 * what Claude Code's own replies actually use: **bold**, `inline code`,
 * fenced ```code blocks```, "- "/"* " bullets, "# " headers, GFM pipe
 * tables, and the daemon's own "→ tool(args)" tool-call summary lines
 * (see claude-agents-daemon.py's summarize_content_blocks) styled
 * distinctly so a tool call reads as different from prose at a glance,
 * the way Claude Code's own CLI output does. Not a CommonMark
 * implementation -- nested/exotic markdown will render literally rather
 * than crash.
 *
 * Tables come out as a separate MdSegment.Table rather than inline
 * Spannable text -- a plain TextView has no notion of a grid, so
 * MessageAdapter renders each Table segment as a real TableLayout child
 * view instead (reported: pipe tables were showing as raw "| a | b |"
 * plain text before this).
 */
object Markdown {
    // Table cells go through MessageAdapter's own TableLayout, not the
    // line-based renderSegments loop -- exposed so a cell's **bold**/`code`
    // still gets processed instead of showing the raw markdown characters
    // (confirmed live 2026-09-05: cells rendered literal asterisks/backticks
    // before this).
    fun renderInline(text: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        appendInline(out, text)
        return out
    }

    // The plain text this app actually sends for TTS/read-aloud, for a
    // message whose markdown renders as exactly one plain-text segment (no
    // table) -- shared by ChatActivity (what gets SENT to the TTS server,
    // see readAloudFrom) and MessageAdapter (what's safe to word-highlight
    // on screen, see highlightableText), so the two can never disagree
    // about what "the text being read" actually is. Null for anything more
    // complex (a table, multiple segments) -- callers fall back to the raw
    // markdown source for those, accepting no highlight for that message
    // (a table/multi-segment layout has no single linear text a highlight
    // span could safely land in anyway).
    fun singleSegmentPlainText(text: String, dimColor: Int): String? {
        val seg = renderSegments(text, dimColor).singleOrNull() as? MdSegment.Text ?: return null
        return seg.spanned.toString()
    }

    fun renderSegments(text: String, dimColor: Int): List<MdSegment> {
        val segments = mutableListOf<MdSegment>()
        val lines = text.split("\n")
        var textBuf = SpannableStringBuilder()
        var inFence = false
        var i = 0

        // Consecutive plain-prose lines (the `else` branch below) are
        // joined back together with their original "\n"s and run through
        // appendInline as ONE string, not one call per line -- **bold**
        // (or `code`) spanning a line break was never found and rendered
        // literally otherwise, since each line was scanned for a matching
        // closing marker independently, with no visibility into the next
        // line at all (reported live 2026-09-09: "bold markdown
        // formatting... isn't being rendered" -- rare in practice since
        // most bold runs stay on one line, but real whenever a reply's
        // own markdown source happens to wrap one across a "\n").
        // Headers/bullets/tool-call lines/fences are still handled per
        // line exactly as before -- only contiguous prose runs are grouped.
        val proseBuf = StringBuilder()

        // A prose run's own lines were already joined with "\n" inside
        // proseBuf, matching what per-line appends used to produce -- but
        // the ORIGINAL per-line loop also appended a "\n" after the run's
        // very last line (since that line's own `i != lines.lastIndex`
        // check fired too, right before whatever non-prose content came
        // next). Flushing the joined buffer via one appendInline() call
        // loses that final separator, so flushProse() re-adds it itself --
        // except at the true end of the whole text (flushText()'s own
        // call), where the original loop's "never a trailing newline on
        // the very last line" rule still applies.
        fun flushProse(trailingNewline: Boolean = true) {
            if (proseBuf.isNotEmpty()) {
                appendInline(textBuf, proseBuf.toString())
                if (trailingNewline) textBuf.append("\n")
                proseBuf.clear()
            }
        }

        fun flushText() {
            flushProse(trailingNewline = false)
            if (textBuf.isNotEmpty()) {
                segments.add(MdSegment.Text(textBuf))
                textBuf = SpannableStringBuilder()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            if (!inFence && line.contains("|") && i + 1 < lines.size && isSeparatorRow(lines[i + 1])) {
                flushText()
                val header = splitRow(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trim().let { it.isNotEmpty() && it.contains("|") }) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                segments.add(MdSegment.Table(header, rows))
                continue
            }
            val isProseLine: Boolean
            if (line.trim().startsWith("```")) {
                flushProse()
                inFence = !inFence
                isProseLine = false
            } else if (inFence) {
                flushProse()
                appendCodeLine(textBuf, line)
                isProseLine = false
            } else when {
                line.startsWith("→ ") -> {
                    flushProse()
                    appendDim(textBuf, line, dimColor)
                    isProseLine = false
                }
                headerRe.matches(line) -> {
                    flushProse()
                    val m = headerRe.matchEntire(line)!!
                    appendHeader(textBuf, m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trimEnd())
                    isProseLine = false
                }
                hrRe.matches(line) -> {
                    flushProse()
                    val start = textBuf.length
                    textBuf.append("\u2500".repeat(24))
                    textBuf.setSpan(ForegroundColorSpan(dimColor), start, textBuf.length, 0)
                    isProseLine = false
                }
                line.trimStart().startsWith(">") -> {
                    flushProse()
                    val start = textBuf.length
                    textBuf.append("\u258E ")
                    textBuf.setSpan(ForegroundColorSpan(dimColor), start, textBuf.length, 0)
                    val bodyStart = textBuf.length
                    appendInline(textBuf, line.trimStart().removePrefix(">").trimStart())
                    textBuf.setSpan(StyleSpan(Typeface.ITALIC), bodyStart, textBuf.length, 0)
                    isProseLine = false
                }
                bulletRe.matches(line) -> {
                    flushProse()
                    val m = bulletRe.matchEntire(line)!!
                    var body = m.groupValues[2]
                    var marker = "\u2022 "
                    if (body.startsWith("[ ] ")) { marker = "\u2610 "; body = body.substring(4) }
                    else if (body.startsWith("[x] ") || body.startsWith("[X] ")) { marker = "\u2611 "; body = body.substring(4) }
                    textBuf.append(m.groupValues[1]).append(marker)
                    appendInline(textBuf, body)
                    isProseLine = false
                }
                else -> {
                    if (proseBuf.isNotEmpty()) proseBuf.append("\n")
                    proseBuf.append(line)
                    isProseLine = true
                }
            }
            if (i != lines.lastIndex && !isProseLine) textBuf.append("\n")
            i++
        }
        flushText()
        return segments
    }

    private fun isSeparatorRow(line: String): Boolean {
        val t = line.trim()
        if (!t.contains("|") || !t.contains("-")) return false
        return t.all { it == '-' || it == ':' || it == '|' || it == ' ' }
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split("|").map { it.trim() }
    }

    private val headerRe = Regex("^ {0,3}(#{2,6}|#(?=\\s))\\s*(.*)$") // "##Heading" with no space counts too (but "#tag" does not)
    private val hrRe = Regex("^ {0,3}([-*_])( *\\1){2,} *$")
    private val bulletRe = Regex("^( *)[-*+] +(.*)$")

    private fun appendHeader(out: SpannableStringBuilder, level: Int, text: String) {
        val start = out.length
        appendInline(out, text)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, 0)
        val size = when (level) { 1 -> 1.4f; 2 -> 1.25f; 3 -> 1.12f; else -> 1.0f }
        if (size != 1.0f) out.setSpan(RelativeSizeSpan(size), start, out.length, 0)
    }

    private fun appendDim(out: SpannableStringBuilder, text: String, dimColor: Int) {
        val start = out.length
        out.append(text)
        out.setSpan(ForegroundColorSpan(dimColor), start, out.length, 0)
        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, 0)
    }

    private fun appendCodeLine(out: SpannableStringBuilder, text: String) {
        val start = out.length
        SyntaxHighlight.append(out, text)
        out.setSpan(TypefaceSpan("monospace"), start, out.length, 0)
    }

    // Inline **bold**, *italic*, and `code` spans within one line. Simple
    // left-to-right scan, not a real tokenizer -- an odd number of
    // **/*/` on a line just renders the trailing marker literally rather
    // than guessing intent. No background span on code -- monospace +
    // SyntaxHighlight's per-token foreground colors are the only visual
    // difference from prose (asked for explicitly: "don't change color of
    // code in messages, just apply syntax coloring" -- a tinted box read
    // as an unwanted color change).
    private fun normalizeUrl(raw: String): String? {
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val lower = raw.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") -> raw
            Regex("^[a-z][a-z0-9+.-]*:").containsMatchIn(lower) -> null // other schemes: not opened
            raw.startsWith("/") || raw.startsWith("#") -> null
            else -> "https://$raw"
        }
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i) || (text.startsWith("__", i) && (i == 0 || !text[i - 1].isLetterOrDigit()))) {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    val start = out.length
                    appendInline(out, text.substring(i + 2, end))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, 0)
                    i = end + 2
                    continue
                }
            } else if (text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    val start = out.length
                    appendInline(out, text.substring(i + 2, end))
                    out.setSpan(android.text.style.StrikethroughSpan(), start, out.length, 0)
                    i = end + 2
                    continue
                }
            } else if (text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ') {
                // Single asterisk (reported live 2026-09-09 -- "single
                // asterisks rather than double surrounding words" was
                // never handled at all, only **bold**/`code`; *word*
                // rendered as literal asterisks). Guarded against a space
                // right after the opening marker or right before the
                // closing one -- CommonMark itself requires no interior
                // whitespace there, and without the guard a bare "*" used
                // mid-sentence (a glob, a multiplication) would get
                // mistaken for an opening marker.
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') {
                    val start = out.length
                    out.append(text.substring(i + 1, end))
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, 0)
                    i = end + 1
                    continue
                }
            } else if (text[i] == '[') {
                // [label](url). Scheme-less targets (www.example.com,
                // example.com/x) get https:// prepended.
                val close = text.indexOf("](", i + 1)
                // Balanced scan: URLs may contain parens, e.g. "...(V4)%2011.pdf".
                var paren = -1
                if (close > 0) {
                    var depth = 1
                    var j = close + 2
                    while (j < text.length) {
                        val c = text[j]
                        if (c == '(') depth++
                        else if (c == ')' && --depth == 0) { paren = j; break }
                        else if (c.isWhitespace()) break
                        j++
                    }
                }
                if (paren > 0) {
                    val label = text.substring(i + 1, close)
                    val url = normalizeUrl(text.substring(close + 2, paren).trim())
                    if (label.isNotEmpty() && url != null) {
                        val start = out.length
                        out.append(label)
                        out.setSpan(LinkSpan(url), start, out.length, 0)
                        i = paren + 1
                        continue
                    }
                }
            } else if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    val start = out.length
                    SyntaxHighlight.append(out, text.substring(i + 1, end))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, 0)
                    i = end + 1
                    continue
                }
            }
            out.append(text[i])
            i++
        }
    }
}

/** Markdown link. Opened from MessageAdapter's tap handler (the text is
 *  selectable, so LinkMovementMethod can't be used), not via onClick. */
class LinkSpan(val url: String) : ClickableSpan() {
    override fun onClick(widget: android.view.View) = open(widget.context)
    fun open(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
    override fun updateDrawState(ds: TextPaint) {
        ds.color = Theme.primary
        ds.isUnderlineText = true
    }
}
