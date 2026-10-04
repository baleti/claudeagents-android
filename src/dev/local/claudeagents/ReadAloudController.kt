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

// Generic fallback before any real synth_ms data exists at all - start()
// replaces this with an engine-aware guess the moment it knows which
// engine (see start()'s own doc).
private const val DEFAULT_ESTIMATE_MS = 4000L

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
    // The server's step-by-step `status` events - see SynthesizingBanner.addStatus.
    private val onStatus: (message: String, sentence: Int, of: Int) -> Unit = { _, _, _ -> },
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
    // Fires true (with a live best-guess of how many ms the wait will be)
    // right when start() is called and again after a sentence finishes
    // playing with nothing queued yet to follow it; false once the next
    // one actually starts. Chatterbox in particular can take 5-15s to
    // synthesize a sentence - streaming means that gap is expected, but
    // with no visual cue it reads as the app having frozen rather than
    // still working (reported live 2026-09-11: "20 second breaks with no
    // warning"). The estimate is a rolling average of this session's own
    // observed synth_ms per sentence, seeded with a generic per-engine
    // guess before any real data exists - ported from newsdigest-android's
    // own ReadAloudController/SynthesizingBanner, which already solved
    // this same gap there. Optional - callers that don't care about
    // showing a "still generating..." indicator can leave it out.
    private val onGenerating: (generating: Boolean, estimatedMs: Long) -> Unit = { _, _ -> },
) {
    private var ttsService: TtsPlaybackService? = null
    private var bound = false
    private var ws: WebSocketClient? = null
    private val localBridge = LocalTtsBridge.shared(context)
    // The exact text the bridge already spoke for section 0's first
    // sentence, if any - set right before kicking off that section's real
    // stream, cleared (whether it matched or not) the moment that stream's
    // own first sentence event arrives. See openConnection's onBinary for
    // the actual skip-if-matches logic.
    @Volatile private var active = false
    @Volatile private var currentSpeed = 1.0f
    // Bumped only on a genuine abandon-everything event - start()/stop()/
    // skipToNextSection()/skipToPreviousSection() - NOT on ordinary
    // section-to-section chaining (that's now a live/ahead promotion, see
    // SectionSession below, and both a section's own connection and its
    // prefetched successor share the same epoch throughout normal
    // playback). A stale message from an abandoned stream can't touch
    // shared state after a new one started (same guard newsdigest's
    // controller uses).
    @Volatile private var streamGeneration = 0

    private class PendingAudio(val text: String, val words: List<WordTiming>, val pcm: ByteArray, val sampleRate: Int)

    // One /tts/stream connection for one section. `live`: arrivals go
    // straight to TtsPlaybackService.enqueueSentence(); false means this is
    // a prefetch running ahead of its turn, so arrivals are buffered
    // instead - flipped to true (and the buffer flushed, in order) the
    // moment this section is actually promoted to live. Lets a section's
    // synthesis get a real head start while the PREVIOUS section is still
    // playing, without breaking playback order.
    private class SectionSession(val index: Int, val epoch: Int) {
        @Volatile var live = false
        val buffer = mutableListOf<PendingAudio>()
        @Volatile var done = false
        var ws: WebSocketClient? = null
    }

    // The section currently playing, and (if one has been started) the
    // next section's connection running ahead of it. Confirmed live
    // 2026-09-22: without this, Claude Agents' bubble-by-bubble chaining
    // (unlike newsdigest-android's single continuous-article stream) paid
    // a full cold "new connection + first sentence synth" gap at EVERY
    // message boundary, no matter how deep the server's own pipelining
    // buffered within one connection - server-side buffering only helps
    // WITHIN a stream, and this app opens a new one per chat bubble.
    private var liveSession: SectionSession? = null
    private var aheadSession: SectionSession? = null
    // The TtsPlaybackService's OWN session generation at the moment THIS
    // controller's start() called startSession() -- captured once, not
    // re-captured per section, since it stays valid for the whole life of
    // this read (chained sections, skipToNext/PreviousSection,
    // appendSections() in live mode all reuse the same underlying
    // service session). See TtsPlaybackService.currentSessionGeneration()'s
    // own doc for why this exists: streamGeneration alone only detects a
    // newer stream from THIS SAME controller instance, not a completely
    // different controller (a different ChatActivity, still alive in the
    // background after being left mid-read) taking over the shared
    // service out from under it.
    @Volatile private var myServiceSessionGeneration = -1

    private var sections: List<String> = emptyList()
    @Volatile private var currentSectionIndex = 0
    // True for the life of a live-mode session (see start()'s `live` param)
    // -- when the currently-known last section finishes, the "done"
    // handler in streamCurrentSection() leaves the session running instead
    // of calling svc.endSession(), so TtsPlaybackService's own play loop
    // just idles (see its `sessionEnded` doc) rather than firing the real
    // onQueueIdle "playback stopped" event. appendSections() is how a
    // caller (ChatActivity, once its poll loop notices new messages)
    // supplies more text to keep going.
    @Volatile private var liveMode = false
    // True exactly when a live-mode session has run out of known sections
    // and is sitting idle waiting for appendSections() to supply more --
    // distinguishes that from "still streaming/playing a known section",
    // so appendSections() only kicks off a new stream when one is actually
    // needed rather than racing an in-flight one.
    @Volatile private var stalledAtEnd = false

    // Word-highlight search state, reset at the start of EACH section
    // (unlike newsdigest, which resets once for the whole document --
    // there each section's text is already part of one shared caption
    // buffer; here every section is its own separate source string, so
    // the search has to restart at 0 each time).
    private var searchCursor = 0
    private var currentSentenceStartOffset = 0
    private var currentWordRanges: List<IntRange> = emptyList()

    // Rolling average of synth_ms across this controller's own observed
    // sentences (kept across separate start() calls in the same Activity,
    // not just within one session).
    @Volatile private var avgSynthMs: Long = DEFAULT_ESTIMATE_MS
    private var synthSampleCount = 0

    private fun recordSynthMs(ms: Long) {
        if (ms <= 0) return
        synthSampleCount++
        // Weight recent samples more heavily so the estimate adapts if
        // the server's pace changes mid-session (e.g. a GPU warming up).
        avgSynthMs = if (synthSampleCount == 1) ms else (avgSynthMs * 3 + ms) / 4
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val playbackListener = object : TtsPlaybackService.HighlightListener {
        override fun onSentenceStart(text: String, words: List<WordTiming>, startMs: Long) {
            mainHandler.post {
                onGenerating(false, 0L)
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

        override fun onSentenceEnd() {
            mainHandler.post { if (active) onGenerating(true, avgSynthMs) }
        }

        override fun onPlayingChanged(playing: Boolean) {
            mainHandler.post { this@ReadAloudController.onPlayingChanged.invoke(playing) }
        }

        override fun onQueueIdle() {
            mainHandler.post {
                onGenerating(false, 0L)
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
            svc.addListener(playbackListener)
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
        // Fire-and-forget, well before start() is ever likely to be called
        // - engine init alone measured ~2.7s the first time an app process
        // asks for it, so warming up only when the user presses play would
        // largely defeat the point.
        localBridge.warmUp()
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
            // Unregister first -- listeners is now a set every bound
            // controller shares (see TtsPlaybackService's own doc), so
            // leaving this in would keep a destroyed Activity's controller
            // (and everything it closes over) reachable from the service
            // indefinitely, a real leak across repeated open/close cycles.
            ttsService?.removeListener(playbackListener)
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
    }

    fun isActive(): Boolean = active

    /** Which conversation the CURRENT background session belongs to, if
     * any -- see TtsPlaybackService.currentConversationId()'s own doc.
     * Lets a controller bound from a different screen than the one that
     * started playback (MainActivity, in particular) figure out where a
     * "locate" tap should navigate. */
    fun currentConversationId(): String? = ttsService?.currentConversationId()

    fun getSectionCount(): Int = sections.size
    fun getCurrentSectionIndex(): Int = currentSectionIndex
    fun hasNextSection(): Boolean = currentSectionIndex + 1 < sections.size

    /** title shows in the media notification. Each entry in `sections` is
     * read in order, chained seamlessly onto one continuous playback
     * session -- see streamCurrentSection(). `live`: when the last known
     * section finishes, keep the session open and wait for appendSections()
     * to supply more (asked for explicitly 2026-09-13: reading should keep
     * up with a conversation that's still producing new messages, e.g.
     * Claude still typing or a new one the user sends) rather than ending
     * the read the instant it catches up to "everything there was when I
     * pressed play". */
    fun start(title: String, sections: List<String>, live: Boolean = false, conversationId: String? = null) {
        stop()
        this.sections = sections
        this.liveMode = live
        this.stalledAtEnd = false
        currentSectionIndex = 0
        active = true
        onStateChanged.invoke(true)
        if (synthSampleCount == 0) {
            // No real data yet at all (first read this activity has done) -
            // seed with an engine-aware guess rather than the generic
            // default, so the very first estimate isn't wildly off for
            // Chatterbox in particular.
            avgSynthMs = if (TtsSettings.getTtsEngine(context) == "chatterbox") 10_000L else 2_000L
        }
        onGenerating(true, avgSynthMs) // nothing synthesized yet either - same "still working" state as a mid-read gap

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
        svc.startSession(title, conversationId)
        myServiceSessionGeneration = svc.currentSessionGeneration()
        // See TtsPlaybackService.setEstimatedDuration's doc (kept in sync
        // with newsdigest-android's copy) - upfront guess from the whole
        // chained session's word count, not just the first section.
        val wordCount = sections.sumOf { s -> s.split(Regex("\\s+")).count { it.isNotBlank() } }
        svc.setEstimatedDuration((wordCount / (160.0 / 60.0) * 1000).toLong())
        svc.setPlaybackSpeed(currentSpeed)
        streamCurrentSection(svc)
    }

    /** Sentence char ranges within `text`, split like server.py's
     * split_sentences (sentence-ending punctuation + whitespace, blank
     * lines) so local and server agree where sentences begin. */
    private fun splitLocalSentences(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (i in text.indices) {
            if (start < 0 && !text[i].isWhitespace()) start = i
            val boundary = start >= 0 && text[i].isWhitespace() && i > 0 &&
                (text[i - 1] in ".!?" || (text[i] == '\n' && i + 1 < text.length && text[i + 1] == '\n'))
            if (boundary) { out.add(start..(i - 1)); start = -1 }
        }
        if (start >= 0) out.add(start..text.trimEnd().length - 1)
        return out.filter { it.last >= it.first }
    }

    /** Evenly-spaced word timing guess for bridge audio, whose real timing
     * Android's TTS doesn't hand back the way the server's own
     * estimate_word_timings() does for a cache hit - same idea, just
     * computed client-side since this audio never touched the server. */
    private fun estimateWordTimings(text: String, durationMs: Long): List<WordTiming> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val perWordMs = durationMs.toDouble() / words.size
        return words.mapIndexed { i, w -> WordTiming(w, (i * perWordMs).toInt(), ((i + 1) * perWordMs).toInt()) }
    }

    /** Abandons whatever's left of the current section (already-queued
     * but not-yet-played audio for it is simply never played) and jumps
     * straight into streaming the next one. No-op past the last section. */
    fun skipToNextSection() {
        val svc = ttsService ?: return
        if (!hasNextSection()) return
        currentSectionIndex += 1
        abandonSessions() // a skip invalidates the whole lookahead, not just the live connection
        svc.jumpToUpcoming()
        onGenerating(true, avgSynthMs)
        streamCurrentSection(svc)
    }

    /** Adds more text to read once it becomes available, for a session
     * started with `live = true` -- called by ChatActivity's poll loop
     * when it notices new messages synced in after the last one this
     * session already knew about. A no-op outside live mode, or if there's
     * nothing to add. Only actually kicks off a new stream if playback had
     * genuinely caught up and was waiting (stalledAtEnd) -- otherwise the
     * new section is just appended to the backlog and the section
     * currently in flight (or its already-running prefetch) will reach it
     * naturally once it finishes. */
    fun appendSections(newSections: List<String>) {
        if (newSections.isEmpty() || !liveMode) return
        sections = sections + newSections
        if (stalledAtEnd) {
            val svc = ttsService ?: return
            stalledAtEnd = false
            currentSectionIndex += 1
            onGenerating(false, 0L)
            streamCurrentSection(svc)
        }
    }

    /** Same as skipToNextSection() but backwards -- jumps to the start of
     * the previous section. No-op on the first section. */
    fun skipToPreviousSection() {
        val svc = ttsService ?: return
        if (currentSectionIndex <= 0) return
        currentSectionIndex -= 1
        abandonSessions()
        svc.jumpToUpcoming()
        onGenerating(true, avgSynthMs)
        streamCurrentSection(svc)
    }

    /** Closes both the live connection and any in-flight prefetch, and
     * bumps the epoch so anything still arriving on either is ignored from
     * here on - used by every "abandon everything and jump elsewhere"
     * entry point (skip forward/back, stop()). Ordinary section-to-section
     * chaining does NOT call this - see SectionSession's own doc. */
    private fun abandonSessions() {
        streamGeneration++
        liveSession?.ws?.close()
        aheadSession?.ws?.close()
        liveSession = null
        aheadSession = null
        ws = null
    }

    /** Makes the section at currentSectionIndex the live one - either by
     * promoting an already-running prefetch (the common case once the
     * pipeline is warm: no new connection, no cold-start gap) or, if
     * nothing was prefetched yet (the very first section, or right after a
     * skip), by opening a fresh connection. */
    private fun streamCurrentSection(svc: TtsPlaybackService) {
        val idx = currentSectionIndex
        if (sections.getOrNull(idx) == null) return
        val ahead = aheadSession
        val session = if (ahead != null && ahead.index == idx && ahead.epoch == streamGeneration) {
            aheadSession = null
            ahead
        } else {
            val fresh = SectionSession(idx, streamGeneration)
            openConnection(svc, fresh)
            fresh
        }
        promote(svc, session)
    }

    /** Flips a section's connection from "prefetching, buffering" to
     * "live, enqueueing for real" - flushing whatever already arrived (in
     * order) and updating all the per-section bookkeeping (highlight
     * state, position-reporting baseline, the caller-facing
     * onSectionChanged callback). If the section had ALREADY fully
     * arrived while it was still just a prefetch, its "done" is handled
     * immediately rather than waiting for an event that already happened.
     * Also kicks off prefetching the NEXT section, one deep - matching
     * TTS_MAX_CONCURRENT_SYNTHESIS server-side (see server.py), and the
     * same "one ahead" principle as the GPU pipelining that motivated it. */
    private fun promote(svc: TtsPlaybackService, session: SectionSession) {
        val idx = session.index
        searchCursor = 0
        currentSentenceStartOffset = 0
        currentWordRanges = emptyList()
        onSectionChanged.invoke(idx)
        liveSession = session
        ws = session.ws

        val flushed: List<PendingAudio>
        val alreadyDone: Boolean
        synchronized(session) {
            session.live = true
            flushed = session.buffer.toList()
            session.buffer.clear()
            alreadyDone = session.done
        }
        for (p in flushed) svc.enqueueSentence(p.text, p.words, p.pcm, p.sampleRate)

        // Tells the server how far playback has actually gotten into this
        // section, every 500ms, so it can cap how far ahead of that it
        // synthesizes (see server.py's TTS_LOOKAHEAD_CAP_MS). A paused
        // player simply stops advancing getPositionMs(), which is exactly
        // what makes the server stall on its own - no separate pause
        // signal needed. Baseline is captured HERE, at promotion, not at
        // connection-open time - a prefetching connection may have already
        // been open for a while with no position feedback at all, which is
        // fine/intended (see GpuSlotManager... no, TTS_LOOKAHEAD_CAP_MS's
        // own doc server-side: with no position reports yet it just
        // buffers eagerly, exactly the head start prefetching wants).
        val streamStartPositionMs = svc.getPositionMs()
        fun isCurrent() = session.epoch == streamGeneration && svc.currentSessionGeneration() == myServiceSessionGeneration
        fun reportPosition() {
            val client = session.ws ?: return
            if (!isCurrent()) { client.close(); return }
            val playedMs = (svc.getPositionMs() - streamStartPositionMs).coerceAtLeast(0)
            try {
                client.sendText(JSONObject().apply {
                    put("type", "position")
                    put("played_ms", playedMs)
                }.toString())
            } catch (_: Exception) {}
            mainHandler.postDelayed(::reportPosition, 500)
        }
        reportPosition()

        if (alreadyDone) {
            handleSectionDone(svc, session)
        } else if (idx + 1 < sections.size) {
            maybeStartPrefetch(svc, idx + 1)
        }
    }

    /** A section's stream reported "done" while it was already live -
     * chain into whatever's next: promote an already-running prefetch (no
     * gap), fall back to opening a fresh connection if nothing was
     * prefetched, wait for more in live mode, or end the session. */
    private fun handleSectionDone(svc: TtsPlaybackService, session: SectionSession) {
        val idx = session.index
        if (idx + 1 < sections.size) {
            currentSectionIndex = idx + 1
            streamCurrentSection(svc)
        } else if (liveMode) {
            // Nothing more to read YET -- don't end the session, just
            // wait. TtsPlaybackService's play loop idles without firing
            // the real "stopped" callback as long as endSession() was
            // never called, so playback UI stays in its normal "still
            // working" gap state (same one a mid-section synthesis gap
            // already shows) rather than flipping back to a stopped
            // Read Aloud button.
            stalledAtEnd = true
        } else {
            svc.endSession()
        }
    }

    /** Opens a connection for section `idx` one section ahead of whatever
     * is currently live, so its synthesis gets a real head start instead
     * of only starting once the current section finishes. No-op if
     * already prefetching this exact section. */
    private fun maybeStartPrefetch(svc: TtsPlaybackService, idx: Int) {
        if (idx !in sections.indices) return
        if (aheadSession?.index == idx) return
        val session = SectionSession(idx, streamGeneration)
        aheadSession = session
        openConnection(svc, session)
    }

    /** Connects to /tts/stream for sections[session.index]. Whether
     * arrivals get enqueued for real playback or just buffered depends
     * entirely on `session.live`, which this function never sets itself -
     * promote() is what flips it, possibly well after this connection was
     * opened (that's the whole point: a section's synthesis can start
     * before it's actually its turn to play). */
    private fun openConnection(svc: TtsPlaybackService, session: SectionSession) {
        val idx = session.index
        val text = sections.getOrNull(idx) ?: return
        fun isCurrent() = session.epoch == streamGeneration && svc.currentSessionGeneration() == myServiceSessionGeneration

        // Constructed (not connected) synchronously, on the caller's own
        // thread - promote() reads session.ws right after calling this and
        // needs it to already exist, not race a background thread that
        // hasn't gotten around to creating it yet (confirmed while writing
        // this: the original single-connection version never had this
        // problem because reportPosition() only ever started from inside
        // onOpen(), already on the connection's own thread).
        val client = WebSocketClient(
            TokenStore.getHost(context),
            TtsSettings.getTtsPort(context),
            "/tts/stream",
            mapOf("X-Peer-Agent" to "1"),
        )
        session.ws = client
        if (session.live) ws = client

        // --- Local-TTS bridge (section 0 only) --------------------------
        // Chatterbox can take 15-30s to produce its first sentence (cold
        // load, GPU model eviction). The phone's own TTS speaks the first
        // sentence at once and the server is asked to start AFTER it; if
        // the server is still behind when local audio runs low, local
        // speaks the next sentence too, and so on. The moment a server
        // sentence lands past what local covered, local stops for good
        // (feedLock keeps the two producers in order); server sentences
        // for text local already covered are dropped. Mirrors
        // newsdigest-android's ReadAloudController.streamText().
        val feedLock = Any()
        var serverTookOver = false
        var localEnd = 0
        var serverCursor = 0
        val bridge = if (idx == 0 && localBridge.isUsable()) localBridge else null
        val localSentences = if (bridge != null) splitLocalSentences(text) else emptyList()
        // If the engine is ready the server skips the sentence local speaks
        // first; if it is still warming up the server gets the whole text
        // (and any of its sentences local already covered are dropped).
        val readyNow = bridge?.isReady() == true
        val wsOffset = if (readyNow) (localSentences.firstOrNull()?.last?.plus(1) ?: 0) else 0
        serverCursor = wsOffset
        val sectionFinished = java.util.concurrent.atomic.AtomicBoolean(false)

        if (bridge != null && localSentences.isNotEmpty()) {
            Thread {
                if (!readyNow && !bridge.awaitReady(4000)) return@Thread
                for ((i, range) in localSentences.withIndex()) {
                    if (!isCurrent() || serverTookOver) return@Thread
                    while (i > 0 && isCurrent() && !serverTookOver && svc.bufferedAheadMs() > 700) Thread.sleep(100)
                    if (!isCurrent() || serverTookOver) return@Thread
                    val sentence = text.substring(range)
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var pcm: ByteArray? = null
                    var sr = 0
                    bridge.synthesize(speakableForLocalTts(sentence)) { p, r -> pcm = p; sr = r; latch.countDown() }
                    if (!latch.await(4, java.util.concurrent.TimeUnit.SECONDS)) return@Thread
                    val audio = pcm
                    if (audio == null || sr <= 0 || audio.isEmpty()) return@Thread
                    synchronized(feedLock) {
                        if (!isCurrent() || serverTookOver) return@Thread
                        svc.enqueueSentence(sentence, estimateWordTimings(sentence, audio.size.toLong() / 2 * 1000 / sr), audio, sr)
                        localEnd = range.last + 1
                    }
                }
                // Local covered the whole section (server dead or hopelessly slow).
                val finish = synchronized(feedLock) { isCurrent() && !serverTookOver && sectionFinished.compareAndSet(false, true) }
                if (finish) {
                    synchronized(session) { session.done = true }
                    mainHandler.post { if (isCurrent()) handleSectionDone(svc, session) }
                }
            }.apply { isDaemon = true; name = "ReadAloudLocalTts"; start() }
        }

        Thread {
            client.connect(object : WebSocketClient.Listener {
                private var pendingMeta: JSONObject? = null

                override fun onOpen() {
                    client.sendText(JSONObject().apply {
                        put("text", text.substring(wsOffset).trimStart())
                        put("engine", TtsSettings.getTtsEngine(context))
                        TtsSettings.getTtsVoice(context)?.let { put("voice", it) }
                    }.toString())
                }

                override fun onText(msg: String) {
                    // A different controller (a different ChatActivity, left
                    // mid-read instead of stopped -- see
                    // TtsPlaybackService.currentSessionGeneration()'s own
                    // doc) took over the shared service out from under this
                    // one. Close the connection instead of just ignoring
                    // its messages forever, so the server stops spending
                    // GPU time synthesizing audio nothing will ever play.
                    if (!isCurrent()) { client.close(); return }
                    val obj = JSONObject(msg)
                    when (obj.optString("type")) {
                        "sentence" -> pendingMeta = obj
                        "status" -> {
                            val m = obj.optString("message")
                            val k = obj.optInt("sentence"); val n = obj.optInt("of")
                            mainHandler.post { onStatus(m, k, n) }
                        }
                        "done" -> {
                            if (localSentences.isNotEmpty() && !sectionFinished.compareAndSet(false, true)) return
                            val wasLive = synchronized(session) {
                                session.done = true
                                session.live
                            }
                            if (wasLive) mainHandler.post { if (isCurrent()) handleSectionDone(svc, session) }
                        }
                        "error" -> {
                            Log.e("ReadAloudController", "server error: ${obj.optString("message")}")
                            if (localSentences.isNotEmpty() && !serverTookOver) return // local TTS keeps reading
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
                    if (!isCurrent()) { client.close(); return }
                    val meta = pendingMeta ?: return
                    val words = mutableListOf<WordTiming>()
                    val wordsArray = meta.optJSONArray("words")
                    if (wordsArray != null) {
                        for (i in 0 until wordsArray.length()) {
                            val w = wordsArray.getJSONObject(i)
                            words.add(WordTiming(w.getString("word"), w.getInt("start_ms"), w.getInt("end_ms")))
                        }
                    }
                    recordSynthMs(meta.optLong("synth_ms", -1))
                    val sentenceText = meta.getString("text")

                    // Local bridge may already have spoken this sentence:
                    // drop the server's copy; otherwise the server has
                    // caught up, so local stops for good.
                    if (localSentences.isNotEmpty()) {
                        synchronized(feedLock) {
                            val at = text.indexOf(sentenceText, serverCursor)
                            if (at >= 0) serverCursor = at + sentenceText.length
                            if (at >= 0 && at + sentenceText.length <= localEnd) return
                            serverTookOver = true
                        }
                    }

                    val audio = PendingAudio(sentenceText, words, data, meta.getInt("sample_rate"))
                    val enqueueNow = synchronized(session) {
                        if (session.live) true else { session.buffer.add(audio); false }
                    }
                    if (enqueueNow) svc.enqueueSentence(audio.text, audio.words, audio.pcm, audio.sampleRate)
                }

                override fun onFailure(error: Throwable) {
                    if (!isCurrent()) return // expected: abandonSessions()'s close() surfaces as a failure on the abandoned connection
                    Log.e("ReadAloudController", "websocket failed", error)
                    if (localSentences.isNotEmpty() && !serverTookOver) return // local TTS keeps reading
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
        liveMode = false
        stalledAtEnd = false
        onGenerating(false, 0L)
        abandonSessions()
        ttsService?.stopAll()
        // Explicit, synchronous notification - stopAll()'s own async
        // onQueueIdle callback (posted to mainHandler) checks `if (active)`
        // before firing onStateChanged(false), but `active` is already set
        // false above by the time that runs, so it silently never fires.
        // Confirmed live 2026-09-22: pressing the stop button never hid the
        // player bar because of exactly this - stop() has to tell the
        // caller itself, it can't rely on the service's own idle signal
        // here (that signal is for playback running out on its own, not
        // for a deliberate stop that already know the outcome).
        onStateChanged.invoke(false)
    }

    fun pause() = ttsService?.pause()
    fun resume() = ttsService?.resume()

    fun seekRelative(deltaMs: Long) {
        val svc = ttsService ?: return
        svc.seekTo((svc.getPositionMs() + deltaMs).coerceAtLeast(0))
    }

    /** For a scrubber bar -- see PlayerControlBar's own doc. Unlike
     * newsdigest-android's copy (one continuous article, so `fraction` maps
     * to a character offset), this app's sections are discrete chat
     * messages with no single linear text to map into -- position/duration
     * here are already a continuous ms timeline across every section
     * chained into the current session (TtsPlaybackService.allSentences
     * accumulates across streamCurrentSection() calls, only resetting on a
     * fresh startSession()), so `fraction` maps directly onto THAT instead,
     * through the same seekTo() skipRelative()/skipToNextSection() already
     * use. */
    fun seekToFraction(fraction: Float) {
        val svc = ttsService ?: return
        val dur = svc.getDisplayDurationMs()
        if (dur <= 0) return
        svc.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong())
    }

    fun getPositionMs(): Long = ttsService?.getPositionMs() ?: 0L
    fun getDurationMs(): Long = ttsService?.getDisplayDurationMs() ?: 0L

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
