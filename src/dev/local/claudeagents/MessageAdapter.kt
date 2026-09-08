package dev.local.claudeagents

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

class MessageAdapter(private val context: Context) : BaseAdapter() {
    var items: List<ChatDisplayRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // Which individual tool items (inside a bundle) are currently expanded,
    // keyed by ToolCallItem.id -- the transcript line number, stable across
    // notifyDataSetChanged()/view recycling unlike position (asked for
    // explicitly: "when it just runs commands collapse these messages by
    // default and let user tap on them so they get shown if needed").
    private val expandedIds = mutableSetOf<String>()

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ChatDisplayRow = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    // "body" holds a variable number/kind of children per message (plain
    // text segments interleaved with real TableLayout grids for any GFM
    // pipe tables -- see Markdown.kt's MdSegment; or, for a tool bundle,
    // one sub-container per tool item), so unlike role/status it can't be a
    // single recycled TextView; it's cleared and rebuilt fresh on every
    // bind instead. Cheap enough for a chat-sized list.
    private class Holder(val outer: LinearLayout, val bubble: LinearLayout, val role: TextView, val body: LinearLayout, val status: TextView)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View
        val holder: Holder
        val dp = { v: Int -> Theme.dp(context, v) }
        if (convertView == null) {
            val outer = LinearLayout(context)
            outer.orientation = LinearLayout.VERTICAL
            outer.setPadding(dp(12), dp(4), dp(12), dp(4))

            val bubble = LinearLayout(context)
            bubble.orientation = LinearLayout.VERTICAL
            val bubblePad = dp(12)
            bubble.setPadding(bubblePad, dp(8), bubblePad, dp(8))

            val role = TextView(context)
            role.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            role.textSize = 11f
            bubble.addView(role)

            val body = LinearLayout(context)
            body.orientation = LinearLayout.VERTICAL
            bubble.addView(body)

            val status = TextView(context)
            status.textSize = 11f
            status.setPadding(0, dp(3), 0, 0)
            bubble.addView(status)

            outer.addView(bubble)
            view = outer
            holder = Holder(outer, bubble, role, body, status)
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as Holder
        }

