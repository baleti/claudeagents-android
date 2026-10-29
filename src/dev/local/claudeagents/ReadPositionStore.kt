package dev.local.claudeagents

import android.content.Context

/**
 * Where "Read aloud from here" last got to, per conversation: the row id of the
 * message being spoken. TtsPlaybackService holds the whole read in memory, so
 * when Android kills the process or destroys the service after a long idle
 * pause the read (and the player bar) vanished with it. This survives that:
 * ChatActivity shows the player bar again, paused, and play restarts from the
 * saved message. Cleared only by an explicit Stop, never by the service dying.
 */
object ReadPositionStore {
    private const val PREFS = "claudeagents_read_position"

    fun get(context: Context, sessionId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sessionId, null)

    fun set(context: Context, sessionId: String, rowId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(sessionId, rowId).apply()
    }

    fun clear(context: Context, sessionId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(sessionId).apply()
    }
}
