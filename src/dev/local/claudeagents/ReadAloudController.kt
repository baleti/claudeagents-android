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
 * ReadAloudController -- that one also drives an in-place word-highlight
 * over a full-article text view, which this app has no equivalent of (chat
 * bubbles, not one long scrollable article), so that half is dropped here;
 * everything else (session lifecycle, pause/resume/seek, audio-focus
 * handling, the WebSocket streaming loop itself) is the same proven
 * mechanism, reused rather than re-derived.
 */
class ReadAloudController(
    private val context: Context,
    private val onStateChanged: (playing: Boolean) -> Unit,
    // Fires on every real play/pause transition, from ANY trigger -- the
    // in-app controls, the system notification's own button, a seek,
    // audio-focus loss/gain.
    private val onPlayingChanged: (playing: Boolean) -> Unit = {},
) {
    private var ttsService: TtsPlaybackService? = null
    private var bound = false
    private var ws: WebSocketClient? = null
    @Volatile private var active = false
    @Volatile private var currentSpeed = 1.0f
    // Bumped every start(). A stale message from an abandoned stream can't
    // touch shared state after a new one has started (same guard
    // newsdigest's controller uses).
    @Volatile private var streamGeneration = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val playbackListener = object : TtsPlaybackService.HighlightListener {
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

    /** title shows in the media notification. text is read start-to-end,
     * sentence by sentence, as the server streams it back -- see
     * streamText() below. */
    fun start(title: String, text: String) {
        stop()
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
        svc.setPlaybackSpeed(currentSpeed)
        streamText(text, svc)
    }

    /** Connects to /tts/stream, sends `text`, and enqueues every sentence
     * that comes back onto `svc` as it arrives. */
    private fun streamText(text: String, svc: TtsPlaybackService) {
        val myGeneration = ++streamGeneration
        fun isCurrent() = streamGeneration == myGeneration

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
                }

                override fun onText(text: String) {
                    if (!isCurrent()) return
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "sentence" -> pendingMeta = obj
                        "done" -> svc.endSession()
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
                    if (!isCurrent()) return
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
}
