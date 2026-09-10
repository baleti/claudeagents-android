package dev.local.claudeagents

import android.graphics.Typeface
import android.text.SpannableStringBuilder
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
                line.trimStart().startsWith("# ") -> {
                    flushProse()
                    appendHeader(textBuf, line.trimStart().removePrefix("# "))
                    isProseLine = false
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    flushProse()
                    val indent = line.takeWhile { it == ' ' }
                    textBuf.append(indent).append("• ")
                    appendInline(textBuf, line.trimStart().removePrefix("- ").removePrefix("* "))
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

    private fun appendHeader(out: SpannableStringBuilder, text: String) {
        val start = out.length
        out.append(text)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, 0)
        out.setSpan(RelativeSizeSpan(1.1f), start, out.length, 0)
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
    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    val start = out.length
                    out.append(text.substring(i + 2, end))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, 0)
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