        val m = items[position]
        val items0 = m.toolItems
        val isBundle = items0 != null
        val isAttachment = m.attachment != null
        // "user" is a real human-typed message. A tool result is *also*
        // wire-formatted as a `type: "user"` transcript line (see the
        // daemon's parse_transcript_line) but reclassified server-side to
        // role "tool_result" -- it's the output of a Bash/Read/etc call
        // being fed back to Claude, not something the human said, and
        // rendering it as a "YOU" bubble read as exactly backwards
        // (reported live 2026-09-05: a daemon status printout the model
        // itself produced was showing as if the user had typed it).
        val isUser = !isBundle && m.role == "user"
        // Claude Code's own synthetic error turn (see the daemon's
        // parse_transcript_line / RelayClient.errorType) -- used to render
        // as an ordinary CLAUDE bubble reading "Prompt is too long" with no
        // indication anything unusual had happened or that anything could
        // be done about it (reported live 2026-09-07/08). Rendered instead
        // as a distinct warning bubble, with a one-tap Compact action for
        // the context-limit case specifically.
        val isError = !isBundle && m.role == "error"
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.86).toInt()

        holder.bubble.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        holder.bubble.minimumWidth = 0
        holder.bubble.background = Theme.roundedDrawable(
            when {
                isError -> Theme.errorContainer
                isUser -> Theme.surfaceContainer
                isBundle -> Theme.bg
                else -> Theme.surface
            },
            context,
            strokeColor = when {
                isError -> Theme.error
                isUser -> Theme.primary and 0x55FFFFFF.toInt()
                isBundle -> Theme.outlineVariant and 0x88FFFFFF.toInt()
                else -> Theme.outlineVariant
            }
        )
        holder.outer.gravity = if (isUser) Gravity.END else Gravity.START
        // Copy/Read-aloud menu -- a single tap (asked for explicitly:
        // "should appear when i tap any message once rather than long
        // press" -- was long-press until 2026-09-08) -- only on a plain
        // prose row (user/assistant/error text), not a tool-call bundle
        // (those already use a tap on each item to expand/collapse it
        // individually -- a second, competing tap gesture on the same
        // bubble would be confusing) and not an attachment (nothing
        // textual worth reading, and its own Download text has its own
        // tap target).
        val wantsMessageMenu = !isBundle && !isAttachment && m.text.isNotBlank()
        holder.bubble.isClickable = wantsMessageMenu
        holder.bubble.setOnClickListener(if (wantsMessageMenu) { v -> showMessageMenu(v, m) } else null)

        holder.role.text = when {
            isError -> when (m.errorType) {
                "context_limit" -> "CONTEXT LIMIT"
                "rate_limit" -> "RATE LIMIT"
                else -> "ERROR"
            }
            isUser -> "YOU"
            isBundle -> when {
                items0.size > 1 -> "TOOLS (${items0.size})"
                items0[0].isStandaloneResult -> "RESULT"
                else -> "TOOL"
            }
            else -> "CLAUDE"
        }
        holder.role.setTextColor(when {
            isError -> Theme.error
            isUser -> Theme.primary
            isBundle -> Theme.muted
            else -> Theme.secondary
        })

        holder.body.removeAllViews()
        if (isError) {
            val tv = plainTextView(m.text, maxWidth)
            tv.setTextColor(Theme.onBackground)
            holder.body.addView(tv)
        } else if (isAttachment) {
            holder.body.addView(buildAttachmentView(m, maxWidth))
        } else if (isBundle) {
            items0.forEachIndexed { idx, item ->
                val key = item.id
                val expanded = expandedIds.contains(key)
                val itemContainer = LinearLayout(context)
                itemContainer.orientation = LinearLayout.VERTICAL
                if (idx > 0) itemContainer.setPadding(0, dp(6), 0, 0)
                itemContainer.addView(buildItemHeadlineRow(item, expanded, maxWidth))
                // Only the paired *result* is genuinely separate content
                // from what the headline row already shows -- expanding
                // that row itself now reveals the full call (or, for a
                // standalone result, the full result), so it's never
                // rendered a second time here (was showing the full text
                // twice: once truncated as the headline, once again in
                // full right below it).
                if (expanded && !item.isStandaloneResult && item.resultText != null) {
                    val tv = plainTextView(renderResultText(item.resultText), maxWidth)
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    tv.textSize = 12f
                    tv.setTextColor(Theme.onSurfaceVariant)
                    tv.setPadding(0, dp(4), 0, 0)
                    itemContainer.addView(tv)
                }
                itemContainer.isClickable = true
                itemContainer.background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, context, radiusDp = 4))
                itemContainer.setOnClickListener {
                    if (expanded) expandedIds.remove(key) else expandedIds.add(key)
                    notifyDataSetChanged()
                }
                holder.body.addView(itemContainer)
            }
        } else if (isUser) {
            holder.body.addView(plainTextView(m.text, maxWidth))
        } else {
            for (seg in Markdown.renderSegments(m.text, dimColor = Theme.muted)) {
                when (seg) {
                    is MdSegment.Text -> if (seg.spanned.isNotEmpty()) {
                        holder.body.addView(plainTextView(seg.spanned, maxWidth))
                    }
                    is MdSegment.Table -> holder.body.addView(buildTableView(seg))
                }
            }
        }

        // A selectable TextView (see plainTextView's setTextIsSelectable)
        // handles its own touch stream internally (cursor placement, then
        // long-press-to-select) -- that consumes the gesture before it
        // ever reaches a parent's OnClickListener (confirmed live: tapping
        // message text did nothing, and long-pressing it opened the
        // platform's Copy/Share/Select-all popup, in both cases because
        // only the bubble had a listener). Wiring the same listener
        // directly onto each content TextView fixes it the same way.
        if (wantsMessageMenu) {
            for (i in 0 until holder.body.childCount) {
                val child = holder.body.getChildAt(i)
                child.isClickable = true
                child.setOnClickListener { v -> showMessageMenu(v, m) }
            }
        }

        holder.status.text = m.status ?: ""
        holder.status.setTextColor(Theme.muted)
        holder.status.visibility = if (m.status == null) View.GONE else View.VISIBLE
        return view
    }

    private fun showMessageMenu(anchor: View, m: ChatDisplayRow) {
        val activity = context as? ChatActivity ?: return
        Theme.showMenu(context, anchor, listOf("Copy message", "Read aloud from here")) { choice ->
            when (choice) {
                "Copy message" -> activity.copyMessage(m.text)
                "Read aloud from here" -> m.id?.let { activity.readAloudFrom(it) }
            }
        }
    }

    // Headline shown for one tool item when its bundle is collapsed. Most
    // tools already put the useful bit inline in the first line ("→
    // Read(`path`)" etc); Bash is the exception -- its command sits on
    // *line 3* of the fenced ```bash block (see the daemon's
    // summarize_tool_use), so a plain first-line grab would just show
    // "→ Bash" with no hint what it ran. Pull the actual first command
    // line out instead (asked for explicitly: "when bash is called show
    // at least first line or something in the collapsed view").
    private fun headlineFor(item: ToolCallItem): CharSequence {
        val headline = when {
            item.isStandaloneResult ->
                "[result] " + (item.callText.lineSequence().firstOrNull() ?: "")
            item.callText.startsWith("→ Bash") -> {
                val cmdLine = item.callText.lineSequence().drop(2).firstOrNull { it.isNotBlank() }?.trim()
                if (cmdLine != null) "→ Bash: $cmdLine" else "→ Bash"
            }
            else -> item.callText.lineSequence().firstOrNull() ?: item.callText
        }
        return Markdown.renderInline(headline)
    }

    // The one line/block that toggles between collapsed and expanded for
    // one tool item inside a bundle, plus a trailing "<N>L ▸/▾" chip
    // (asked for explicitly: "add + - lines changed next to the collapsed
    // views"). Collapsed shows a single truncated line; expanded shows the
    // *same* text in full instead of appending a second copy below it
    // (reported: "Edit (/path/to/file)" was appearing once truncated, then
    // again in full right underneath -- confusing). Tapping this item's
    // own container toggles just this item, not the whole bundle.
    private fun buildItemHeadlineRow(item: ToolCallItem, expanded: Boolean, maxWidth: Int): View {
        val dp = { v: Int -> Theme.dp(context, v) }
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = if (expanded) Gravity.TOP else Gravity.CENTER_VERTICAL

        val label = TextView(context)
        label.maxWidth = maxWidth
        if (expanded) {
            label.maxLines = Int.MAX_VALUE
            label.ellipsize = null
            label.textSize = 12f
            label.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            label.setTextColor(Theme.onSurfaceVariant)
            label.text = if (item.isStandaloneResult) renderResultText(item.callText) else fullCallText(item.callText)
        } else {
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.textSize = 13f
            label.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC)
            label.setTextColor(Theme.muted)
            label.text = headlineFor(item)
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val resultBody = item.resultText ?: if (item.isStandaloneResult) item.callText else null
        val chip = TextView(context)
        chip.textSize = 11f
        chip.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        chip.setTextColor(Theme.muted)
        chip.setPadding(dp(6), 0, 0, 0)
        chip.text = buildString {
            if (resultBody != null) {
                append(resultBody.count { it == '\n' } + 1)
                append("L ")
            }
            append(if (expanded) "▾" else "▸")
        }
        row.addView(chip)
        return row
    }

    // Full (untruncated) call text, syntax-highlighted the same way a
    // normal Claude message is -- a tool call is plain text apart from
    // Bash's ```bash fence (see summarize_tool_use), so this only ever
    // produces MdSegment.Text in practice; Table is handled anyway since
    // Markdown.renderSegments is the same general-purpose pipeline.
    private fun fullCallText(callText: String): CharSequence {
        val out = SpannableStringBuilder()
        for (seg in Markdown.renderSegments(callText, dimColor = Theme.muted)) {
            if (seg is MdSegment.Text) {
                if (out.isNotEmpty()) out.append("\n")
                out.append(seg.spanned)
            }
        }
        return out
    }

    // A tool result was rendering as a flat, uncolored wall of text --
    // unlike a call, results never went through Markdown/SyntaxHighlight
    // at all (reported: reading a .kt file showed every line prefixed with
    // its `cat -n`-style line number and no syntax coloring). Applied
    // per-line here rather than through Markdown.renderSegments since a
    // result isn't markdown -- it's raw tool output (file contents, command
    // stdout, etc) that may itself contain ``` or other characters that
    // would otherwise be misparsed as formatting. The leading line-number
    // gutter Read emits (e.g. "   42\t") is dimmed separately from the
    // code itself, the way a real code viewer's gutter reads.
    private val LINE_NUMBER_PREFIX = Regex("""^(\s*\d+)(\t)""")

    private fun renderResultText(text: String): CharSequence {
        val out = SpannableStringBuilder()
        val lines = text.split("\n")
        for ((i, line) in lines.withIndex()) {
            val m = LINE_NUMBER_PREFIX.find(line)
            if (m != null) {
                val start = out.length
                out.append(m.value)
                out.setSpan(ForegroundColorSpan(Theme.muted), start, out.length, 0)
                SyntaxHighlight.append(out, line.substring(m.value.length))
            } else {
                SyntaxHighlight.append(out, line)
            }
            if (i != lines.lastIndex) out.append("\n")
        }
        return out
    }

    // Image: an actual decoded thumbnail (android.graphics.BitmapFactory,
    // the platform's own decoder -- not a third-party library, matching
    // what was asked for explicitly: "use only trusted libraries"). Any
    // other mime type: a filename/size card, same as an image with no
    // local copy yet (still uploading, or a fresh install with nothing
    // cached locally). Either way a Download action saves a real copy via
    // MediaStore.Downloads (see ChatActivity.downloadAttachment).
    private fun buildAttachmentView(m: ChatDisplayRow, maxWidth: Int): View {
        val dp = { v: Int -> Theme.dp(context, v) }
        val info = m.attachment ?: return LinearLayout(context)
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL

        val bitmap = if (info.mimeType.startsWith("image/") && info.localPath != null) {
            decodeSampledBitmap(info.localPath, maxWidth, dp(320))
        } else null

        if (bitmap != null) {
            val iv = ImageView(context)
            iv.setImageBitmap(bitmap)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.maxWidth = maxWidth
            iv.maxHeight = dp(320)
            container.addView(iv)
        } else {
            container.addView(buildAttachmentFileRow(info, dp))
        }

        if (info.caption.isNotEmpty()) {
            val cap = plainTextView(info.caption, maxWidth)
            cap.setPadding(0, dp(4), 0, 0)
            container.addView(cap)
        }

        val downloadBtn = TextView(context)
        downloadBtn.text = "⬇ Download"
        downloadBtn.textSize = 12f
        downloadBtn.setTextColor(Theme.primary)
        downloadBtn.setPadding(0, dp(6), 0, 0)
        downloadBtn.setOnClickListener {
            (context as? ChatActivity)?.downloadAttachment(info.localPath, info.attachmentId, info.filename, info.mimeType)
        }
        container.addView(downloadBtn)
        return container
    }

    private fun buildAttachmentFileRow(info: AttachmentInfo, dp: (Int) -> Int): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val icon = TextView(context)
        icon.text = if (info.mimeType.startsWith("image/")) "🖼" else "📄"
        icon.textSize = 22f
        row.addView(icon)
        val labelCol = LinearLayout(context)
        labelCol.orientation = LinearLayout.VERTICAL
        val name = TextView(context)
        name.text = info.filename
        name.textSize = 13f
        name.setTextColor(Theme.onBackground)
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        labelCol.addView(name)
        val size = TextView(context)
        size.text = formatBytes(info.size)
        size.textSize = 11f
        size.setTextColor(Theme.muted)
        labelCol.addView(size)
        val labelParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        labelParams.marginStart = dp(8)
        row.addView(labelCol, labelParams)
        return row
    }

    private fun formatBytes(size: Long): String {
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }

    // Decodes at a size just large enough for this bubble, not the file's
    // full resolution -- a multi-megapixel photo shouldn't cost tens of MB
    // of decoded bitmap memory just to show a small thumbnail. Standard
    // two-pass BitmapFactory pattern: measure bounds only, pick the
    // smallest power-of-two inSampleSize that still covers the target box,
    // then decode for real at that scale.
    private fun decodeSampledBitmap(path: String, maxWidthPx: Int, maxHeightPx: Int): android.graphics.Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidthPx && bounds.outHeight / (sample * 2) >= maxHeightPx) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun plainTextView(content: CharSequence, maxWidth: Int): TextView {
        val dp = { v: Int -> Theme.dp(context, v) }
        val tv = TextView(context)
        tv.textSize = 14f
        tv.setLineSpacing(dp(2).toFloat(), 1f)
        // NOT selectable (was true) -- a selectable TextView inside a
        // ListView row needs a first tap just to gain focus before its own
        // click listener ever fires, so opening the tap-menu took two taps
        // (reported live 2026-09-08). "Copy message" already covers
        // copying a whole message; native partial-text selection inside a
        // bubble is the trade-off for single-tap working reliably.
        tv.setPadding(0, dp(3), 0, 0)
        tv.setTextColor(Theme.onBackground)
        tv.maxWidth = maxWidth
        tv.text = content
        return tv
    }

    // A grid line color peeking through 1px margins around each cell --
    // TableLayout/TableRow have no border-drawing of their own, and this
    // is the plain-platform way to fake one without a 9-patch asset.
    private fun buildTableView(table: MdSegment.Table): View {
        val dp = { v: Int -> Theme.dp(context, v) }
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, dp(4), 0, dp(4))

        val grid = TableLayout(context)
        grid.setBackgroundColor(Theme.outlineVariant)
        grid.setPadding(dp(1), dp(1), dp(1), dp(1))

        fun addRow(cells: List<String>, isHeader: Boolean) {
            val row = TableRow(context)
            for (c in cells) {
                val cell = TextView(context)
                cell.text = if (isHeader) c else Markdown.renderInline(c)
                cell.textSize = 12f
                cell.setTextColor(Theme.onBackground)
                cell.setTypeface(Typeface.MONOSPACE, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
                cell.setPadding(dp(8), dp(5), dp(8), dp(5))
                cell.setBackgroundColor(if (isHeader) Theme.surfaceContainer else Theme.surface)
                val lp = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(1), dp(1), dp(1), dp(1))
                cell.layoutParams = lp
                row.addView(cell)
            }
            grid.addView(row)
        }

        addRow(table.header, true)
        for (r in table.rows) {
            val padded = when {
                r.size < table.header.size -> r + List(table.header.size - r.size) { "" }
                r.size > table.header.size -> r.take(table.header.size)
                else -> r
            }
            addRow(padded, false)
        }

        container.addView(grid)
        return container
    }
}
