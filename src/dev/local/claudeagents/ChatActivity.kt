package dev.local.claudeagents

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.View
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ChatActivity : Activity() {
    private lateinit var db: Db
    private lateinit var sessionId: String
    // True when this screen was opened from the Archive view (the
    // conversation had no live tmux pane at open time) -- see
    // ArchiveActivity. Routes every send from this screen through
    // .../resume instead of .../send (see sendText/OutboxLogic), which
    // relaunches the conversation via `claude --resume` if it's still not
    // live by the time the outbox drains. Stays true for the rest of this
    // screen's lifetime even if the conversation goes live in the
    // meantime -- the daemon's /resume handles that by delivering straight
    // into the now-live pane instead of resuming a second time, so there's
    // no need to track the transition here.
    private var archivedOrigin: Boolean = false
    // Left-edge swipe-out panel listing live conversations -- asked for
    // explicitly 2026-09-10: "so i can switch between them without having
    // to go back to main screen". Switching never calls finish() on the
    // conversation being left (see the drawer list's own click handler
    // below), so each switch just pushes a new ChatActivity instance onto
    // the normal Android back stack -- system Back therefore already
    // walks backward through whichever conversations were actually
    // visited, in order, before finally reaching MainActivity, with no
    // separate history bookkeeping needed here.
    private lateinit var drawer: SwipeDrawer
    private lateinit var drawerListView: ListView
    private lateinit var drawerAdapter: ConversationAdapter
    private lateinit var listView: ListView
    private lateinit var adapter: MessageAdapter
    private lateinit var input: EditText
    private val polling = AtomicBoolean(false)
    private var pollThread: Thread? = null
    private var commandPopup: PopupWindow? = null
    private var thinkingRow: View? = null
    private var contextBannerView: View? = null
    private var contextBannerLabel: TextView? = null
    private var pendingAttachmentCaption: String = ""
    // Reuses the exact same TTS server + WebSocketClient/TtsPlaybackService
    // pipeline news-digest built (asked for explicitly: "reuse the digest's
    // tts server", then "generalize it" -- the server's /tts/stream route
    // was already source-agnostic, so this app's copy of the client-side
    // pieces (WebSocketClient, TtsPlaybackService, PlayerControlBar,
    // SpeedPicker) needed no server changes, just its own thin
    // ReadAloudController wiring them together without the word-highlight
    // caption view news-digest's article reader uses (no equivalent here --
    // chat bubbles, not one scrollable article).
    private lateinit var readAloud: ReadAloudController
    private lateinit var playerBar: PlayerControlBar
    // Parallel to the section list passed to readAloud.start() -- see
    // readAloudFrom().
    private var readAloudSectionRowIds: List<String> = emptyList()

    companion object {
        private const val PICK_ATTACHMENT_REQUEST = 4201
        // Mirrors the daemon's own MAX_UPLOAD_BYTES -- reject oversized
        // files client-side too, before spending time/battery reading and
        // base64-encoding something the server would just refuse anyway.
        private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
        private val ATTACHMENT_REF_RE = Regex("""^\[(image|file) attached: """)
        // Mirrors the daemon's own CONTEXT_WARN_PCT (claude-agents-daemon.py).
        private const val CONTEXT_WARN_PCT = 80
    }

    // Claude Code's own slash commands -- typed as the whole line at the
    // very start of a message, same convention the desktop CLI uses.
    // There's no live endpoint that reports which of these are actually
    // registered for a given session, so this is a fixed common set rather
    // than something pulled from the server.
    private val slashCommands = listOf(
        "/clear", "/compact", "/exit", "/help", "/resume", "/status", "/cost",
        "/memory", "/agents", "/mcp", "/hooks", "/permissions", "/review",
        "/pr-comments", "/init", "/add-dir", "/model", "/login", "/logout",
        "/doctor", "/bug", "/export", "/config", "/vim", "/terminal-setup"
    )

    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread { loadCached() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.getInstance(this)
        sessionId = intent.getStringExtra("session_id") ?: run { finish(); return }
        archivedOrigin = intent.getBooleanExtra("archived_origin", false)
        val convTitle = intent.getStringExtra("title") ?: sessionId

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Theme.bg)
        // Not attached via setContentView yet -- root becomes the
        // drawer's main content at the very end of onCreate, once it's
        // fully built (see SwipeDrawer.setContent below).
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
        // Asked for explicitly 2026-09-10 -- same clipboard mechanism and
        // "Copied" toast a message bubble's own long-press/menu copy uses.
        title.setOnLongClickListener { copyMessage(title.text.toString()); true }
        titleBar.addView(title)
        val divider = View(this)
        divider.setBackgroundColor(Theme.outlineVariant)
        titleBar.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also { it.topMargin = dp(10) })
        root.addView(titleBar)

        // Hidden until the daemon reports this conversation's context usage
        // at or above CONTEXT_WARN_PCT (see claude-agents-daemon.py) --
        // the only warning Claude Code itself gives is the hard "Prompt is
        // too long" *after* the window is already full (reported live
        // 2026-09-07/08: no proactive signal at all). Informational only --
        // no action button ("we can simply run /compact ourselves, we just
        // need that warning to appear when we are near", asked for
        // explicitly 2026-09-08 -- an earlier version offered a one-tap
        // Compact action here, removed per that feedback).
        val contextBanner = LinearLayout(this)
        contextBanner.orientation = LinearLayout.HORIZONTAL
        contextBanner.gravity = Gravity.CENTER_VERTICAL
        contextBanner.visibility = View.GONE
        contextBanner.setBackgroundColor(Theme.errorContainer)
        contextBanner.setPadding(dp(14), dp(8), dp(14), dp(8))
        val contextLabel = TextView(this)
        contextLabel.textSize = 12f
        contextLabel.setTextColor(Theme.error)
        contextBanner.addView(contextLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(contextBanner)
        contextBannerView = contextBanner
        contextBannerLabel = contextLabel

        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setBackgroundColor(Theme.bg)
        adapter = MessageAdapter(this)
        listView.adapter = adapter
        adapter.listView = listView
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // "just silence" while Claude works was the actual complaint --
        // Claude Code's own sessions/<pid>.json already tracks busy/idle
        // (claude-agents-daemon.py now threads it through /stream's
        // response), so this doesn't need to guess from transcript
        // activity. Shown between the message list and the input row,
        // visible only while genuinely busy.
        val thinking = LinearLayout(this)
        thinking.orientation = LinearLayout.HORIZONTAL
        thinking.gravity = Gravity.CENTER_VERTICAL
        thinking.visibility = View.GONE
        thinking.setPadding(dp(14), dp(4), dp(14), dp(4))
        val thinkingSpinner = ProgressBar(this)
        thinkingSpinner.isIndeterminate = true
        thinking.addView(thinkingSpinner, LinearLayout.LayoutParams(dp(14), dp(14)))
        val thinkingLabel = TextView(this)
        thinkingLabel.text = "Claude is working…"
        thinkingLabel.textSize = 11f
        thinkingLabel.setTypeface(null, Typeface.ITALIC)
        thinkingLabel.setTextColor(Theme.muted)
        val thinkingLabelParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        thinkingLabelParams.marginStart = dp(6)
        thinking.addView(thinkingLabel, thinkingLabelParams)
        root.addView(thinking)
        thinkingRow = thinking

        readAloud = ReadAloudController(
            context = this,
            onStateChanged = { active ->
                if (active) {
                    playerBar.show()
                } else {
                    playerBar.hide()
                    adapter.setHighlight(null, null)
                }
            },
            onPlayingChanged = { playing -> playerBar.setPlaying(playing) },
            // One section == one message here (see readAloudFrom) --
            // moving into a new section means a different message bubble
            // is now the one being read, so the previous bubble's
            // highlight needs clearing (the new section's own word-range
            // highlight arrives moments later via onWordHighlight below).
            // Also keeps the currently-reading bubble scrolled into view.
            onSectionChanged = { sectionIndex ->
                val rowId = readAloudSectionRowIds.getOrNull(sectionIndex)
                adapter.setHighlight(rowId, null)
                val pos = adapter.items.indexOfFirst { it.id == rowId }
                if (pos >= 0) listView.smoothScrollToPosition(pos)
            },
            onWordHighlight = { sectionIndex, charStart, charEnd ->
                val rowId = readAloudSectionRowIds.getOrNull(sectionIndex)
                if (charEnd > charStart) adapter.setHighlight(rowId, charStart..(charEnd - 1))
            },
        )
        playerBar = PlayerControlBar(
            context = this,
            onPreviousSection = { readAloud.skipToPreviousSection() },
            onRewind = { readAloud.seekRelative(-15_000) },
            onPlayPause = {
                // playerBar's own icon already reflects real playing state
                // via onPlayingChanged above, so it's the source of truth
                // here rather than tracking a second local flag.
                if (playerBar.isPlayingIcon()) readAloud.pause() else readAloud.resume()
            },
            onForward = { readAloud.seekRelative(15_000) },
            onNextSection = { readAloud.skipToNextSection() },
            onSpeedClick = { anchor ->
                SpeedPicker.show(this, anchor, readAloud.getSpeed()) { speed -> readAloud.setSpeed(speed) }
            },
            // Re-scrolls to whichever message is currently being read --
            // asked for explicitly 2026-09-10. onSectionChanged above
            // already does this automatically as reading progresses, but
            // scrolling away to reread something earlier needs a manual
            // way back to the live position too.
            onLocate = {
                val rowId = readAloudSectionRowIds.getOrNull(readAloud.getCurrentSectionIndex())
                val pos = adapter.items.indexOfFirst { it.id == rowId }
                if (pos >= 0) listView.smoothScrollToPosition(pos)
            },
        )
        root.addView(playerBar.view)
        readAloud.bind()

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.setPadding(dp(10), dp(8), dp(10), dp(10))

        input = EditText(this)
        input.hint = "Message…"
        Theme.styleEditText(input, this)
        // A long paste (reported: ~20-30 lines) grew this EditText without
        // bound, which the keyboard then ate into from below -- windowSoftInputMode
        // "adjustResize" above stops the *window* from being covered, but the
        // field still needs its own cap or it just keeps growing to fill
        // whatever space adjustResize leaves it, right back into the same
        // problem for a long enough message. maxLines alone is enough --
        // EditText already scrolls internally by touch once content
        // exceeds it, no extra wiring needed. A custom movementMethod
        // (ScrollingMovementMethod, tried first) was a real regression:
        // it replaces EditText's own selection-aware movement method, and
        // took spacebar cursor-drag, double-tap word select, and long-
        // press select with it -- reported live 2026-09-08.
        input.maxLines = 8
        input.isVerticalScrollBarEnabled = true
        // Restores whatever was typed but not sent last time this
        // conversation's input was touched -- including after the app
        // process was killed outright, not just switching away and back
        // (asked for explicitly 2026-09-09, after an adb force-stop during
        // testing dropped an in-progress draft: "text ij i put field to
        // persist even across app getting closed"). See DraftStore.
        val savedDraft = DraftStore.get(this, sessionId)
        if (savedDraft.isNotEmpty()) {
            input.setText(savedDraft)
            input.setSelection(input.text.length)
        }
        val inputParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        inputParams.marginEnd = dp(8)
        inputRow.addView(input, inputParams)

        // Attach lives beside input while it's short (room to spare) and
        // moves below Send, in a narrow column, once the message is long
        // enough that the extra width actually matters. Driven by
        // character count, not input.lineCount -- reported live
        // 2026-09-08: line count flickered the button back and forth right
        // around the threshold, since word-wrap's exact break point can
        // shift by a character or two independent of anything the user
        // deliberately typed/deleted. Character count is monotonic with
        // typing and needs no layout pass to read. Two thresholds (not
        // one) add a little hysteresis on top of that -- crossing 80 to
        // stack and having to drop back under 60 to unstack means sitting
        // near either boundary doesn't toggle the layout on every
        // keystroke either.
        val actionColumn = LinearLayout(this)
        actionColumn.orientation = LinearLayout.VERTICAL

        val send = Button(this)
        send.text = "Send"
        send.isAllCaps = false
        send.setTextColor(Theme.onPrimary)
        send.setTypeface(null, Typeface.BOLD)
        Theme.stylePrimaryButton(send, this)
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (sendText(text)) {
                input.setText("")
                DraftStore.set(this, sessionId, "")
            }
        }
        actionColumn.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        inputRow.addView(actionColumn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val attach = Button(this)
        attach.text = "📎"
        attach.isAllCaps = false
        Theme.styleGhostButton(attach, this)
        attach.minWidth = 0
        attach.minimumWidth = 0
        attach.setPadding(dp(12), dp(6), dp(12), dp(6))
        attach.setOnClickListener {
            pendingAttachmentCaption = input.text.toString().trim()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivityForResult(intent, PICK_ATTACHMENT_REQUEST)
            } catch (e: Exception) {
                Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
            }
        }

        // Starts side-by-side (matches input's initial empty state) --
        // placed directly here rather than through updateAttachLayout
        // below, since that function only *moves* the button on a state
        // change and there's no previous state yet to change from.
        // updateAttachLayout handles every placement after this one, as
        // length crosses either threshold while typing/deleting.
        var attachStacked = false
        run {
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.marginEnd = dp(8)
            inputRow.addView(attach, 0, params)
        }
        fun updateAttachLayout(charLength: Int) {
            val shouldStack = if (attachStacked) charLength >= 60 else charLength >= 80
            if (shouldStack == attachStacked) return
            attachStacked = shouldStack
            (attach.parent as? LinearLayout)?.removeView(attach)
            if (shouldStack) {
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.topMargin = dp(6)
                actionColumn.addView(attach, params)
            } else {
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.marginEnd = dp(8)
                inputRow.addView(attach, 0, params)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCommandPopup(input)
                updateAttachLayout(s?.length ?: 0)
                // Per-keystroke, not just onPause -- the incident that
                // prompted this (see DraftStore) was an abrupt process
                // kill, which skips onPause entirely.
                DraftStore.set(this@ChatActivity, sessionId, s?.toString() ?: "")
            }
        })

        root.addView(inputRow)

        // Left-edge swipe drawer: live conversations, for switching
        // without leaving this screen (see the `drawer` field's own doc).
        drawer = SwipeDrawer(this)
        val drawerRoot = LinearLayout(this)
        drawerRoot.orientation = LinearLayout.VERTICAL
        drawerRoot.setBackgroundColor(Theme.surface)
        val drawerTitle = TextView(this)
        drawerTitle.text = "Conversations"
        drawerTitle.textSize = 15f
        drawerTitle.setTypeface(null, Typeface.BOLD)
        drawerTitle.setTextColor(Theme.onBackground)
        drawerTitle.setPadding(dp(16), dp(16), dp(16), dp(10))
        drawerRoot.addView(drawerTitle)
        val drawerDivider = View(this)
        drawerDivider.setBackgroundColor(Theme.outlineVariant)
        drawerRoot.addView(drawerDivider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        drawerListView = ListView(this)
        drawerListView.divider = null
        drawerListView.dividerHeight = 0
        drawerListView.setBackgroundColor(Theme.surface)
        drawerAdapter = ConversationAdapter(this, showTokens = false)
        drawerListView.adapter = drawerAdapter
        drawerListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val c = drawerAdapter.items[position]
            drawer.close()
            if (c.id == sessionId) return@OnItemClickListener
            // No finish() here -- switching this way pushes a new instance
            // onto the back stack instead of replacing this one, which is
            // exactly what gives Back its "previously opened conversations"
            // history (see the `drawer` field's own doc).
            val switchIntent = Intent(this, ChatActivity::class.java)
            switchIntent.putExtra("session_id", c.id)
            switchIntent.putExtra("title", c.title)
            startActivity(switchIntent)
        }
        drawerRoot.addView(drawerListView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        drawer.setDrawerContent(drawerRoot)
        // Refreshed on every open, not just once at screen-build time --
        // the whole point is switching to a conversation that may have
        // only just gone live, or dropping one that closed, since this
        // screen was first opened.
        drawer.onOpen = { drawerAdapter.items = db.listConversations().filter { it.isLive } }
        drawer.setContent(root)
        setContentView(drawer.root)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::drawer.isInitialized && drawer.isOpen()) {
            drawer.close()
            return
        }
        super.onBackPressed()
    }

    // insertOutbox throws instead of silently swallowing a failed write
    // (see Db.kt) -- this is the one place in the app that must never
    // lose a message with no trace, so the caller only clears its own
    // input on a confirmed commit. Returns whether the write succeeded.
    private fun sendText(text: String): Boolean {
        try {
            db.insertOutbox(sessionId, text, viaResume = archivedOrigin)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't save message locally, try again: ${e.message}", Toast.LENGTH_LONG).show()
            return false
        }
        loadCached()
        OutboxJobService.scheduleImmediate(this)
        return true
    }

    private fun updateContextBanner(pct: Int?) {
        if (pct != null && pct >= CONTEXT_WARN_PCT) {
            contextBannerLabel?.text = "Context $pct% full — replies may start failing"
            contextBannerView?.visibility = View.VISIBLE
        } else {
            contextBannerView?.visibility = View.GONE
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_ATTACHMENT_REQUEST || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        Thread { handlePickedAttachment(uri) }.start()
    }

    // Copies the picked document into this app's own private storage
    // immediately, off the UI thread -- never keeps just the picker's
    // content:// URI as the source of truth. That URI's read grant (and
    // the source app's own cache entry backing it) can both go away well
    // before a slow/queued upload gets around to actually reading it;
    // this app's own copy is durable the same way a typed message's local
    // row already is (never lost before the network is even touched).
    private fun handlePickedAttachment(uri: Uri) {
        val resolver = contentResolver
        var displayName = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) c.getString(nameIdx)?.let { displayName = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"

        val dir = File(filesDir, "pending_attachments").also { it.mkdirs() }
        val destFile = File(dir, "${java.util.UUID.randomUUID()}_$displayName")
        val actualSize = try {
            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { out ->
                    // Copy in bounded chunks, aborting past the cap rather
                    // than fully buffering an oversized file into memory
                    // or onto disk first and rejecting only afterward.
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ATTACHMENT_BYTES) throw java.io.IOException("file too large")
                        out.write(buf, 0, n)
                    }
                    total
                }
            } ?: throw java.io.IOException("could not open picked file")
        } catch (e: Exception) {
            destFile.delete()
            runOnUiThread { Toast.makeText(this, "Couldn't read that file: ${e.message}", Toast.LENGTH_LONG).show() }
            return
        }

        try {
            db.insertAttachmentOutbox(sessionId, destFile.absolutePath, displayName, mimeType, actualSize, pendingAttachmentCaption)
        } catch (e: Exception) {
            destFile.delete()
            runOnUiThread { Toast.makeText(this, "Couldn't save attachment locally, try again: ${e.message}", Toast.LENGTH_LONG).show() }
            return
        }
        runOnUiThread {
            input.setText("")
            loadCached()
            OutboxJobService.scheduleImmediate(this)
        }
    }

    // Used by MessageAdapter's Download action -- prefers the already-local
    // copy (what we sent, still cached) over a network round trip; falls
    // back to fetching from the daemon (GET /api/v1/attachments/<id>) when
    // there's no local copy (a previous install, cache cleared, etc).
    // MediaStore.Downloads is plain platform API (no WRITE_EXTERNAL_STORAGE,
    // no custom ContentProvider of our own to get subtly wrong) -- the file
    // lands in the real, user-visible Downloads collection, openable from
    // any other app the same way any other download is.
    fun downloadAttachment(localPath: String?, attachmentId: String?, filename: String, mimeType: String) {
        Thread {
            try {
                val bytes = if (localPath != null && File(localPath).isFile) {
                    File(localPath).readBytes()
                } else if (attachmentId != null) {
                    RelayClient(this).downloadAttachment(attachmentId).data
                } else {
                    throw java.io.IOException("no local copy and not yet uploaded")
                }
                val uri = saveToDownloads(filename, mimeType, bytes)
                runOnUiThread {
                    if (uri != null) {
                        Toast.makeText(this, "Saved to Downloads: $filename", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Couldn't save to Downloads", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun saveToDownloads(filename: String, mimeType: String, bytes: ByteArray): Uri? {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private class CommandRowAdapter(private val context: Context, private val data: List<String>) : BaseAdapter() {
        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): TextView {
            val tv = convertView as? TextView ?: TextView(context).apply {
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                textSize = 14f
                setTextColor(Theme.onBackground)
                setBackgroundColor(Theme.surface)
                val padH = Theme.dp(context, 14)
                val padV = Theme.dp(context, 10)
                setPadding(padH, padV, padH, padV)
            }
            tv.text = data[position]
            return tv
        }
    }

    // Hand-rolled dropdown (no androidx AutoCompleteTextView-style widget
    // reachable here either, same reason as everything else in this app --
    // no dependency resolver) that lists slash commands matching whatever
    // the user has typed so far, opening *above* the input row since it
    // sits at the very bottom of the screen -- a plain showAsDropDown would
    // try to open below and run off-screen.
    private fun updateCommandPopup(input: EditText) {
        val text = input.text.toString()
        val cursorAtEnd = input.selectionStart == text.length
        val matches = if (cursorAtEnd && text.startsWith("/") && !text.contains(" ") && text.length > 1) {
            slashCommands.filter { it.startsWith(text, ignoreCase = true) && it != text }
        } else {
            emptyList()
        }
        if (matches.isEmpty()) {
            commandPopup?.dismiss()
            return
        }

        val listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.setBackgroundColor(Theme.surface)
        listView.adapter = CommandRowAdapter(this, matches)
        listView.setOnItemClickListener { _, _, position, _ ->
            input.setText(matches[position] + " ")
            input.setSelection(input.text.length)
            commandPopup?.dismiss()
        }

        val popup = commandPopup ?: PopupWindow(this).also {
            it.isOutsideTouchable = true
            it.isFocusable = false
            it.setBackgroundDrawable(Theme.roundedDrawable(Theme.surface, this, strokeColor = Theme.outlineVariant))
            commandPopup = it
        }
        popup.contentView = listView

        val loc = IntArray(2)
        input.getLocationOnScreen(loc)
        val rowHeightPx = Theme.dp(this, 40)
        val popupHeight = rowHeightPx * matches.size.coerceAtMost(6)
        val x = loc[0]
        val y = loc[1] - popupHeight
        if (popup.isShowing) {
            popup.update(x, y, input.width, popupHeight)
        } else {
            popup.width = input.width
            popup.height = popupHeight
            popup.showAtLocation(input, Gravity.NO_GRAVITY, x, y)
        }
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
        commandPopup?.dismiss()
        try {
            unregisterReceiver(changedReceiver)
        } catch (e: IllegalArgumentException) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // unbind() only detaches this Activity's ServiceConnection -- it no
        // longer stops playback (asked for explicitly: "don't stop media
        // playback when i escape from a conversation ... should still be
        // playable in the background via normal android media control").
        // The read, if one is in progress, keeps going in
        // TtsPlaybackService's own foreground-service notification.
        readAloud.unbind()
    }

    fun copyMessage(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    // "start from the selected message and continue until the end" (asked
    // for explicitly) -- concatenates this row's text plus every real
    // prose row after it in the currently-rendered list (tool-call
    // bundles/attachments are skipped: reading raw tool output or a
    // filename aloud isn't useful). fromId is a ChatDisplayRow.id, not a
    // list position -- positions shift under the adapter on every
    // loadCached(), ids don't.
    fun readAloudFrom(fromId: String) {
        val items = adapter.items
        val startIdx = items.indexOfFirst { it.id == fromId }
        if (startIdx < 0) return
        // One section per message (asked for explicitly: "have this app
        // ... split text into sections, in this app this logically would
        // be split by messages") -- readAloudSectionRowIds is the parallel
        // list of each section's own ChatDisplayRow.id, letting
        // ReadAloudController's index-based onSectionChanged/
        // onWordHighlight callbacks (it has no idea what a "message" is)
        // be mapped back to a specific bubble for the highlight overlay.
        val rows = items.subList(startIdx, items.size)
            .filter { it.toolItems == null && it.attachment == null && it.text.isNotBlank() && it.id != null }
        if (rows.isEmpty()) return
        readAloudSectionRowIds = rows.map { it.id!! }
        readAloud.start(intent.getStringExtra("title") ?: sessionId, rows.map { it.text })
    }

    private var everLoaded = false

    // A "→ Bash"/"→ Read(...)" tool-call line is immediately followed by
    // its own tool_result line in the raw transcript (each content block
    // is its own transcript line -- confirmed live 2026-09-04), and a
    // multi-step turn produces a whole run of these back to back. Every
    // consecutive tool call/result in that run gets bundled into one
    // ChatDisplayRow (one outer bubble), with each individual call+result
    // pair inside it independently foldable -- asked for explicitly:
    // "when you have multiple consecutive tool uses bundle them into one
    // message that can be folded individually". A run ends as soon as an
    // ordinary prose user/assistant message appears.
    private fun isToolCallRow(row: MessageRow) = row.role == "assistant" && row.text.startsWith("→ ")
    private fun isToolResultRow(row: MessageRow) = row.role == "tool_result"

    private fun groupToolCallsWithResults(rows: List<MessageRow>): List<ChatDisplayRow> {
        val out = mutableListOf<ChatDisplayRow>()
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            if (isToolCallRow(row) || isToolResultRow(row)) {
                val items = mutableListOf<ToolCallItem>()
                while (i < rows.size) {
                    val r = rows[i]
                    if (isToolCallRow(r)) {
                        val next = rows.getOrNull(i + 1)
                        if (next != null && isToolResultRow(next)) {
                            items.add(ToolCallItem(r.text, next.text, false, r.line.toString()))
                            i += 2
                        } else {
                            items.add(ToolCallItem(r.text, null, false, r.line.toString()))
                            i += 1
                        }
                    } else if (isToolResultRow(r)) {
                        // A result with no preceding call in this run -- its
                        // call must have ended the previous bundle (or never
                        // synced). Still shown, just as its own foldable line.
                        items.add(ToolCallItem(r.text, null, true, r.line.toString()))
                        i += 1
                    } else {
                        break
                    }
                }
                out.add(ChatDisplayRow("assistant", "", null, id = items.first().id, toolItems = items))
            } else {
                out.add(ChatDisplayRow(row.role, row.text, null, id = row.line.toString(), errorType = row.errorType))
                i += 1
            }
        }
        return out
    }

    private fun loadCached() {
        // Auto-scroll to the newest message only if the user was already
        // at (or near) the bottom -- otherwise a poll/outbox update while
        // they've scrolled up to check something earlier yanks the view
        // back down out from under them. Always scrolls on the very first
        // load for this activity instance (nothing to preserve yet), since
        // lastVisiblePosition is unreliable before the list's first layout
        // pass has actually happened.
        val nearBottom = !everLoaded ||
            (adapter.count > 0 && listView.lastVisiblePosition >= adapter.count - 2)
        everLoaded = true

        // The reference line an attachment's own upload generates
        // ("[image attached: /path, original name: ..., N bytes]") does
        // sync down like any other real user message eventually -- filtered
        // out here since the attachment bubble below already represents it
        // (with an actual thumbnail/download card), showing both would just
        // be the same send twice, once nicely and once as a raw path.
        val rawMessages = db.listMessages(sessionId)
            .filterNot { it.role == "user" && ATTACHMENT_REF_RE.containsMatchIn(it.text) }
        // Real-message timestamp per transcript line, carried forward across
        // any line missing its own ts (rare, but a merge key needs one) --
        // used below to interleave pending outbox rows into the actual
        // chronological position rather than always trailing every synced
        // message. Real ts values and outbox created_at are both true UTC
        // epoch millis, so they're directly comparable.
        val lineMillis = HashMap<Int, Long>()
        var lastKnownMillis = 0L
        for (m in rawMessages) {
            parseTsMillis(m.ts)?.let { lastKnownMillis = it }
            lineMillis[m.line] = lastKnownMillis
        }
        val messages = groupToolCallsWithResults(rawMessages)
            .map { row -> row to (row.id?.toIntOrNull()?.let { lineMillis[it] } ?: 0L) }

        // Keep showing an outbox row until its content actually shows up as
        // a real synced message -- "the daemon accepted/delivered it" is
        // NOT the same moment as "it's visible anywhere", and treating them
        // as the same was a real reported bug: mid-turn (Claude still busy
        // on the previous exchange), tmux send-keys queues the keystrokes
        // into the pty and the daemon reports delivered=true immediately,
        // but the message doesn't actually reach the transcript until
        // Claude gets around to reading stdin again -- which can be a
        // while. This row used to vanish the instant "delivered" came
        // back, on the assumption the real line would appear "soon"; if it
        // didn't (busy pane, or the reported spotty-connection pane-
        // targeting issues), the message was invisible everywhere in the
        // meantime -- not pending, not sent, nothing. Now it keeps showing
        // (relabeled once delivered) until reconcileDeliveredOutbox below
        // actually finds the matching real message and retires it.
        //
        // That fix exposed a second, separate bug (reported live
        // 2026-09-08): this used to always render as messages + pendingOutbox
        // -- two fixed blocks, every pending row trailing every synced one
        // no matter when either actually happened. That was invisible
        // before, since a pending row vanished almost the instant it was
        // superseded; now that it can legitimately stay visible for a
        // while, sending several messages before earlier ones resolve made
        // real replies appear to sync in *above* a growing stuck pile of
        // your own newer messages. Fixed by merging both lists on their
        // real timestamp instead of concatenating two blocks -- a pending
        // row now renders exactly where it chronologically belongs,
        // including ahead of real content that arrived first but happened
        // later.
        // Whether THIS conversation still has a live tmux pane right now --
        // used below so a "delivered" row's label can say something
        // truthful once the session it was typed into has since ended
        // (e.g. the message itself was "/exit"): reported live 2026-09-10,
        // a message sent while offline stayed "sent — waiting for
        // response" indefinitely even after the daemon confirmed delivery
        // and the agent visibly acted on it and the session closed -- the
        // only way to tell it had actually worked was watching the
        // conversation disappear from the main list entirely, which this
        // very screen has no way to notice about itself. Not every
        // "delivered" message ends the session (most don't, and get
        // retired normally by reconcileDeliveredOutbox the moment the real
        // line syncs), so this only overrides the label for the minority
        // case where the session is already gone by the time this renders.
        val stillLive = db.getConversation(sessionId)?.isLive ?: true
        val pendingOutbox = db.listOutbox(sessionId)
            .map { row ->
                val status = when {
                    row.state == "pending" -> "sending…"
                    row.serverState == "queued" -> "queued on server — will send once the session is live"
                    row.serverState == "delivered" && !stillLive ->
                        "sent — the session ended before a reply could sync back"
                    row.serverState == "delivered" -> "sent — waiting for response"
                    else -> "sending…"
                }
                ChatDisplayRow("user", row.text, status, id = "outbox_${row.id}") to row.createdAt
            }
        // Unlike a plain text outbox row, an attachment bubble never gets
        // superseded/retired -- its own reference line is filtered out of
        // `messages` above precisely so this stays the one representation
        // of it, permanently (a thumbnail/download card is strictly more
        // useful than the raw path text it would otherwise be replaced by).
        // Only the status label changes as delivery confirms.
        val pendingAttachments = db.listAttachmentOutbox(sessionId)
            .map { row ->
                val status = when {
                    row.state == "pending" -> "uploading…"
                    row.serverState == "queued" -> "queued on server — will send once the session is live"
                    row.serverState == "delivered" -> "sent"
                    else -> "uploading…"
                }
                val info = AttachmentInfo(
                    localPath = row.localPath, filename = row.filename, mimeType = row.mimeType,
                    size = row.size, caption = row.caption, attachmentId = row.attachmentId
                )
                ChatDisplayRow("user", row.caption, status, id = "attachment_${row.id}", attachment = info) to row.createdAt
            }
        adapter.items = (messages + pendingOutbox + pendingAttachments).sortedBy { it.second }.map { it.first }
        if (nearBottom) {
            listView.post {
                listView.setSelection(adapter.count - 1)
                // A single post() isn't always enough on the very first
                // load of a long conversation (reported live 2026-09-09:
                // "opened at the top rather than at the bottom") --
                // variable-height rows (tool bundles, attachments) can
                // still be mid-measure at that point, so the target
                // position this resolves to isn't necessarily the real
                // bottom yet. A second, delayed pass re-asserts it once
                // layout has actually settled.
                listView.postDelayed({ listView.setSelection(adapter.count - 1) }, 150)
            }
        }
    }

    private fun parseTsMillis(ts: String?): Long? {
        if (ts == null) return null
        return try {
            java.time.Instant.parse(ts).toEpochMilli()
        } catch (e: Exception) {
            null
        }
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

    // A "delivered" outbox row is retired (deleted -- its content is now
    // fully represented by the real synced line) only once that real line
    // actually shows up among synced messages, never just because the
    // daemon said delivered=true. Exact-text match against synced *user*
    // messages, same matching style reconcileQueuedOutbox already uses
    // against the daemon's queue file -- a false match needs the exact
    // same text sent twice in the same conversation, an acceptable
    // tradeoff for not needing any new correlation id round-trip.
    private fun reconcileDeliveredOutbox(): Boolean {
        val delivered = db.listOutbox(sessionId).filter { it.serverState == "delivered" }
        if (delivered.isEmpty()) return false
        val syncedUserTexts = db.listMessages(sessionId).filter { it.role == "user" }.map { it.text }.toSet()
        var changed = false
        for (row in delivered) {
            if (row.text in syncedUserTexts) {
                db.deleteOutboxRow(row.id)
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
                    if (reconcileDeliveredOutbox()) changed = true
                    // Independent of "changed" -- status can flip busy<->idle
                    // on a tick with no new lines at all (e.g. Claude just
                    // started working, hasn't produced output yet), and the
                    // indicator needs to track that regardless.
                    val busy = resp.optString("status", "") == "busy"
                    val contextPct = if (resp.isNull("context_pct")) null else resp.optInt("context_pct", -1).takeIf { it >= 0 }
                    runOnUiThread {
                        thinkingRow?.visibility = if (busy) View.VISIBLE else View.GONE
                        updateContextBanner(contextPct)
                        if (changed) loadCached()
                    }
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
