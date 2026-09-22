package dev.local.claudeagents

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var db: Db
    private lateinit var root: LinearLayout
    private var adapter: ConversationAdapter? = null
    private var offlineBanner: TextView? = null
    private var searchPopup: PopupWindow? = null
    // Null until showListView() builds it (not shown at all on the
    // pairing screen) -- referenced from onBackPressed below so Back can
    // clear an in-progress search instead of immediately exiting the app.
    private var searchInput: EditText? = null

    // A second ReadAloudController bound to the SAME TtsPlaybackService a
    // ChatActivity may have started -- asked for explicitly 2026-09-20
    // ("media player... should be shown [in] main menu after i started
    // playing something"). TtsPlaybackService already outlives whichever
    // ChatActivity started it (real foreground service, own notification -
    // see its own doc), and ReadAloudController.bind()'s onServiceConnected
    // already detects and reflects an already-running session for ANY
    // fresh controller instance that binds to it, not just the one that
    // started it (see that check's own doc) - this is exactly that
    // mechanism, reused here instead of built new. No word-highlighting or
    // section-scroll wiring, since there's no message list open on this
    // screen to highlight anything in; the transport controls (play/pause/
    // seek/skip/speed) still work normally regardless, since they operate
    // on the controller/service, not on anything specific to this screen.
    private lateinit var mainReadAloud: ReadAloudController
    private lateinit var mainPlayerBar: PlayerControlBar

    private var pullIndicator: ProgressBar? = null

    // Full unfiltered list from the local cache, re-fetched on every
    // refreshList(); the adapter only ever sees applyFilter()'s output so a
    // sync/pull-to-refresh mid-search doesn't clear what's typed. Only
    // conversations with a claude agent actually running on host3 right now
    // -- "have it show only currently active conversations, don't show
    // archived ones" (asked for explicitly 2026-09-07) -- so an idle,
    // finished conversation from last week doesn't clutter a list meant for
    // "what can I message right now".
    private var allConversations: List<ConversationRow> = emptyList()
    private var searchQuery: String = ""
    private var syncSpinner: View? = null
    private var syncLabel: TextView? = null
    private var totalCountLabel: TextView? = null
    // Whether a round is actively running right now -- kept separate from
    // the "Synced N ago" text so the two compose instead of one replacing
    // the other: while syncing, the label still shows how stale the data
    // was *before* this round started (asked for explicitly: "still show
    // 'synced N ago' as well next to when 'Syncing...' happens"), and it
    // keeps ticking forward via the same 30s tick either way.
    private var syncingNow = false

    // A background sync is otherwise completely silent -- the whole point
    // of shortening the periodic interval (see SyncJobService) is that a
    // catch-up round can now happen while you're not even looking, so
    // there needs to be *some* visible sign it's happening rather than the
    // list just quietly updating underneath you (asked for explicitly: "if
    // we are not [in sync] we should know about it and sync in the
    // background, showing some visual cue" -- and, once that existed,
    // "could [it] also tell when the last syncing completed"). The row
    // itself is always visible (unlike most of this app's chrome) since
    // it's exactly the piece of information that answers "can I trust
    // what I'm looking at right now".
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

    // The AGE column reads "now - mtime" at bind time, but a ListView only
    // re-binds a row when it scrolls (or the adapter's data actually
    // changes) -- left sitting on a static screen, the numbers just go
    // stale instead of ticking forward. Re-rendered periodically here
    // purely for that reason: no network call, no DB query, just recompute
    // Fmt.ago() against the current clock for whatever's already loaded.
    // The sync-status label is the same kind of "age" text, so it rides
    // the same tick.
    private val ageTickHandler = Handler(Looper.getMainLooper())
    private val ageTick = object : Runnable {
        override fun run() {
            adapter?.notifyDataSetChanged()
            updateSyncStatusLabel()
            // Reported live 2026-09-12: a conversation that ended on
            // host3 (its live pane closing) didn't show up as no-longer-
            // live until this screen was left and reopened -- onResume's
            // own scheduleImmediate() only ever fired once, at the
            // moment this screen was first shown, so nothing re-checked
            // afterward while it just sat here open. Riding the same
            // already-running 30s tick this screen uses for the AGE
            // column keeps it actually catching up while visible, not
            // just on first open. scheduleImmediate() is safe to call
            // repeatedly -- SyncJobService.IMMEDIATE_JOB_ID is a fixed
            // job id, so this replaces any still-pending one rather than
            // stacking duplicates.
            if (TokenStore.isPaired(this@MainActivity)) {
                SyncJobService.scheduleImmediate(this@MainActivity)
                // Same reasoning as onResume's direct call -- JobScheduler
                // dispatch is real (if usually short) latency this screen
                // shouldn't have to wait through every 30s while it's
                // already sitting open in the foreground.
                Thread { SyncLogic.performSync(this@MainActivity) }.apply { isDaemon = true; name = "AgeTickSync"; start() }
            }
            ageTickHandler.postDelayed(this, 30_000)
        }
    }

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

        if (TokenStore.isPaired(this)) {
            showListView()
        } else {
            showPairingView()
        }

        // Read-aloud's playback foreground service posts a persistent
        // media-style notification (play/pause/stop) the same way any
        // other media app does -- on API 33+ that needs this runtime grant
        // or the notification (and its transport controls) just silently
        // never appears, even though audio still plays. Asked up front
        // here rather than the first time read-aloud is actually used, so
        // there's no surprise permission prompt interrupting that flow.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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
        if (TokenStore.isPaired(this)) {
            refreshList()
            // Re-asserted every resume (not just at pairing time) so an
            // already-paired install picks up a lowered interval the next
            // time this build runs, without needing to re-pair.
            SyncJobService.schedulePeriodic(this)
            SyncJobService.scheduleImmediate(this)
            // scheduleImmediate() above still goes through JobScheduler's
            // own dispatch, which is NOT actually instant -- even a
            // minimumLatency(0)/overrideDeadline(0) job is subject to
            // real (if usually short) scheduling overhead, and can be
            // pushed out further right after a cold app launch before the
            // process is fully "woken up" for job eligibility. Reported
            // live 2026-09-13 ("opening the app only to find out it is
            // still unsynced") -- SyncLogic.performSync() was already
            // written to be callable directly for exactly this case (see
            // its own doc: "MainActivity's manual refresh (immediate,
            // foreground)") but nothing here was actually doing that; it
            // only ever went through the JobScheduler path. Calling it
            // directly on a plain background thread skips that dispatch
            // latency entirely -- as fast as the real network round-trip
            // allows, which on this WireGuard link is well under a
            // second. Safe to run alongside the JobScheduler-triggered
            // call: performSync()'s own `syncing` guard coalesces if both
            // land close together.
            Thread { SyncLogic.performSync(this) }.apply { isDaemon = true; name = "MainSyncNow"; start() }
        }
        // Refreshed synchronously here, not just left to the recurring
        // tick below -- reported stuck at a stale "synced Ns ago" that
        // never advanced: onResume previously only *scheduled* the first
        // tick 30s out, so a background stretch long enough for Android to
        // freeze the process (common once it's no longer the foreground
        // app) left the label showing whatever it said at the moment it
        // froze until that delayed tick finally got to run. Updating
        // immediately here means the instant this screen is actually
        // looked at, the label is correct regardless of how long the tick
        // loop was suspended.
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

    override fun onDestroy() {
        super.onDestroy()
        // Same as ChatActivity's own onDestroy -- only detaches this
        // Activity's ServiceConnection, doesn't stop playback (a real
        // foreground service with its own notification keeps it going
        // regardless). ::mainReadAloud.isInitialized guards the pairing
        // screen, where showListView() (and therefore this) never ran.
        if (::mainReadAloud.isInitialized) mainReadAloud.unbind()
    }

    // Asked for explicitly 2026-09-10: Back with text still in the search
    // box should clear the search first, not immediately exit the app (the
    // default behavior on this screen, being the launcher activity).
    // setText("") alone is enough -- the field's own TextWatcher already
    // re-derives searchQuery/applyFilter/updateSearchPopup from it.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val input = searchInput
        if (input != null && input.text.isNotEmpty()) {
            input.setText("")
            return
        }
        super.onBackPressed()
    }

    private fun showOverflowMenu(anchor: View) {
        Theme.showMenu(this, anchor, listOf("Archive", "Read aloud settings", "Settings")) { picked ->
            when (picked) {
                "Archive" -> startActivity(Intent(this, ArchiveActivity::class.java))
                "Read aloud settings" -> startActivity(Intent(this, TtsSettingsActivity::class.java))
                "Settings" -> startActivity(Intent(this, AppSettingsActivity::class.java))
            }
        }
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

        val hint = label("Paste the pairing token printed by claude-agents-daemon.py.")
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
            .setMessage("Clears the current pairing token. You'll need to paste a new one from claude-agents-daemon.py.")
            .setPositiveButton("Re-pair") { _, _ ->
                TokenStore.clear(this)
                showPairingView()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewSessionDialog() {
        val dp = { v: Int -> Theme.dp(this, v) }
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        container.setPadding(pad, pad, pad, pad)

        val acctLabel = label("Account", 12f, Theme.muted)
        container.addView(acctLabel)

        val acctRow = LinearLayout(this)
        acctRow.orientation = LinearLayout.HORIZONTAL
        val acctRowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        acctRowParams.topMargin = dp(6)
        acctRowParams.bottomMargin = dp(16)
        container.addView(acctRow, acctRowParams)

        // Same dir_key spelling as everywhere else in this app (see
        // ConversationColumns.accountNumber) -- the daemon's ACCOUNT_DIRS.
        val accountKeys = listOf("claude", "claude2", "claude3")
        var selected = 0
        val acctButtons = mutableListOf<Button>()
        fun restyle() {
            acctButtons.forEachIndexed { i, b ->
                if (i == selected) Theme.stylePrimaryButton(b, this) else Theme.styleGhostButton(b, this)
            }
        }
        accountKeys.forEachIndexed { i, _ ->
            val b = Button(this)
            b.text = (i + 1).toString()
            b.isAllCaps = false
            b.setTypeface(null, Typeface.BOLD)
            b.setTextColor(Theme.onPrimary)
            val bp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) bp.marginStart = dp(8)
            b.setOnClickListener { selected = i; restyle() }
            acctButtons.add(b)
            acctRow.addView(b, bp)
        }
        restyle()

        val input = EditText(this)
        input.hint = "First message…"
        input.minLines = 2
        input.maxLines = 5
        Theme.styleEditText(input, this)
        container.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(this)
            .setTitle("New conversation")
            .setView(container)
            .setPositiveButton("Start", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Type a first message", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                spawnSession(accountKeys[selected], text)
            }
        }
        dialog.show()
    }

    // Same primitive as the daemon's other launch points: tmux new-session
    // + claude --session-id under the chosen account's CLAUDE_CONFIG_DIR
    // (claude-agents-daemon.py's spawn_session / POST /api/v1/spawn).
    // Network call, so off the main thread; navigates straight into the
    // new conversation on success the same way tapping an existing row
    // does.
    private fun spawnSession(account: String, text: String) {
        Toast.makeText(this, "Starting session…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val client = RelayClient(this)
                val resp = client.spawn(account, text)
                val sessionId = resp.optString("session_id", "")
                runOnUiThread {
                    if (sessionId.isEmpty()) {
                        Toast.makeText(this, "Spawn failed: no session id returned", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    SyncJobService.scheduleImmediate(this)
                    val intent = Intent(this, ChatActivity::class.java)
                    intent.putExtra("session_id", sessionId)
                    intent.putExtra("title", text.take(60))
                    startActivity(intent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Spawn failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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

        val searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        val searchRowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        searchRowParams.leftMargin = dp(12)
        searchRowParams.rightMargin = dp(12)
        searchRowParams.topMargin = dp(8)
        searchRowParams.bottomMargin = dp(4)
        root.addView(searchRow, searchRowParams)

        val searchInput = EditText(this)
        this.searchInput = searchInput
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

        // New-session entry point -- same primitive host3's other per-
        // account launch points use (tmux new-session + claude under that
        // account's CLAUDE_CONFIG_DIR), via the daemon's existing
        // spawn_session()/POST /api/v1/spawn (asked for explicitly: "add
        // options to spawn new claude1/claude2/claude3 sessions via the
        // same mechanism as in .shortcuts scripts" -- couldn't read those
        // scripts directly, phone unreachable this session, so this reuses
        // the daemon's own equivalent already-built primitive instead).
        val newButton = Button(this)
        newButton.text = "+"
        newButton.isAllCaps = false
        newButton.textSize = 16f
        newButton.setTextColor(Theme.onPrimary)
        newButton.setTypeface(null, Typeface.BOLD)
        Theme.stylePrimaryButton(newButton, this)
        // stylePrimaryButton's padding is sized for a normal-width text
        // button ("Send" etc) -- a bare "+" icon-only button reads too big
        // next to the search field at that size. Buttons also carry a
        // platform-default minWidth/minHeight (~48dp) that padding alone
        // can't shrink below, so both are zeroed here.
        newButton.minWidth = 0
        newButton.minimumWidth = 0
        newButton.minHeight = 0
        newButton.minimumHeight = 0
        newButton.setPadding(dp(14), dp(6), dp(14), dp(6))
        val newButtonParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        newButtonParams.marginStart = dp(8)
        newButtonParams.gravity = Gravity.CENTER_VERTICAL
        newButton.setOnClickListener { showNewSessionDialog() }
        searchRow.addView(newButton, newButtonParams)

        // No visible ActionBar anywhere in this app (every screen hides it
        // and builds its own chrome by hand), so "three dots menu" is a
        // plain PopupMenu anchored to a hand-built button rather than a
        // real options-menu overflow -- same idiom this app already uses
        // for other popups (commandPopup, searchPopup).
        val overflowButton = TextView(this)
        overflowButton.text = "⋮"
        overflowButton.textSize = 20f
        overflowButton.gravity = Gravity.CENTER
        overflowButton.setTextColor(Theme.onBackground)
        // The glyph itself is narrow, and the old padding (10/4/4/4) left a
        // genuinely small tap target next to it -- reported live 2026-09-09:
        // "very hard to select". Symmetric padding plus an explicit min
        // size gives it a real touch target (Android's own guidance is
        // 48dp minimum) regardless of how little space the "⋮" text needs.
        overflowButton.setPadding(dp(14), dp(10), dp(14), dp(10))
        overflowButton.minWidth = dp(44)
        overflowButton.minHeight = dp(44)
        overflowButton.background = Theme.rippleOn(Theme.roundedDrawable(Color.TRANSPARENT, this, radiusDp = 20))
        overflowButton.setOnClickListener { showOverflowMenu(overflowButton) }
        val overflowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        overflowParams.gravity = Gravity.CENTER_VERTICAL
        searchRow.addView(overflowButton, overflowParams)

        val syncRow = LinearLayout(this)
        syncRow.orientation = LinearLayout.HORIZONTAL
        syncRow.gravity = Gravity.CENTER_VERTICAL
        val spinner = ProgressBar(this)
        spinner.isIndeterminate = true
        spinner.visibility = View.GONE
        syncRow.addView(spinner, LinearLayout.LayoutParams(dp(14), dp(14)))
        syncSpinner = spinner
        val label = TextView(this)
        label.textSize = 11f
        label.setTextColor(Theme.muted)
        val syncLabelParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        syncLabelParams.marginStart = dp(6)
        syncRow.addView(label, syncLabelParams)
        syncLabel = label
        updateSyncStatusLabel()
        val syncRowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        syncRowParams.leftMargin = dp(12)
        syncRowParams.topMargin = dp(2)
        syncRowParams.bottomMargin = dp(2)
        root.addView(syncRow, syncRowParams)

        // Asked for explicitly 2026-09-11: a small total-conversation-count
        // line, same muted/11sp styling as the sync label above it. Counts
        // allConversations (unfiltered) rather than whatever the search box
        // currently shows -- "total" means total, not "how many matched".
        val totalLabel = TextView(this)
        totalLabel.textSize = 11f
        totalLabel.setTextColor(Theme.muted)
        val totalLabelParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        totalLabelParams.leftMargin = dp(12)
        totalLabelParams.bottomMargin = dp(4)
        root.addView(totalLabel, totalLabelParams)
        totalCountLabel = totalLabel

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

        mainReadAloud = ReadAloudController(
            context = this,
            onStateChanged = { active -> if (active) mainPlayerBar.show() else mainPlayerBar.hide() },
            onPlayingChanged = { playing -> mainPlayerBar.setPlaying(playing) },
        )
        mainPlayerBar = PlayerControlBar(
            context = this,
            onPreviousSection = { mainReadAloud.skipToPreviousSection() },
            // 10s, not 15 -- same fix as ChatActivity's own copy
            // (2026-09-20), just never carried over to this screen's
            // separate player bar instance.
            onRewind = { mainReadAloud.seekRelative(-10_000) },
            onPlayPause = {
                if (mainPlayerBar.isPlayingIcon()) mainReadAloud.pause() else mainReadAloud.resume()
            },
            onForward = { mainReadAloud.seekRelative(10_000) },
            onNextSection = { mainReadAloud.skipToNextSection() },
            onSpeedClick = { anchor ->
                SpeedPicker.show(this, anchor, mainReadAloud.getSpeed()) { speed -> mainReadAloud.setSpeed(speed) }
            },
            onStop = { mainReadAloud.stop() },
            // Never wired at all before -- this screen has no message list
            // of its own to scroll within (mainReadAloud's own doc), so
            // "locate" here means "open the conversation that's actually
            // playing" instead. currentConversationId() reflects whatever
            // conversation's ChatActivity started the live session, since
            // this controller only ever binds to an already-running one
            // (see ReadAloudController.currentConversationId()'s own doc).
            onLocate = {
                val id = mainReadAloud.currentConversationId() ?: return@PlayerControlBar
                val title = a.items.firstOrNull { it.id == id }?.title ?: id
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("session_id", id)
                intent.putExtra("title", title)
                startActivity(intent)
            },
            getPosition = { mainReadAloud.getPositionMs() },
            getDuration = { mainReadAloud.getDurationMs() },
            onSeek = { fraction -> mainReadAloud.seekToFraction(fraction) },
        )
        root.addView(mainPlayerBar.view)
        mainReadAloud.bind()

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
        row.addView(headerLabel("ACCT", ConversationColumns.account))
        val title = headerLabel("TITLE", 0)
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.marginStart = dp(8)
        titleParams.marginEnd = dp(8)
        title.layoutParams = titleParams
        row.addView(title)
        row.addView(headerLabel("TKNS", ConversationColumns.tokens, alignEnd = true))
        val ago = headerLabel("AGE", ConversationColumns.ago, alignEnd = true)
        (ago.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        row.addView(ago)
        return row
    }

    private fun refreshList() {
        adapter ?: return
        allConversations = db.listConversations().filter { it.isLive }
        totalCountLabel?.text = "${allConversations.size} conversation${if (allConversations.size == 1) "" else "s"}"
        applyFilter()
        val ok = getSharedPreferences("claudeagents_prefs", Context.MODE_PRIVATE)
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
            // afterTextChanged() (triggered by setText above) re-derives
            // searchQuery/applyFilter/updateSearchPopup from the new text.
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
}
