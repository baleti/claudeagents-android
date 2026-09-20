package dev.local.claudeagents

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
    const val ACTION_SYNC_COMPLETE = "dev.local.claudeagents.SYNC_COMPLETE"
    // Fired the instant a real sync round actually starts (not on a
    // coalesced no-op) -- MainActivity shows a "Syncing…" indicator between
    // this and the matching ACTION_SYNC_COMPLETE, so a background catch-up
    // sync is visible rather than silent (asked for explicitly: "if we are
    // not [in sync] we should know about it... showing some visual cue").
    const val ACTION_SYNC_STARTED = "dev.local.claudeagents.SYNC_STARTED"
    private const val TAG = "SyncLogic"

    // Guards against two performSync() calls actually running at once --
    // confirmed live 2026-09-07 that the periodic job, an immediate
    // "just opened the app" trigger, and a manual pull-to-refresh can all
    // land within the same few seconds ("SQLiteConnection leaked" warnings
    // when each independently opened Db(context); Db.getInstance() below
    // now shares one connection, but two full sync rounds at once is still
    // wasted work -- they'd both fetch the same 300+ conversations). A
    // coalesced call just reports the in-flight run's eventual result
    // rather than redoing the work.
    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun performSync(context: Context): Boolean {
        if (!TokenStore.isPaired(context)) {
            Log.w(TAG, "sync: not paired, skipping")
            return false
        }
        if (!syncing.compareAndSet(false, true)) {
            Log.i(TAG, "sync: already in progress, coalescing this trigger")
            return true
        }
        try {
            return performSyncLocked(context)
        } finally {
            syncing.set(false)
        }
    }

    private fun performSyncLocked(context: Context): Boolean {
        Log.i(TAG, "sync: starting, host=${TokenStore.getHost(context)}:${TokenStore.getPort(context)}")
        context.sendBroadcast(Intent(ACTION_SYNC_STARTED).setPackage(context.packageName))
        val client = RelayClient(context)
        val db = Db.getInstance(context)
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
            Log.i(TAG, "sync: fetched ${conversations.size} conversations from server")
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
            val pruned = db.pruneOrphanedMessages()
            if (pruned > 0) Log.i(TAG, "sync: reclaimed $pruned orphaned message rows (see deleteConversation's 2026-09-20 fix)")
            Log.i(TAG, "sync: done, local now has ${db.listConversations().size} conversations")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sync failed (server unreachable): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        val prefs = context.getSharedPreferences("claudeagents_prefs", Context.MODE_PRIVATE)
        val edit = prefs.edit().putBoolean("last_sync_ok", ok)
        // Only stamped on an actual successful round -- a failed attempt
        // shouldn't make the status line claim to be more current than it
        // is (asked for explicitly: show "last synced N ago" next to the
        // syncing indicator).
        if (ok) edit.putLong("last_sync_at", System.currentTimeMillis())
        edit.apply()
        context.sendBroadcast(Intent(ACTION_SYNC_COMPLETE).setPackage(context.packageName).putExtra("ok", ok))
        return ok
    }

    // Epoch millis of the last successful sync, or null if there's never
    // been one yet.
    fun lastSyncAt(context: Context): Long? {
        val v = context.getSharedPreferences("claudeagents_prefs", Context.MODE_PRIVATE)
            .getLong("last_sync_at", -1L)
        return if (v < 0) null else v
    }
}
