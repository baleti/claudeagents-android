package dev.local.clauderelay

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
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

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ChatDisplayRow = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    // "body" holds a variable number/kind of children per message (plain
    // text segments interleaved with real TableLayout grids for any GFM
    // pipe tables -- see Markdown.kt's MdSegment), so unlike role/status
    // it can't be a single recycled TextView; it's cleared and rebuilt
    // fresh on every bind instead. Cheap enough for a chat-sized list.
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
        val isUser = m.role == "user"
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.86).toInt()

        holder.bubble.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        holder.bubble.minimumWidth = 0
        holder.bubble.background = Theme.roundedDrawable(
            if (isUser) Theme.surfaceContainer else Theme.surface,
            context,
            strokeColor = if (isUser) Theme.primary and 0x55FFFFFF.toInt() else Theme.outlineVariant
        )
        holder.outer.gravity = if (isUser) Gravity.END else Gravity.START

        holder.role.text = if (isUser) "YOU" else "CLAUDE"
        holder.role.setTextColor(if (isUser) Theme.primary else Theme.secondary)

        holder.body.removeAllViews()
        if (isUser) {
            holder.body.addView(plainTextView(m.text, maxWidth))
        } else {
            for (seg in Markdown.renderSegments(m.text, codeBg = Theme.surfaceContainer, dimColor = Theme.muted)) {
                when (seg) {
                    is MdSegment.Text -> if (seg.spanned.isNotEmpty()) {
                        val tv = plainTextView(seg.spanned, maxWidth)
                        holder.body.addView(tv)
                    }
                    is MdSegment.Table -> holder.body.addView(buildTableView(seg))
                }
            }
        }

        holder.status.text = m.status ?: ""
        holder.status.setTextColor(Theme.muted)
        holder.status.visibility = if (m.status == null) View.GONE else View.VISIBLE
        return view
    }

    private fun plainTextView(content: CharSequence, maxWidth: Int): TextView {
        val dp = { v: Int -> Theme.dp(context, v) }
        val tv = TextView(context)
        tv.textSize = 14f
        tv.setLineSpacing(dp(2).toFloat(), 1f)
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
                cell.text = if (isHeader) c else Markdown.renderInline(c, Theme.bg)
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
