package dev.local.claudeagents

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView

// Closed conversations -- everything MainActivity's own list filters OUT
// (see its allConversations comment: only live ones show there, "don't
// show archived ones" was asked for explicitly 2026-09-07). Reached via
// MainActivity's three-dots menu (asked for explicitly 2026-09-09): same
// list shape, same search, but for the (likely much longer) history of
// conversations that aren't running right now. Opening one shows its
// cached transcript same as any live conversation; sending a message here
// relaunches it via `claude --resume` instead of typing into a live pane
// that doesn't exist -- see ChatActivity.archivedOrigin / OutboxLogic /
// claude-agents-daemon.py resume_session.
class ArchiveActivity : Activity() {
    private lateinit var db: Db
    private lateinit var root: LinearLayout
    private var adapter: ConversationAdapter? = null
    private var searchPopup: PopupWindow? = null
    private var pullIndicator: ProgressBar? = null

    // Same cache-then-filter split as MainActivity.allConversations -- a
    // sync landing mid-search must not clear what's typed.
    private var allConversations: List<ConversationRow> = emptyList()
    private var searchQuery: String = ""
    private var syncSpinner: View? = null
    private var syncLabel: TextView? = null
    private var totalCountLabel: TextView? = null
    private var syncingNow = false

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SyncLogic.ACTION_SYNC_STARTED) {
                syncingNow = true
                syncSpinner?.visibility = View.VISIBLE
                updateSyncStatusLabel()
                return
            }
            syncingNow = false
            syncSpinner?.visibility = View.GONE
            updateSyncStatusLabel()
            refreshList()
            hidePullIndicator()
        }
    }

    private fun updateSyncStatusLabel() {
        val prefix = if (syncingNow) "Syncing… " else ""
        val at = SyncLogic.lastSyncAt(this) ?: run {
            syncLabel?.text = prefix + "not synced yet"
            return
        }
        syncLabel?.text = "$prefix" + "synced ${Fmt.ago(at / 1000.0)} ago"
    }

    // Same "ages go stale on a static screen" fix as MainActivity.ageTick.
    private val ageTickHandler = Handler(Looper.getMainLooper())
    private val ageTick = object : Runnable {
        override fun run() {
            adapter?.notifyDataSetChanged()
            updateSyncStatusLabel()
            ageTickHandler.postDelayed(this, 30_000)
        }
    }

    private fun dp(v: Int): Int = Theme.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.getInstance(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.bg)
        setContentView(root)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        actionBar?.hide()
        buildView()
    }

    override fun onResume() {
        super.onResume()
        val syncFilter = IntentFilter().apply {
            addAction(SyncLogic.ACTION_SYNC_COMPLETE)
            addAction(SyncLogic.ACTION_SYNC_STARTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(syncReceiver, syncFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(syncReceiver, syncFilter)
        }
        refreshList()
        updateSyncStatusLabel()
        ageTickHandler.removeCallbacks(ageTick)
        ageTickHandler.postDelayed(ageTick, 30_000)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(syncReceiver)
        } catch (e: IllegalArgumentException) {
        }
        ageTickHandler.removeCallbacks(ageTick)
        searchPopup?.dismiss()
    }

    private fun buildView() {
        val titleBar = LinearLayout(this)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setPadding(dp(16), dp(14), dp(16), dp(4))
        val backButton = TextView(this)
        backButton.text = "←"
        backButton.textSize = 20f
        backButton.setTextColor(Theme.onBackground)
        backButton.setPadding(dp(4), dp(4), dp(14), dp(4))
        backButton.setOnClickListener { finish() }
        titleBar.addView(backButton)
        val title = TextView(this)
        title.text = "Archive"
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Theme.onBackground)
        titleBar.addView(title)
        root.addView(titleBar)

        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        val searchRowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        searchRowParams.leftMargin = dp(12)
        searchRowParams.rightMargin = dp(12)
        searchRowParams.topMargin = dp(8)
        searchRowParams.bottomMargin = dp(4)
        root.addView(searchRow, searchRowParams)

        val searchInput = EditText(this)
        // Archive is expected to hold far more rows than the live list --
        // asked for explicitly ("searchbox is very important in that
        // archive view as there likely will be many more conversation that
        // you can manage") -- same query DSL as the main list.
        searchInput.hint = "Search… (/fv /s /rv, see query-dsl.md)"
        searchInput.setSingleLine(true)
        searchInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        Theme.styleEditText(searchInput, this)
        val searchParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        searchRow.addView(searchInput, searchParams)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                applyFilter()
                updateSearchPopup(searchInput)
            }
        })

        val syncRow = LinearLayout(this)
        syncRow.orientation = LinearLayout.HORIZONTAL
        syncRow.gravity = Gravity.CENTER_VERTICAL
        val spinner = ProgressBar(this)
        spinner.isIndeterminate = true
        spinner.visibility = View.GONE
        syncRow.addView(spinner, LinearLayout.LayoutParams(dp(14), dp(14)))
        syncSpinner = spinner
        val sLabel = TextView(this)
        sLabel.textSize = 11f
        sLabel.setTextColor(Theme.muted)
        val syncLabelParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        syncLabelParams.marginStart = dp(6)
        syncRow.addView(sLabel, syncLabelParams)
        syncLabel = sLabel
        updateSyncStatusLabel()
        val syncRowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        syncRowParams.leftMargin = dp(12)
        syncRowParams.topMargin = dp(2)
        syncRowParams.bottomMargin = dp(2)
        root.addView(syncRow, syncRowParams)

        // Same small total-count line as MainActivity's conversation list
        // (asked for explicitly 2026-09-11) -- counts allConversations
        // (unfiltered), i.e. all archived conversations, not the current
        // search results.
        val totalLabel = TextView(this)
        totalLabel.textSize = 11f
        totalLabel.setTextColor(Theme.muted)
        val totalLabelParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        totalLabelParams.leftMargin = dp(12)
        totalLabelParams.bottomMargin = dp(4)
        root.addView(totalLabel, totalLabelParams)
        totalCountLabel = totalLabel

        val listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setBackgroundColor(Theme.bg)
        val a = ConversationAdapter(this)
        adapter = a
        listView.adapter = a
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val c = a.items[position]
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("session_id", c.id)
            intent.putExtra("title", c.title)
            // Not live right now (that's why it's in this list) -- a send
            // from this screen should relaunch it via --resume rather than
            // type into a pane that doesn't exist. See ChatActivity.
            intent.putExtra("archived_origin", true)
            startActivity(intent)
        }
        val pullContainer = FrameLayout(this)
        pullContainer.addView(listView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val indicator = ProgressBar(this)
        indicator.isIndeterminate = true
        indicator.visibility = View.INVISIBLE
        indicator.translationY = -dp(48).toFloat()
        val indicatorParams = FrameLayout.LayoutParams(dp(28), dp(28))
        indicatorParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        indicatorParams.topMargin = dp(6)
        pullContainer.addView(indicator, indicatorParams)
        pullIndicator = indicator

        installPullToRefresh(listView, indicator)
        root.addView(pullContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (allConversations.isEmpty()) {
            refreshList()
        }
    }

    // Same hand-rolled pull-to-refresh as MainActivity (no androidx
    // SwipeRefreshLayout available in this build).
    private var pullStartY = 0f
    private var pullTriggered = false
    private var pullDragging = false
    private fun installPullToRefresh(listView: ListView, indicator: ProgressBar) {
        val thresholdPx = dp(70)
        val maxDragPx = dp(110).toFloat()
        val restY = dp(8).toFloat()
        val hiddenY = -dp(48).toFloat()
        listView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.rawY
                    pullTriggered = false
                    pullDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val atTop = listView.firstVisiblePosition == 0 &&
                        (listView.getChildAt(0)?.top ?: 0) >= 0
                    val delta = event.rawY - pullStartY
                    if (atTop && delta > 0) {
                        pullDragging = true
                        val progress = (delta / maxDragPx).coerceIn(0f, 1f)
                        indicator.visibility = View.VISIBLE
                        indicator.alpha = progress.coerceAtLeast(0.35f)
                        indicator.translationY = hiddenY + progress * (restY - hiddenY)
                        if (delta > thresholdPx) pullTriggered = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pullTriggered) {
                        indicator.alpha = 1f
                        indicator.translationY = restY
                        SyncJobService.scheduleImmediate(this)
                        ageTickHandler.postDelayed({ hidePullIndicator() }, 4000)
                    } else if (pullDragging) {
                        hidePullIndicator()
                    }
                    pullDragging = false
                }
                else -> {}
            }
            false
        }
    }

    private fun hidePullIndicator() {
        pullIndicator?.let {
            it.visibility = View.INVISIBLE
            it.translationY = -dp(48).toFloat()
        }
    }

    private fun refreshList() {
        adapter ?: return
        allConversations = db.listConversations().filter { !it.isLive }
        totalCountLabel?.text = "${allConversations.size} conversation${if (allConversations.size == 1) "" else "s"}"
        applyFilter()
    }

    private fun applyFilter() {
        adapter?.items = QueryDsl.apply(allConversations, searchQuery)
    }

    private fun updateSearchPopup(anchor: EditText) {
        val suggestions = if (searchQuery.isEmpty()) emptyList() else QueryDsl.suggestions(allConversations, searchQuery)
        if (suggestions.isEmpty()) {
            searchPopup?.dismiss()
            return
        }
        val listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setBackgroundColor(Theme.surface)
        listView.adapter = SuggestionRowAdapter(this, suggestions)
        listView.setOnItemClickListener { _, _, position, _ ->
            val s = suggestions[position]
            val from = QueryDsl.replaceFrom(searchQuery)
            val newText = searchQuery.substring(0, from) + s.insertText
            anchor.setText(newText)
            anchor.setSelection(newText.length)
        }

        val popup = searchPopup ?: PopupWindow(this).also {
            it.isOutsideTouchable = true
            it.isFocusable = false
            it.setBackgroundDrawable(Theme.roundedDrawable(Theme.surface, this, strokeColor = Theme.outlineVariant))
            searchPopup = it
        }
        popup.contentView = listView
        val rowHeightPx = dp(40)
        val popupHeight = rowHeightPx * suggestions.size.coerceAtMost(6)
        if (popup.isShowing) {
            popup.update(anchor.width, popupHeight)
        } else {
            popup.width = anchor.width
            popup.height = popupHeight
            popup.showAsDropDown(anchor, 0, 0)
        }
    }

    // Same three-column suggestion row as MainActivity.SuggestionRowAdapter
    // -- duplicated rather than shared, matching how ConversationAdapter's
    // row-anatomy pattern is already repeated per screen in this codebase
    // rather than factored into a shared base.
    private class SuggestionRowAdapter(private val context: Context, private val data: List<QueryDsl.Suggestion>) : android.widget.BaseAdapter() {
        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
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
                meta.ellipsize = android.text.TextUtils.TruncateAt.END
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
}
