package dev.local.clauderelay

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Drains locally-pending outbox rows by POSTing them to the daemon. A row
 * is marked handed_off as soon as the POST succeeds, whether the daemon
 * delivered it live (tmux send-keys) or queued it server-side for when the
 * conversation's tmux pane reappears -- from the app's perspective the
 * message has left the device either way, which is what "pending" tracks.
 * On failure the row stays pending and the JobScheduler backoff / next
 * periodic tick retries it -- nothing is ever dropped.
 */
object OutboxLogic {
    const val ACTION_OUTBOX_CHANGED = "dev.local.clauderelay.OUTBOX_CHANGED"
    private const val TAG = "OutboxLogic"

    fun drainOutbox(context: Context): Boolean {
        if (!TokenStore.isPaired(context)) return false
        val db = Db(context)
        val pending = db.listPendingOutbox()
        if (pending.isEmpty()) return true
        val client = RelayClient(context)
        var allOk = true
        for (row in pending) {
            try {
                val resp = client.send(row.conversationId, row.text)
                val serverState = if (resp.optBoolean("delivered", false)) "delivered" else "queued"
                db.markOutboxHandedOff(row.id, serverState)
            } catch (e: Exception) {
                Log.w(TAG, "send failed for outbox ${row.id}: ${e.message}")
                db.bumpOutboxAttempt(row.id)
                allOk = false
            }
        }
        context.sendBroadcast(Intent(ACTION_OUTBOX_CHANGED).setPackage(context.packageName))
        return allOk
    }
}
