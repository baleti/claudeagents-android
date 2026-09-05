package dev.local.clauderelay

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Shared by SyncJobService (periodic, background) and MainActivity's manual
 * refresh (immediate, foreground) so there's one sync code path. Pulls the
 * conversation list, then incrementally pulls new messages for anything
 * whose server line_count has grown past what's cached locally -- this is
 * the rsync-hourly substitute (see plan deviation #1).
 */
object SyncLogic {
    const val ACTION_SYNC_COMPLETE = "dev.local.clauderelay.SYNC_COMPLETE"
    private const val TAG = "SyncLogic"

    fun performSync(context: Context): Boolean {
        if (!TokenStore.isPaired(context)) return false
        val client = RelayClient(context)
        val db = Db(context)
        // "Reachable" (what the offline banner reflects) means the
        // conversation list itself came back -- that's the only thing that
        // actually indicates the server is unreachable. A single conversation's
        // message fetch failing (404: the session was renamed/compacted/
        // removed between the list scan and this fetch -- normal churn
        // across 300 live conversations, not a connectivity problem; other
        // codes/timeouts: transient, retried next round) must NOT flip the
        // whole sync to "offline" -- confirmed live: a couple of stale ids
        // 404ing every round pinned the banner permanently even though
        // every other request was succeeding.
        val ok = try {
            val conversations = RelayClient.parseConversations(client.getConversations())
            // Prune local rows for anything that's vanished from the
            // server list entirely (a deleted/renamed session) -- the
            // per-item 404 handling below only catches a conversation that
            // still appears in the list but fails on its message fetch;
            // one that's gone from the list outright was never silently
            // cleaned up at all until this loop, confirmed live 2026-09-05
            // (a couple of removed test fixtures kept showing up locally,
            // permanently pinned at "0s" old, since nothing ever pruned
            // them).
            val serverIds = conversations.map { it.id }.toSet()
            for (localId in db.listConversations().map { it.id }) {
                if (localId !in serverIds) db.deleteConversation(localId)
            }
            for (c in conversations) {
                try {
                    db.upsertConversation(c)
                    val localMax = db.getMaxLine(c.id)
                    if (c.lineCount > localMax) {
                        val msgs = RelayClient.parseMessages(client.getMessages(c.id, localMax))
                        if (msgs.isNotEmpty()) db.insertMessages(c.id, msgs)
                    }
                } catch (e: RelayException) {
                    if (e.code == 404) {
                        db.deleteConversation(c.id)
                    }
                    Log.w(TAG, "sync: skipping ${c.id} this round: ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "sync: skipping ${c.id} this round: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "sync failed (server unreachable): ${e.message}")
            false
        }
        context.getSharedPreferences("clauderelay_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("last_sync_ok", ok).apply()
        context.sendBroadcast(Intent(ACTION_SYNC_COMPLETE).setPackage(context.packageName).putExtra("ok", ok))
        return ok
    }
}
