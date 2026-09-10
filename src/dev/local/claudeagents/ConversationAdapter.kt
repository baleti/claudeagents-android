package dev.local.claudeagents

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Compact table, not stacked cards -- mirrors (a miniaturized version of)
 * the desktop's ctrl+alt+c process table (ClaudeUsageExpanded.qml): one
 * dense row per conversation, fixed-width columns, monospace. Column set
 * here is deliberately smaller than the desktop's 9-column table (no tmux/
 * hyprland/pid -- those describe a live *process*, which the phone has no
 * business identifying down to the pid; "live" is already the daemon's own
 * verdict). See ConversationAdapter.COLUMN_HEADER_TEXT / widths, which
 * MainActivity's header row reuses so the two stay pixel-aligned.
 */
object ConversationColumns {
    const val account = 26
    const val tokens = 46
    const val ago = 40

    // dir_key ("claude"/"claude2"/"claude3", see the daemon's ACCOUNT_DIRS)
    // -> the same 1/2/3 numbering used everywhere else on this machine for
    // the 3 accounts (the server's ~/.claude, ~/.claude2, ~/.claude3).
    fun accountNumber(dirKey: String): String = when (dirKey) {
        "claude" -> "1"
        "claude2" -> "2"
        "claude3" -> "3"
        // Genuinely unrecoverable, not a bug: ~57 of this machine's oldest
        // conversations predate the "bridge-session" transcript line the
        // daemon reads to attribute an account (confirmed live 2026-09-05
        // by checking how many lack it) -- a bare "?" read as broken, so
        // this renders as a plain dash instead (see the dimmer color
        // applied alongside it in ConversationAdapter.getView).
        else -> "–"
    }
}

// showTokens = false drops the TKNS column entirely (not just blanked --
// no reserved width either) -- asked for explicitly 2026-09-10 for the
// drawer's copy of this list, which is a narrow 300dp quick-switch panel
// with no room to spare for a column that matters far less there than on
// the full-width main table.
class ConversationAdapter(private val context: Context, private val showTokens: Boolean = true) : BaseAdapter() {
    var items: List<ConversationRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ConversationRow = items[position]
    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    private class Holder(
        val account: TextView, val title: TextView,
        val tokens: TextView?, val ago: TextView
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View
        val holder: Holder
        val dp = { v: Int -> Theme.dp(context, v) }
        if (convertView == null) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.VERTICAL
            row.background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, context, radiusDp = 0))

            val dataRow = LinearLayout(context)
            dataRow.orientation = LinearLayout.HORIZONTAL
            dataRow.gravity = Gravity.CENTER_VERTICAL
            dataRow.setPadding(dp(12), dp(9), dp(12), dp(9))

            // No live/dead dot -- every list this adapter ever populates
            // is already filtered to one liveness state or the other (see
            // MainActivity/ArchiveActivity/ChatActivity's drawer), so the
            // dot never actually varied within a single list and just
            // repeated information the screen itself already establishes
            // (removed, asked for explicitly 2026-09-10: "i think its
            // redundant... we are only supposed to show active sessions
            // anyway").
            val account = TextView(context)
            account.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            account.textSize = 11f
            account.maxLines = 1
            account.gravity = Gravity.CENTER
            account.ellipsize = android.text.TextUtils.TruncateAt.END
            dataRow.addView(account, LinearLayout.LayoutParams(dp(ConversationColumns.account), LinearLayout.LayoutParams.WRAP_CONTENT))

            val title = TextView(context)
            title.textSize = 13f
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            titleParams.marginStart = dp(8)
            titleParams.marginEnd = dp(8)
            dataRow.addView(title, titleParams)

            val tokens = if (showTokens) {
                TextView(context).also {
                    it.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    it.textSize = 11f
                    it.gravity = Gravity.END
                    dataRow.addView(it, LinearLayout.LayoutParams(dp(ConversationColumns.tokens), LinearLayout.LayoutParams.WRAP_CONTENT))
                }
            } else {
                null
            }

            val ago = TextView(context)
            ago.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            ago.textSize = 11f
            ago.gravity = Gravity.END
            val agoParams = LinearLayout.LayoutParams(dp(ConversationColumns.ago), LinearLayout.LayoutParams.WRAP_CONTENT)
            agoParams.marginStart = dp(6)
            dataRow.addView(ago, agoParams)

            row.addView(dataRow)

            val divider = View(context)
            divider.setBackgroundColor(Theme.outlineVariant and 0x2AFFFFFF.toInt())
            row.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

            view = row
            holder = Holder(account, title, tokens, ago)
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as Holder
        }

        val c = items[position]
        val isKnownAccount = c.account in setOf("claude", "claude2", "claude3")
        holder.account.text = ConversationColumns.accountNumber(c.account)
        holder.account.setTextColor(if (isKnownAccount) Theme.onSurfaceVariant else Theme.muted and 0x66FFFFFF.toInt())
        holder.title.text = c.title
        holder.title.setTextColor(Theme.onBackground)
        holder.tokens?.text = Fmt.tokens(c.tokens)
        holder.tokens?.setTextColor(Theme.muted)
        holder.ago.text = Fmt.ago(c.mtime)
        holder.ago.setTextColor(Theme.muted)
        return view
    }
}
