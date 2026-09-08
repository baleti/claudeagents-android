package dev.local.claudeagents

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
    const val ACTION_OUTBOX_CHANGED = "dev.local.claudeagents.OUTBOX_CHANGED"
    private const val TAG = "OutboxLogic"

    fun drainOutbox(context: Context): Boolean {
        if (!TokenStore.isPaired(context)) return false
        val db = Db.getInstance(context)
        val client = RelayClient(context)
        var allOk = true

        val pending = db.listPendingOutbox()
        for (row in pending) {
            try {
                val resp = client.send(row.conversationId, row.text, row.clientMsgId)
                val serverState = if (resp.optBoolean("delivered", false)) "delivered" else "queued"
                db.markOutboxHandedOff(row.id, serverState)
            } catch (e: Exception) {
                Log.w(TAG, "send failed for outbox ${row.id}: ${e.message}")
                db.bumpOutboxAttempt(row.id)
                allOk = false
            }
        }

        // Same drain loop, same per-row failure isolation, for picked
        // files/images -- reads the durable local copy (never the picker's
        // original URI, see ChatActivity.handlePickedAttachment) and
        // uploads it with the same idempotent client_msg_id convention.
        val pendingAttachments = db.listPendingAttachmentOutbox()
        for (row in pendingAttachments) {
            try {
                val file = java.io.File(row.localPath)
                if (!file.isFile) {
                    // The local copy is gone (e.g. cleared app storage
                    // mid-retry) with nothing left to upload -- mark it
                    // handed off as failed-permanently rather than retry
                    // forever against a file that will never come back.
                    Log.w(TAG, "attachment ${row.id} local file missing, giving up: ${row.localPath}")
                    db.markAttachmentHandedOff(row.id, "failed", null)
                    continue
                }
                val bytes = file.readBytes()
                val resp = client.uploadAttachment(row.conversationId, row.filename, row.mimeType, bytes, row.caption, row.clientMsgId)
                val serverState = if (resp.optBoolean("delivered", false)) "delivered" else "queued"
                db.markAttachmentHandedOff(row.id, serverState, resp.optString("attachment_id", "").ifEmpty { null })
            } catch (e: Exception) {
                Log.w(TAG, "attachment upload failed for ${row.id}: ${e.message}")
                db.bumpAttachmentAttempt(row.id)
                allOk = false
            }
        }

        context.sendBroadcast(Intent(ACTION_OUTBOX_CHANGED).setPackage(context.packageName))
        return allOk
    }
}
