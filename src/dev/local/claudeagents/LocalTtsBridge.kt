package dev.local.claudeagents

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.UUID

/**
 * Instant, on-device fallback for the very first sentence of a session -
 * asked for explicitly 2026-09-22/23 ("a fast model can start and
 * chatterbox pick up once it's ready"): Chatterbox can take 15-20s to
 * cold-load (see server.py's GPU arbiter work), which otherwise reads as
 * dead silence with zero feedback. Renders to a file (synthesizeToFile),
 * not the device speaker directly, so the result feeds into the SAME
 * TtsPlaybackService queue chatterbox's own audio uses - one playback
 * pipeline, not two, so seeking/highlighting/pause all keep working
 * uniformly regardless of which engine actually produced a given sentence.
 *
 * Google's on-device engine (com.google.android.tts) is the only one
 * confirmed to actually initialize on this hardware/OS combo as of
 * 2026-09-23 - GrapheneOS's own privacy-respecting engine
 * (app.grapheneos.speechservices) is installed but fails to init
 * (status=-1), apparently needing a manual voice-data download through
 * Settings this class has no way to trigger. Both engines were also
 * DISABLED at the OS level by default here - a normal app has no
 * permission to enable one for the user, so this class degrades to a
 * complete no-op (isReady() stays false forever) rather than assuming
 * on-device TTS is available anywhere. Measured live: ~200-400ms to
 * first audio once warmed up, vs Chatterbox's cold-load or even kokoro's
 * ~2-3s network round trip.
 *
 * Single-use per instance by design - only ever asked to cover one
 * sentence, right at the start of a read, not a general-purpose queue.
 */
class LocalTtsBridge(private val context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    // Engine init finished but unusable (no engine / no installed voice) -
    // lets callers stop waiting for readiness that will never come.
    @Volatile private var failed = false

    companion object {
        @Volatile private var instance: LocalTtsBridge? = null

        /** One bridge per process, kept warm across conversations: engine
         * init takes ~2.7s, and a per-screen instance meant opening a chat
         * and tapping Read aloud within a few seconds found it not ready
         * yet, so the read silently went without the local bridge. */
        fun shared(context: Context): LocalTtsBridge =
            instance ?: synchronized(this) {
                instance ?: LocalTtsBridge(context.applicationContext).also { instance = it }
            }
    }

    /** Ready now, or still initialising and likely to be soon. */
    fun isUsable(): Boolean = ready || (tts != null && !failed)

    /** Blocks (call off the main thread) until ready, init failed, or the timeout passes. */
    fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!ready && !failed && System.nanoTime() < deadline) Thread.sleep(50)
        return ready
    }

    /** Call well before synthesize() is ever likely to be needed - engine
     * init alone measured ~2.7s the first time an app process asks for it
     * (a one-time OS-level bind, not a per-utterance cost), so this should
     * happen at bind()-time, not at the moment the user presses play. */
    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context, { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val voice = pickInstalledLocalVoice(engine.voices ?: emptySet())
                if (voice != null) {
                    engine.voice = voice
                    ready = true
                } else failed = true
            } else failed = true
        }, "com.google.android.tts")
    }

    fun isReady(): Boolean = ready

    /** Synthesizes `text` to a temp WAV file and hands the raw PCM back via
     * `onResult(pcmBytes, sampleRateHz)` - or `onResult(null, 0)` if this
     * bridge isn't usable or synthesis failed for any reason. Callers treat
     * that exactly like "no bridge available", never as a hard error - the
     * real server stream is always coming regardless. */
    fun synthesize(text: String, onResult: (ByteArray?, Int) -> Unit) {
        val engine = tts
        if (!ready || engine == null) {
            onResult(null, 0)
            return
        }
        val file = File.createTempFile("ttsbridge", ".wav", context.cacheDir)
        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId2: String?) {}
            override fun onDone(utteranceId2: String?) {
                if (utteranceId2 != utteranceId) return
                val (pcm, sr) = readWavPcm(file)
                file.delete()
                if (sr > 0) onResult(pcm, sr) else onResult(null, 0)
            }
            @Deprecated("deprecated in the platform API but still the callback signature on this SDK")
            override fun onError(utteranceId2: String?) {
                if (utteranceId2 != utteranceId) return
                file.delete()
                onResult(null, 0)
            }
        })
        val result = engine.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS) onResult(null, 0)
    }

    /** Prefers an installed, on-device (not network-dependent) en_GB
     * voice - matches this codebase's own audience, and confirmed live
     * 2026-09-23 every en_GB "-local" voice on this device is already
     * installed (no download needed), unlike most other locales. Falls
     * back to any installed local en_* voice if none. */
    private fun pickInstalledLocalVoice(voices: Set<Voice>): Voice? {
        fun installedLocal(v: Voice) = !v.isNetworkConnectionRequired && !v.features.contains("notInstalled")
        return voices.filter { it.locale.language == "en" && it.locale.country == "GB" && installedLocal(it) }
            .minByOrNull { it.name }
            ?: voices.filter { it.locale.language == "en" && installedLocal(it) }.minByOrNull { it.name }
    }

    /** Minimal WAV parse (RIFF/fmt /data chunk walk, not just a fixed
     * 44-byte header offset - synthesizeToFile's exact header layout isn't
     * a documented guarantee) - returns the raw PCM samples and the real
     * sample rate from the fmt chunk. */
    private fun readWavPcm(file: File): Pair<ByteArray, Int> {
        val bytes = file.readBytes()
        fun u32(off: Int) = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or ((bytes[off + 3].toInt() and 0xFF) shl 24)
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            return ByteArray(0) to 0
        }
        var pos = 12
        var sampleRate = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = u32(pos + 4)
            val bodyStart = pos + 8
            when (chunkId) {
                "fmt " -> sampleRate = u32(bodyStart + 4)
                "data" -> { dataOffset = bodyStart; dataSize = chunkSize }
            }
            pos = bodyStart + chunkSize + (chunkSize and 1) // chunks are word-aligned
        }
        if (dataOffset < 0 || sampleRate == 0) return ByteArray(0) to 0
        val end = minOf(bytes.size, dataOffset + dataSize)
        return bytes.copyOfRange(dataOffset, end) to sampleRate
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}


/** What the on-device TTS should actually say for a sentence: the server
 * strips markdown before synthesizing (text_clean.markdown_to_speech), but
 * the local bridge speaks the client's own text, so without this it read
 * "##Recommended" as "hash hash recommended" (also asterisks, backticks,
 * bullet dashes, raw URLs). Display/highlight text stays untouched - only
 * what is passed to the engine changes. */
internal fun speakableForLocalTts(s: String): String {
    var t = s
    t = t.replace(Regex("(?m)(^|\\s)#{2,6}\\s*"), "\$1")
    t = t.replace(Regex("(?m)^\\s*#\\s+"), "")
    t = t.replace(Regex("(?m)^\\s*[-*\u2022]\\s+"), "")
    t = t.replace(Regex("\\*{1,3}|`+"), "")
    t = t.replace(Regex("https?://\\S+"), "link")
    t = t.replace(Regex("\\s+"), " ").trim()
    return if (t.isEmpty()) s else t
}
