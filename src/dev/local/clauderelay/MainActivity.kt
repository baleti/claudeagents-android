package dev.local.clauderelay

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var db: Db
    private lateinit var root: LinearLayout
    private var adapter: ConversationAdapter? = null
    private var offlineBanner: TextView? = null

    private var pullIndicator: ProgressBar? = null

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshList()
            hidePullIndicator()
        }
    }

    // The AGE column reads "now - mtime" at bind time, but a ListView only
    // re-binds a row when it scrolls (or the adapter's data actually
    // changes) -- left sitting on a static screen, the numbers just go
    // stale instead of ticking forward. Re-rendered periodically here
    // purely for that reason: no network call, no DB query, just recompute
    // Fmt.ago() against the current clock for whatever's already loaded.
    private val ageTickHandler = Handler(Looper.getMainLooper())
    private val ageTick = object : Runnable {
        override fun run() {
            adapter?.notifyDataSetChanged()
            ageTickHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.bg)
        setContentView(root)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        actionBar?.hide()

        if (TokenStore.isPaired(this)) {
            showListView()
        } else {
            showPairingView()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(syncReceiver, IntentFilter(SyncLogic.ACTION_SYNC_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(syncReceiver, IntentFilter(SyncLogic.ACTION_SYNC_COMPLETE))
        }
        if (TokenStore.isPaired(this)) {
            refreshList()
            SyncJobService.scheduleImmediate(this)
        }
        ageTickHandler.postDelayed(ageTick, 30_000)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(syncReceiver)
        } catch (e: IllegalArgumentException) {
        }
        ageTickHandler.removeCallbacks(ageTick)
    }

    private fun dp(v: Int): Int = Theme.dp(this, v)

    private fun label(text: String, sizeSp: Float = 13f, color: Int = Theme.onSurfaceVariant): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTextColor(color)
        return t
    }

    private fun showPairingView() {
        root.removeAllViews()
        val pad = dp(24)
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Pair with server"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Theme.onBackground)
        root.addView(title)

        val hint = label("Paste the pairing token printed by claude-relay-daemon.py.")
        hint.setPadding(0, dp(8), 0, dp(20))
        root.addView(hint)

        val tokenField = EditText(this)
        tokenField.hint = "Pairing token"
        tokenField.inputType = InputType.TYPE_CLASS_TEXT
        Theme.styleEditText(tokenField, this)
        root.addView(tokenField, fieldParams())

        val hostField = EditText(this)
        hostField.hint = "Host IP (e.g. your WireGuard peer address)"
        Theme.styleEditText(hostField, this)
        root.addView(hostField, fieldParams())

        val portField = EditText(this)
        portField.hint = "Port"
        portField.setText("8790")
        portField.inputType = InputType.TYPE_CLASS_NUMBER
        Theme.styleEditText(portField, this)
        root.addView(portField, fieldParams())

        val pairButton = Button(this)
        pairButton.text = "Pair"
        pairButton.setTextColor(Theme.onPrimary)
        pairButton.setTypeface(null, Typeface.BOLD)
        pairButton.isAllCaps = false
        Theme.stylePrimaryButton(pairButton, this)
        pairButton.setOnClickListener {
            val token = tokenField.text.toString().trim()
            val host = hostField.text.toString().trim()
            val port = portField.text.toString().trim().toIntOrNull()
            if (token.isEmpty() || host.isEmpty() || port == null) {
                Toast.makeText(this, "Fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TokenStore.save(this, token, host, port)
            SyncJobService.schedulePeriodic(this)
            OutboxJobService.schedulePeriodic(this)
            SyncJobService.scheduleImmediate(this)
            showListView()
        }
        val buttonParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        buttonParams.topMargin = dp(20)
        root.addView(pairButton, buttonParams)
    }

    private fun fieldParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(Theme.spacingDp)
        return p
    }

    private fun confirmRepair() {
        AlertDialog.Builder(this)
            .setTitle("Re-pair with server?")
            .setMessage("Clears the current pairing token. You'll need to paste a new one from claude-relay-daemon.py.")
            .setPositiveButton("Re-pair") { _, _ ->
                TokenStore.clear(this)
                showPairingView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // No refresh/re-pair buttons -- a persistent header row for two rarely-
    // used actions wasted space on every single screen view (asked for
    // explicitly: "let's just have one view of the table"). Refresh is a
    // swipe-down gesture at the top of the list instead (below); re-pair
    // is a long-press on the column header row, since it's a maintenance
    // action you'd reach for maybe once a month, not something that earns
    // permanent on-screen real estate.
    private fun showListView() {
        root.removeAllViews()

        val banner = TextView(this)
        banner.background = Theme.roundedDrawable(Theme.errorContainer, this, strokeColor = Theme.error)
        banner.setTextColor(Theme.error)
        banner.text = "Offline / server unreachable — showing cached conversations"
        banner.setPadding(dp(14), dp(10), dp(14), dp(10))
        banner.visibility = View.GONE
        offlineBanner = banner
        val bannerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bannerParams.leftMargin = dp(16)
        bannerParams.rightMargin = dp(16)
        bannerParams.topMargin = dp(10)
        bannerParams.bottomMargin = dp(4)
        root.addView(banner, bannerParams)

        val header = tableHeaderRow()
        header.setOnLongClickListener { confirmRepair(); true }
        root.addView(header)

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

        refreshList()
    }

    // Hand-rolled pull-to-refresh (the "hold and drag down" browser gesture)
    // -- androidx's SwipeRefreshLayout isn't reachable from this build (no
    // dependency resolver), and a plain ListView has no such gesture built
    // in. Only arms when the list is already scrolled to its very top
    // (first row fully visible, top edge at or past the container's own
    // top) so an ordinary downward scroll through the middle of a long
    // list never misfires this. The indicator slides in and tracks the
    // drag distance live (not just a silent one-shot trigger) so there's
    // real visual confirmation the gesture registered, the way a browser's
    // own pull-to-refresh spinner does.
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
                        manualRefresh()
                        // Belt-and-suspenders: syncReceiver hides it as soon as
                        // the real sync-complete broadcast lands, but hide it
                        // anyway after a bit in case that broadcast is ever
                        // missed (activity paused mid-sync, etc).
                        ageTickHandler.postDelayed({ hidePullIndicator() }, 4000)
                    } else if (pullDragging) {
                        hidePullIndicator()
                    }
                    pullDragging = false
                }
                else -> {}
            }
            false // never consume -- the list still scrolls normally
        }
    }

    private fun hidePullIndicator() {
        pullIndicator?.let {
            it.visibility = View.INVISIBLE
            it.translationY = -dp(48).toFloat()
        }
    }

    private fun manualRefresh() {
        SyncJobService.scheduleImmediate(this)
    }

    private fun headerLabel(text: String, widthDp: Int, alignEnd: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 10f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Theme.muted)
        t.gravity = if (alignEnd) Gravity.END else Gravity.START
        t.layoutParams = LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
        return t
    }

    private fun tableHeaderRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(12), dp(6), dp(12), dp(6))
        row.addView(Space(dp(16)))
        row.addView(headerLabel("ACCT", ConversationColumns.account))
        val title = headerLabel("TITLE", 0)
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.marginStart = dp(8)
        titleParams.marginEnd = dp(8)
        title.layoutParams = titleParams
        row.addView(title)
        row.addView(headerLabel("LN", ConversationColumns.lines, alignEnd = true))
        val ago = headerLabel("AGE", ConversationColumns.ago, alignEnd = true)
        (ago.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        row.addView(ago)
        return row
    }

    private fun Space(widthPx: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        return v
    }

    private fun refreshList() {
        val a = adapter ?: return
        a.items = db.listConversations()
        val ok = getSharedPreferences("clauderelay_prefs", Context.MODE_PRIVATE)
            .getBoolean("last_sync_ok", true)
        offlineBanner?.let {
            if (ok) {
                it.visibility = View.GONE
            } else {
                // Always re-set the error look here, not just visibility --
                // manualRefresh() above repurposes this same view for a
                // transient neutral "Syncing…" state, which must not leak
                // into the genuine offline banner if the sync it kicked off
                // turns out to fail.
                it.background = Theme.roundedDrawable(Theme.errorContainer, this, strokeColor = Theme.error)
                it.setTextColor(Theme.error)
                it.text = "Offline / server unreachable — showing cached conversations"
                it.visibility = View.VISIBLE
            }
        }
    }
}
