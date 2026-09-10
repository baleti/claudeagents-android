package dev.local.claudeagents

import android.content.Context

/**
 * Per-conversation unsent draft text -- typed into ChatActivity's input
 * field but not yet sent, kept across leaving/returning to a chat (a
 * different conversation, backgrounding, or the app process being killed
 * outright) so it's never silently lost. Asked for explicitly 2026-09-09,
 * after an adb force-stop during testing dropped an in-progress draft:
 * "can you also remember what was typed in the input fields across app
 * runs? i sometimes write something then switch activity then come back".
 *
 * Saved on every keystroke (not just onPause) specifically because the
 * incident that prompted this was an abrupt kill (force-stop), which
 * skips onPause entirely -- a plain SharedPreferences write is cheap
 * enough to do per-keystroke for one short string. Plain
 * SharedPreferences, not the SQLite Db -- a draft is transient scratch
 * text, not data that needs Db's durability guarantees (WAL, migration
 * preservation, etc, see Db.kt's DURABLE_TABLES doc) the way an
 * already-sent outbox row does.
 */
object DraftStore {
    private const val PREFS = "claudeagents_drafts"

    fun get(context: Context, sessionId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sessionId, "") ?: ""

    fun set(context: Context, sessionId: String, text: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Don't let an empty conversation's key pile up forever across
        // hundreds of live conversations -- remove rather than store "".
        if (text.isEmpty()) {
            prefs.edit().remove(sessionId).apply()
        } else {
            prefs.edit().putString(sessionId, text).apply()
        }
    }
}
