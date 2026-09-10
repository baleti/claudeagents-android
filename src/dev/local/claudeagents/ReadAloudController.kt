package dev.local.claudeagents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Wires a WebSocketClient (against the shared TTS server's /tts/stream --
 * see newsdigest-android/server/server.py, already general-purpose: it
 * takes arbitrary text/engine/voice, nothing digest-specific) to
 * TtsPlaybackService. Adapted from newsdigest-android's own
 * ReadAloudController.
 *
 * Text is read as a list of `sections` (one message each, in this app --
 * see ChatActivity.readAloudFrom) rather than one big blob: each section
 * is streamed to the server as its own /tts/stream request, chained back
 * to back onto the same TtsPlaybackService session so playback crosses a
 * section boundary seamlessly, but the boundary itself is a real,
 * trackable point -- skipToNextSection() abandons whatever's left of the
 * current section and jumps straight to the next one (same
 * abandon-and-restart mechanism newsdigest's word-tap-seek already uses,
 * see TtsPlaybackService.jumpToUpcoming), and onSectionChanged/
 * onWordHighlight let a caller drive a per-message highlight overlay
 * (unlike newsdigest's single continuous article view, this app's chat is
 * one bubble per section, so highlighting needs to know not just *which
 * word* but *which message* it's currently in).
 */
class ReadAloudController(
    private val context: Context,
    private val onStateChanged: (playing: Boolean) -> Unit,
    // Fires on every real play/pause transition, from ANY trigger -- the
    // in-app controls, the system notification's own button, a seek,
    // audio-focus loss/gain.
    private val onPlayingChanged: (playing: Boolean) -> Unit = {},
    // Fires when playback moves into a different section (including the
    // very first one) -- the index into the `sections` list passed to
    // start(). Lets a caller map "what's playing now" back to its own
    // source (e.g. a ChatDisplayRow id) without this controller needing
    // to know anything about that mapping itself.
    private val onSectionChanged: (sectionIndex: Int) -> Unit = {},
    // Fires as each word is spoken, with its character range WITHIN
    // sections[current section] (not the whole document) -- exactly the
    // range a caller would slice out of that one section's own source
    // text to know what to highlight in whatever view represents it.
    private val onWordHighlight: (sectionIndex: Int, charStart: Int, charEnd: Int) -> Unit = { _, _, _ -> },
) {
    private var ttsService: TtsPlaybackService? = null
    private var bound = false
    private var ws: WebSocketClient? = null
    @Volatile private var active = false
    @Volatile private var currentSpeed = 1.0f
    // Bumped every start()/skipToNextSection(). A stale message from an
    // abandoned stream can't touch shared state after a new one started
    // (same guard newsdigest's controller uses).
    @Volatile private var streamGeneration = 0

    private var sections: List<String> = emptyList()
    @Volatile private var currentSectionIndex = 0

    // Word-highlight search state, reset at the start of EACH section
    // (unlike newsdigest, which resets once for the whole document --
    // there each section's text is already part of one shared caption
    // buffer; here every section is its own separate source string, so
    // the search has to restart at 0 each time).
    private var searchCursor = 0
    private var currentSentenceStartOffset = 0
    private var currentWordRanges: List<IntRange> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val playbackListener = object : TtsPlaybackService.HighlightListener {
        override fun onSentenceStart(text: String, words: List<WordTiming>, startMs: Long) {
            mainHandler.post {
                val sectionText = sections.getOrNull(currentSectionIndex) ?: return@post
                var idx = sectionText.indexOf(text, searchCursor)
                if (idx < 0) idx = sectionText.indexOf(text) // shouldn't happen; best effort
                if (idx < 0) {
                    // Can't safely place a span for this sentence -- most
                    // often means the section's source text contained
                    // markdown syntax the server's markdown_to_speech()
                    // stripped before echoing this sentence back, so the
                    // strings no longer match exactly. Audio still plays
                    // fine; this sentence just isn't highlighted.
                    currentWordRanges = emptyList()
                    return@post
                }
                currentSentenceStartOffset = idx
                searchCursor = idx + text.length
                currentWordRanges = computeWordRanges(text, words)
            }
        }

        override fun onWordHighlight(wordIndex: Int) {
            mainHandler.post {
                if (wordIndex !in currentWordRanges.indices) return@post
                val range = currentWordRanges[wordIndex]
                onWordHighlight.invoke(
                    currentSectionIndex,
                    currentSentenceStartOffset + range.first,
                    currentSentenceStartOffset + range.last + 1,
                )
            }
        }

        override fun onSentenceEnd() {}

        override fun onPlayingChanged(playing: Boolean) {
            mainHandler.post { this@ReadAloudController.onPlayingChanged.invoke(playing) }
        }

        override fun onQueueIdle() {
            mainHandler.post {
                if (active) {
                    active = false
                    onStateChanged.invoke(false)
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as TtsPlaybackService.LocalBinder).service()
            ttsService = svc
            svc.setListener(playbackListener)
            bound = true
            // A previous ChatActivity instance may have started a session
            // and then gone away (back button, app switch) without
            // stopping it -- the service itself, a real foreground service
            // with its own notification, kept right on playing in the
            // background exactly as intended (asked for explicitly: "don't
            // stop media playback when i escape from a conversation").
            // This fresh controller instance's own `active` starts false
            // regardless, so without this check a still-playing session
            // would show no player bar at all until some new event
            // happened to fire onPlayingChanged.
            if (svc.hasActiveSession()) {
                active = true
                val playingNow = svc.isPlaying()
                mainHandler.post {
                    onStateChanged.invoke(true)
                    onPlayingChanged.invoke(playingNow)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService = null
            bound = false
        }
    }

    fun bind() {
        context.bindService(Intent(context, TtsPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Detaches this controller from the playback service WITHOUT stopping
     * playback -- TtsPlaybackService is a real foreground service with its
     * own MediaSession/notification specifically so a read continues
     * playing (and stays controllable from the notification/lock screen)
     * after the launching Activity is gone, the same way any other media
     * app behaves. Call stop() explicitly first if leaving really should
     * end the read (this controller never does that on its own). */
    fun unbind() {
        if (bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
    }

    fun isActive(): Boolean = active

    fun getSectionCount(): Int = sections.size
    fun getCurrentSectionIndex(): Int = currentSectionIndex
    fun hasNextSection(): Boolean = currentSectionIndex + 1 < sections.size

    /** title shows in the media notification. Each entry in `sections` is
     * read in order, chained seamlessly onto one continuous playback
     * session -- see streamCurrentSection(). */
    fun start(title: String, sections: List<String>) {
        stop()
        this.sections = sections
        currentSectionIndex = 0
        active = true
        onStateChanged.invoke(true)

        val svc = ttsService
        if (svc == null) {
            Log.e("ReadAloudController", "TtsPlaybackService not bound yet")
            active = false
            onStateChanged.invoke(false)
            return
        }
        // Explicitly START the service, not just bind it -- confirmed live
        // (2026-09-08) that a bind-only service is destroyed the instant
        // its last client unbinds, REGARDLESS of startForeground() having
        // already been called: startForeground() elevates process
        // priority/shows the notification while the service is alive, but
        // it does not by itself keep the component's lifecycle independent
        // of bindings. Without this, "don't stop playback when I leave the
        // conversation" silently did nothing -- the notification and
        // service both vanished the moment ChatActivity.onDestroy() ran
        // unbind(), even with the stop() call already removed from it.
        context.startForegroundService(Intent(context, TtsPlaybackService::class.java))
        svc.startSession(title)
        // See TtsPlaybackService.setEstimatedDuration's doc (kept in sync
        // with newsdigest-android's copy) - upfront guess from the whole
        // chained session's word count, not just the first section.
        val wordCount = sections.sumOf { s -> s.split(Regex("\\s+")).count { it.isNotBlank() } }
        svc.setEstimatedDuration((wordCount / (160.0 / 60.0) * 1000).toLong())
        svc.setPlaybackSpeed(currentSpeed)
        streamCurrentSection(svc)
    }

    /** Abandons whatever's left of the current section (already-queued
     * but not-yet-played audio for it is simply never played) and jumps
     * straight into streaming the next one. No-op past the last section. */
    fun skipToNextSection() {
        val svc = ttsService ?: return
        if (!hasNextSection()) return
        currentSectionIndex += 1
        ws?.close() // stop the old section's remaining sentences from arriving after the new one's
        svc.jumpToUpcoming()
        streamCurrentSection(svc)
    }

    /** Same as skipToNextSection() but backwards -- jumps to the start of
     * the previous section. No-op on the first section. */
    fun skipToPreviousSection() {
        val svc = ttsService ?: return
        if (currentSectionIndex <= 0) return
        currentSectionIndex -= 1
        ws?.close()
        svc.jumpToUpcoming()
        streamCurrentSection(svc)
    }

    /** Connects to /tts/stream for sections[currentSectionIndex], enqueues
     * every sentence that comes back, and on "done" either chains straight
     * into the next section (same session, no gap) or ends the session if
     * this was the last one. */
    private fun streamCurrentSection(svc: TtsPlaybackService) {
        val idx = currentSectionIndex
        val text = sections.getOrNull(idx) ?: return
        searchCursor = 0
        currentSentenceStartOffset = 0
        currentWordRanges = emptyList()
        onSectionChanged.invoke(idx)

        val myGeneration = ++streamGeneration
        fun isCurrent() = streamGeneration == myGeneration
        // Base position this stream builds on top of - each section is
        // its own fresh stream/connection, chained onto the same session,
        // so "played_ms" reported below has to be relative to THIS
        // section's own audio, matching how the server counts what it's
        // sent for this connection.
        val streamStartPositionMs = svc.getPositionMs()

        // Tells the server how far playback has actually gotten into this
        // section, every 500ms, so it can cap how far ahead of that it
        // synthesizes (see server.py's TTS_LOOKAHEAD_CAP_MS). A paused
        // player simply stops advancing getPositionMs(), which is exactly
        // what makes the server stall on its own - no separate pause
        // signal needed. Reported live 2026-09-09 (in newsdigest-android's
        // copy of this file): the server was synthesizing the entire
        // article right after the first play.
        fun reportPosition(client: WebSocketClient) {
            if (!isCurrent()) return
            val playedMs = (svc.getPositionMs() - streamStartPositionMs).coerceAtLeast(0)
            try {
                client.sendText(JSONObject().apply {
                    put("type", "position")
                    put("played_ms", playedMs)
                }.toString())
            } catch (_: Exception) {}
            mainHandler.postDelayed({ reportPosition(client) }, 500)
        }

        Thread {
            val client = WebSocketClient(
                TokenStore.getHost(context),
                TtsSettings.getTtsPort(context),
                "/tts/stream",
                mapOf("X-Peer-Agent" to "1"),
            )
            ws = client
            client.connect(object : WebSocketClient.Listener {
                private var pendingMeta: JSONObject? = null

                override fun onOpen() {
                    client.sendText(JSONObject().apply {
                        put("text", text)
                        put("engine", TtsSettings.getTtsEngine(context))
                    }.toString())
                    mainHandler.post { reportPosition(client) }
                }

                override fun onText(msg: String) {
                    if (!isCurrent()) return
                    val obj = JSONObject(msg)
                    when (obj.optString("type")) {
                        "sentence" -> pendingMeta = obj
                        "done" -> {
                            if (idx + 1 < sections.size) {
                                currentSectionIndex = idx + 1
                                streamCurrentSection(svc)
                            } else {
                                svc.endSession()
                            }
                        }
                        "error" -> {
                            Log.e("ReadAloudController", "server error: ${obj.optString("message")}")
                            svc.endSession()
                            mainHandler.post {
                                if (active) {
                                    active = false
                                    onStateChanged.invoke(false)
                                }
                            }
                        }
                    }
                }

                override fun onBinary(data: ByteArray) {
                    if (!isCurrent()) return
                    val meta = pendingMeta ?: return
                    val words = mutableListOf<WordTiming>()
                    val wordsArray = meta.optJSONArray("words")
                    if (wordsArray != null) {
                        for (i in 0 until wordsArray.length()) {
                            val w = wordsArray.getJSONObject(i)
                            words.add(WordTiming(w.getString("word"), w.getInt("start_ms"), w.getInt("end_ms")))
                        }
                    }
                    svc.enqueueSentence(meta.getString("text"), words, data, meta.getInt("sample_rate"))
                }

                override fun onFailure(error: Throwable) {
                    if (!isCurrent()) return // expected: skipToNextSection()'s ws.close() surfaces as a failure on the abandoned stream
                    Log.e("ReadAloudController", "websocket failed", error)
                    mainHandler.post {
                        active = false
                        onStateChanged.invoke(false)
                    }
                }
            })
        }.apply { isDaemon = true; name = "ReadAloudWs"; start() }
    }

    fun stop() {
        active = false
        streamGeneration++
        ws?.close()
        ws = null
        ttsService?.stopAll()
    }

    fun pause() = ttsService?.pause()
    fun resume() = ttsService?.resume()

    fun seekRelative(deltaMs: Long) {
        val svc = ttsService ?: return
        svc.seekTo((svc.getPositionMs() + deltaMs).coerceAtLeast(0))
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        ttsService?.setPlaybackSpeed(speed)
    }

    fun getSpeed(): Float = currentSpeed

    /** Sequentially matches each timed word against the sentence text to
     * find its character range -- the words are exactly the sentence's own
     * whitespace-split tokens (see server.py estimate_word_timings), so a
     * simple left-to-right scan is enough; no fuzzy matching needed. */
    private fun computeWordRanges(sentenceText: String, words: List<WordTiming>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var searchFrom = 0
        for (w in words) {
            val idx = sentenceText.indexOf(w.word, searchFrom)
            if (idx < 0) {
                ranges.add(IntRange.EMPTY)
                continue
            }
            ranges.add(idx..(idx + w.word.length - 1))
            searchFrom = idx + w.word.length
        }
        return ranges
    }
}
