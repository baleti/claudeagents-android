package dev.local.claudeagents

import android.content.Context

/**
 * Read-aloud config -- which TTS engine/voice to ask the shared TTS server
 * for (see ReadAloudController, ~/src/newsdigest-android/server/server.py).
 * Host reuses TokenStore.getHost() (same box, same WireGuard tunnel as the
 * claude-agents daemon -- just a different port); kept in its own prefs
 * file rather than folded into TokenStore's so TokenStore.clear() (used on
 * unpair) doesn't also wipe a voice/engine choice that has nothing to do
 * with pairing.
 */
object TtsSettings {
    private const val PREFS = "claudeagents_tts_prefs"
    private const val DEFAULT_TTS_PORT = 8792

    fun getTtsPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("tts_port", DEFAULT_TTS_PORT)

    fun setTtsPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("tts_port", port).apply()
    }

    fun getTtsEngine(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tts_engine", "kokoro") ?: "kokoro"

    fun setTtsEngine(context: Context, engine: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tts_engine", engine).apply()
    }

    // null means "let the server pick its own default voice for the
    // current engine" (see server.py's KokoroEngine.synthesize) -- most
    // engines/voices haven't been explicitly chosen by every user, and a
    // hardcoded fallback here could silently drift from the server's own.
    fun getTtsVoice(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tts_voice", null)

    fun setTtsVoice(context: Context, voice: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tts_voice", voice).apply()
    }
}
