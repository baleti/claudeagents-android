package dev.local.claudeagents

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

class MessageAdapter(private val context: Context) : BaseAdapter() {
    var items: List<ChatDisplayRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // Set once by ChatActivity right after `listView.adapter = this` --
    // needed so setHighlight() below can find the currently-bound View for
    // a given row (if it's on screen right now) without a full
    // notifyDataSetChanged() on every ~60ms highlight tick, which would
    // rebuild every visible row and read as flicker/jank during a read.
    var listView: ListView? = null

    // Read-aloud word-highlight target -- which row, and which character
    // range within that row's OWN text (see ReadAloudController's
    // per-section onWordHighlight). Applied both here (for a cheap direct
    // update when the row is already on screen) and in getView() itself
    // (so scrolling the highlighted row back into view shows the right
    // state immediately, matching how expandedIds already survives
    // recycling).
    private var highlightRowId: String? = null
    private var highlightRange: IntRange? = null

    fun setHighlight(rowId: String?, range: IntRange?) {
        val previousRowId = highlightRowId
        highlightRowId = rowId
        highlightRange = range
        val lv = listView ?: return
        // Moving to a new row (a new section started, see
        // ReadAloudController.onSectionChanged) leaves the old row's View
        // still showing its last-applied highlight span -- ListView
        // recycles views, so nothing else would ever clear it until that
        // exact View instance happens to get rebound to different content
        // (confirmed live 2026-09-09: the previous bubble's highlighted
        // word stayed highlighted after the read moved on to the next
        // message). Re-render it with no range first.
        if (previousRowId != null && previousRowId != rowId) renderRowHighlight(lv, previousRowId, null)
        renderRowHighlight(lv, rowId, range)
    }

    /** Cheap in-place update of one row's already-bound TextView, used for
     * every highlight tick instead of notifyDataSetChanged() (would
     * rebuild every visible row ~every 60ms -- visible flicker/jank during
     * a read). Only ever touches a row highlightableText() actually
     * approves -- a tool bundle, attachment, or a message whose markdown
     * rendering changed its text can't safely have m.text's offsets
     * applied to whatever's really on screen, so those are left alone
     * exactly as getView() last rendered them. */
    private fun renderRowHighlight(lv: ListView, rowId: String?, range: IntRange?) {
        if (rowId == null) return
        val pos = items.indexOfFirst { it.id == rowId }
        if (pos < 0) return
        val m = items[pos]
        val base = highlightableText(m) ?: return
        val childIdx = pos - lv.firstVisiblePosition
        if (childIdx !in 0 until lv.childCount) return
        val holder = lv.getChildAt(childIdx)?.tag as? Holder ?: return
        val tv = holder.body.getChildAt(0) as? TextView ?: return
        tv.text = withHighlight(base, range)
    }

    // The exact text a row's TextView shows when NOT highlighted, only for
    // rows where read-aloud highlighting can safely apply -- shared between
    // getView() (initial/recycled bind) and renderRowHighlight() (per-tick
    // update) so the two can never disagree about what "unhighlighted"
    // looks like for a given row.
    private fun highlightableText(m: ChatDisplayRow): CharSequence? {
        if (m.toolItems != null || m.attachment != null) return null
        if (m.role == "user") return m.text
        // ChatActivity.readAloudFrom() now sends this SAME rendered plain
        // text (Markdown.singleSegmentPlainText) to the TTS server, not
        // the raw markdown source -- so word-timing offsets always line up
        // with what's shown here. Previously this required the rendered
        // form to be byte-IDENTICAL to the raw source (i.e. only messages
        // with no markdown at all), which real Claude replies almost never
        // satisfy, silently disabling highlighting for nearly every
        // message (reported live 2026-09-21: "still isn't highlighting").
        // Only requirement left: a single plain-text segment, no table --
        // see MessageAdapter's getView for the matching fallback when this
        // returns null.
        val segments = Markdown.renderSegments(m.text, dimColor = Theme.muted)
        return (segments.singleOrNull() as? MdSegment.Text)?.spanned
    }

