package dev.local.claudeagents

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean

class ChatActivity : Activity() {
    private lateinit var db: Db
    private lateinit var sessionId: String
    private lateinit var listView: ListView
    private lateinit var adapter: MessageAdapter
    private val polling = AtomicBoolean(false)
    private var pollThread: Thread? = null

    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread { loadCached() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        sessionId = intent.getStringExtra("session_id") ?: run { finish(); return }
        val convTitle = intent.getStringExtra("title") ?: sessionId

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.bg)
        setContentView(root)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        actionBar?.hide()

        val dp = { v: Int -> Theme.dp(this, v) }

        val titleBar = LinearLayout(this)
        titleBar.orientation = LinearLayout.VERTICAL
        titleBar.setPadding(dp(16), dp(14), dp(16), dp(10))
        val title = TextView(this)
        title.text = convTitle
        title.textSize = 15f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Theme.onBackground)
        title.maxLines = 2
        titleBar.addView(title)
        val divider = View(this)
        divider.setBackgroundColor(Theme.outlineVariant)
        titleBar.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(10) })
        root.addView(titleBar)

        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setBackgroundColor(Theme.bg)
        adapter = MessageAdapter(this)
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.setPadding(dp(10), dp(8), dp(10), dp(10))

        val input = EditText(this)
        input.hint = "Message…"
        Theme.styleEditText(input, this)
        val inputParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        inputParams.marginEnd = dp(8)
        inputRow.addView(input, inputParams)

        val send = Button(this)
        send.text = "Send"
        send.isAllCaps = false
        send.setTextColor(Theme.onPrimary)
        send.setTypeface(null, Typeface.BOLD)
        Theme.stylePrimaryButton(send, this)
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            db.insertOutbox(sessionId, text)
            loadCached()
            OutboxJobService.scheduleImmediate(this)
        }
        inputRow.addView(send)

        root.addView(inputRow)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(changedReceiver, IntentFilter(OutboxLogic.ACTION_OUTBOX_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(changedReceiver, IntentFilter(OutboxLogic.ACTION_OUTBOX_CHANGED))
        }
        loadCached()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        polling.set(false)
        try {
            unregisterReceiver(changedReceiver)
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun loadCached() {
        val messages = db.listMessages(sessionId).map { ChatDisplayRow(it.role, it.text, null) }
        // Keep showing an outbox row until the daemon confirms it actually
        // reached a live tmux pane -- "handed_off/queued" (no live session
        // right now) must stay visible, not disappear, since that's exactly
        // the case this app exists for: the reply is accepted and will be
        // delivered automatically once the session goes live, but nothing
        // will ever land back in the synced transcript for it until then.
        val pendingOutbox = db.listOutbox(sessionId)
            .filter { it.state == "pending" || it.serverState != "delivered" }
            .map { row ->
                val status = when {
                    row.state == "pending" -> "sending…"
                    row.serverState == "queued" -> "queued on server — will send once the session is live"
                    else -> "sending…"
                }
                ChatDisplayRow("user", row.text, status)
            }
        adapter.items = messages + pendingOutbox
        listView.post { listView.setSelection(adapter.count - 1) }
    }

    // A "queued" outbox row was accepted by the daemon but not yet delivered
    // into a live tmux pane; there is no other signal (no transcript line
    // will ever exist for it if it was never delivered) that tells the app
    // it finally went out, so poll the daemon's own queue file and drop the
    // local row once its text is no longer in there -- delivered.
    private fun reconcileQueuedOutbox(client: RelayClient): Boolean {
        val queuedLocally = db.listOutbox(sessionId).filter { it.serverState == "queued" }
        if (queuedLocally.isEmpty()) return false
        val remoteTexts = try {
            val resp = client.getQueue(sessionId)
            val arr = resp.optJSONArray("queued") ?: org.json.JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("text") }.toSet()
        } catch (e: Exception) {
            return false
        }
        var changed = false
        for (row in queuedLocally) {
            if (row.text !in remoteTexts) {
                db.markOutboxDelivered(row.id)
                changed = true
            }
        }
        return changed
    }

    private fun startPolling() {
        if (polling.get()) return
        polling.set(true)
        pollThread = Thread {
            val client = RelayClient(this)
            while (polling.get()) {
                try {
                    val since = db.getMaxLine(sessionId)
                    val resp = client.stream(sessionId, since, 25)
                    val msgs = RelayClient.parseMessages(resp)
                    var changed = false
                    if (msgs.isNotEmpty()) {
                        db.insertMessages(sessionId, msgs)
                        changed = true
                    }
                    if (reconcileQueuedOutbox(client)) changed = true
                    if (changed) runOnUiThread { loadCached() }
                } catch (e: Exception) {
                    try {
                        Thread.sleep(3000)
                    } catch (ie: InterruptedException) {
                    }
                }
            }
        }
        pollThread?.start()
    }
}