    private fun withHighlight(text: CharSequence, range: IntRange?): CharSequence {
        if (range == null) return text
        val sb = SpannableStringBuilder(text)
        val start = range.first.coerceIn(0, sb.length)
        val end = (range.last + 1).coerceIn(start, sb.length)
        if (start < end) sb.setSpan(BackgroundColorSpan(0x552196F3), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
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
    private class Holder(val outer: LinearLayout, val bubble: LinearLayout, val role: TextView, val time: TextView, val body: LinearLayout, val status: TextView)

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
            role.id = View.generateViewId()

            val time = TextView(context)
            time.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            time.textSize = 9f
            time.setTextColor(Theme.muted)
            time.maxLines = 1
            // END_OF(role)+ALIGN_PARENT_END makes this view span the gap, so the
            // text itself must be end-aligned to land at the far right.
            time.gravity = Gravity.END

            // Role label left, timestamp pinned top-right (smaller font);
            // RelativeLayout so the timestamp stays right-aligned even in a
            // shrink-wrapped short bubble, never overlapping the label.
            val header = android.widget.RelativeLayout(context)
            header.addView(role, android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val timeLp = android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            timeLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            timeLp.addRule(android.widget.RelativeLayout.END_OF, role.id)
            timeLp.addRule(android.widget.RelativeLayout.ALIGN_BASELINE, role.id)
            timeLp.marginStart = dp(14)
            header.addView(time, timeLp)
            bubble.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val body = LinearLayout(context)
            body.orientation = LinearLayout.VERTICAL
            bubble.addView(body)

            val status = TextView(context)
            status.textSize = 11f
            status.setPadding(0, dp(3), 0, 0)
            bubble.addView(status)

            outer.addView(bubble)
            view = outer
            holder = Holder(outer, bubble, role, time, body, status)
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

        holder.time.text = if (m.tsMs > 0L) Fmt.stamp(m.tsMs) else ""

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
            val base = highlightableText(m) ?: m.text
            val text = if (m.id == highlightRowId) withHighlight(base, highlightRange) else base
            holder.body.addView(plainTextView(text, maxWidth))
        } else {
            // highlightableText() null means rendering isn't a safe target
            // for a read-aloud highlight (real markdown formatting, a
            // table, ...) -- falls back to the normal unconditional
            // per-segment render, same as before highlighting existed.
            // Audio still plays fine either way; see highlightableText's
            // own doc comment for why.
            val base = highlightableText(m)
            if (base != null) {
                if (base.isNotEmpty()) {
                    val text = if (m.id == highlightRowId) withHighlight(base, highlightRange) else base
                    holder.body.addView(plainTextView(text, maxWidth))
                }
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
        }

        // Single tap opens Copy/Read-aloud; long-press (or double-tap) on a
        // word instead selects it and hands off to the platform's normal
        // text-selection controls -- drag handles, extend-selection, and
        // (since the TextView is selectable, see plainTextView) Android's
        // built-in Smart Text Selection, which expands a long-press/
        // double-tap to grab a whole recognized URL/email/etc in one go
        // instead of stopping at internal punctuation (asked for
        // explicitly 2026-09-09: "when selecting hyperlinks have that
        // entire hyperlink select at once").
        //
        // Getting both gestures out of ONE selectable TextView needed a
        // real GestureDetector rather than a plain OnClickListener:
        // View.OnClickListener/performClick() and a selectable TextView's
        // own touch handling fight over the same first tap (confirmed
        // live 2026-09-08 -- opening the menu took two taps, since the
        // first one was consumed just focusing the view). A GestureDetector
        // fed through OnTouchListener sees every raw MotionEvent in
        // parallel -- onSingleTapConfirmed only fires once it's sure the
        // gesture wasn't the start of a double-tap/long-press, and
        // returning false from the listener still lets the SAME events
        // reach the TextView's own Editor afterward for selection.
        if (wantsMessageMenu) {
            // Tracked from ACTION_DOWN, in holder.bubble's own local
            // space, converted from whichever child view actually
            // received the touch (the listener below is shared across
            // the bubble and every one of its body children) -- lets the
            // popup open right where the finger was instead of always at
            // the bubble's own top/bottom edge (asked for explicitly
            // 2026-09-12).
            var tapX = 0f
            var tapY = 0f
            val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true // required for onSingleTapConfirmed to ever fire
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    showMessageMenu(holder.bubble, tapX, tapY, m)
                    return true
                }
            })
            val touchListener = View.OnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val vLoc = IntArray(2)
                    v.getLocationOnScreen(vLoc)
                    val bubbleLoc = IntArray(2)
                    holder.bubble.getLocationOnScreen(bubbleLoc)
                    tapX = event.x + (vLoc[0] - bubbleLoc[0])
                    tapY = event.y + (vLoc[1] - bubbleLoc[1])
                }
                tapDetector.onTouchEvent(event)
                false
            }
            holder.bubble.setOnTouchListener(touchListener)
            for (i in 0 until holder.body.childCount) {
                holder.body.getChildAt(i).setOnTouchListener(touchListener)
            }
        } else {
            holder.bubble.setOnTouchListener(null)
            for (i in 0 until holder.body.childCount) {
                holder.body.getChildAt(i).setOnTouchListener(null)
            }
        }

        holder.status.text = m.status ?: ""
        holder.status.setTextColor(Theme.muted)
        holder.status.visibility = if (m.status == null) View.GONE else View.VISIBLE
        return view
    }

    private fun showMessageMenu(anchor: View, tapX: Float, tapY: Float, m: ChatDisplayRow) {
        val activity = context as? ChatActivity ?: return
        // Only a synced transcript row has a line number to restore to
        // (not an "outbox_<id>" one that hasn't landed yet).
        val line = m.id?.toIntOrNull()
        val options = mutableListOf("Copy message", "Read aloud from here")
        if (line != null) options.add("Restore conversation to here")
        Theme.showMenu(context, anchor, options, tapX.toInt(), tapY.toInt()) { choice ->
            when (choice) {
                "Restore conversation to here" -> if (line != null) activity.restoreConversationTo(line)
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
        // Selectable again (see the GestureDetector wiring in getView,
        // which is what makes single-tap-for-menu and native long-press
        // selection coexist without the earlier two-taps-needed bug).
        tv.setTextIsSelectable(true)
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

        // Table columns are WRAP_CONTENT with no cap, so a wide table (many
        // columns, or long cell content) can render wider than the screen
        // -- reported 2026-09-11 as unreadable with no way to see the rest.
        // A HorizontalScrollView here just lets that overflow be panned to
        // instead of silently clipped; it never fires for a table that
        // already fits, since a ScrollView with no scrollable excess is a
        // no-op wrapper.
        val scroller = TableScrollView(context)
        scroller.isFillViewport = false
        scroller.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroller.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(scroller)
        return container
    }
}
