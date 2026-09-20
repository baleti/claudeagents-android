package dev.local.claudeagents

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File

// Android's *default* DatabaseErrorHandler silently deletes and recreates
// the whole database file the instant SQLite reports corruption -- no
// backup, no log a user would ever see, every table gone (not just
// outbox). Confirmed live as the likely explanation for a reported-
// critical bug (2026-09-08): messages that had genuinely queued (visible,
// "sending…") vanished completely -- not marked failed, not still
// pending, just absent -- after backgrounding and returning to the app.
// Nothing in this codebase ever deletes an outbox row or the DB file (grep
// confirms it), so total, silent loss of already-committed data points at
// exactly this default behavior, most likely triggered by the same root
// cause as the sibling silent-insert-failure bug: four separate
// SQLiteOpenHelper connections (pre-singleton, see Db.getInstance below)
// writing to one file with no coordination beyond SQLite's own file
// locking, under the heavy concurrent retry churn a spotty connection
// produces. This handler can't un-corrupt a database, but it refuses to
// do so *silently* -- the corrupt file is copied aside first so there's
// at least forensic evidence and a chance of manual recovery, and the
// event is logged loudly instead of vanishing without a trace.
private class NonDestructiveErrorHandler : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase) {
        val path = dbObj.path
        Log.e("Db", "SQLite corruption detected at $path -- backing up before recreating")
        try {
            dbObj.close()
        } catch (e: Exception) {
        }
        try {
            if (path != null) {
                val src = File(path)
                if (src.exists()) {
                    val backup = File("$path.corrupt.${System.currentTimeMillis()}")
                    src.copyTo(backup, overwrite = true)
                    Log.e("Db", "Corrupt database backed up to ${backup.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e("Db", "Failed to back up corrupt database: ${e.message}")
        }
        if (path != null) SQLiteDatabase.deleteDatabase(File(path))
    }
}

// Version 7: adds outbox.via_resume (Archive view -- see ArchiveActivity/
// ChatActivity's archivedOrigin -- marks a row for POST .../resume instead
// of .../send). outbox IS a DURABLE_TABLES entry, so onUpgrade's generic
// preserve-and-backfill path (below) carries existing pending rows across
// this migration; they backfill to via_resume=0 (plain /send), which is
// correct -- a message that was already pending under the old schema was
// never typed into an archived conversation, since that flow didn't exist
// yet.
class Db private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "claudeagents.db", null, 7, NonDestructiveErrorHandler()
) {
    init {
        // WAL mode: readers (the poll thread, sync, list queries) never
        // block behind a writer (an outbox insert) and vice versa -- real
        // extra hardening on top of the singleton fix below, since
        // multiple *threads* still legitimately touch this one connection
        // concurrently even with only one SQLiteOpenHelper instance now.
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        // MainActivity, ChatActivity, SyncLogic and OutboxLogic each used to
        // construct their own Db(context) -- four independent SQLiteOpenHelper
        // instances, each holding its own SQLiteDatabase connection to the
        // *same* underlying file. SQLiteDatabase synchronizes concurrent
        // access from multiple threads correctly within one connection, but
        // separate connections only coordinate through SQLite's own file-
        // level locking, which can hand back SQLITE_BUSY under contention --
        // and plain insert()/update() (as opposed to insertOrThrow()) swallow
        // that and just return -1/0 rather than throwing. Confirmed as the
        // likely cause of a reported-critical bug (2026-09-08): on a spotty
        // connection, tapping Send could make the message "disappear as if
        // nothing was sent" -- not that the pending bubble appeared then got
        // cleared, but that db.insertOutbox()'s write silently never landed,
        // and nothing downstream ever finds out. A spotty connection makes
        // this far more likely than a cleanly offline one: it produces much
        // more concurrent retry churn (poll thread, periodic sync, outbox
        // drain, an immediate outbox trigger all racing shortly after each
        // other) hitting the four separate connections at once, instead of
        // failing fast and going quiet. One shared instance (this
        // getInstance()) removes the cross-connection race entirely --
        // everything now goes through the one SQLiteDatabase object, which
        // handles its own thread-safety internally.
        @Volatile private var instance: Db? = null

        fun getInstance(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                account TEXT,
                account_label TEXT,
                title TEXT,
                mtime REAL,
                line_count INTEGER,
                tokens INTEGER,
                live_pane TEXT
            )"""
        )
        db.execSQL(
            """CREATE TABLE messages (
                conversation_id TEXT,
                line INTEGER,
                role TEXT,
                text TEXT,
                ts TEXT,
                error_type TEXT,
                PRIMARY KEY (conversation_id, line)
            )"""
        )
        db.execSQL(
            """CREATE TABLE outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT,
                text TEXT,
                state TEXT,
                server_state TEXT,
                created_at INTEGER,
                attempts INTEGER,
                client_msg_id TEXT,
                via_resume INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE attachment_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT,
                local_path TEXT,
                filename TEXT,
                mime_type TEXT,
                size INTEGER,
                caption TEXT,
                state TEXT,
                server_state TEXT,
                attachment_id TEXT,
                created_at INTEGER,
                attempts INTEGER,
                client_msg_id TEXT
            )"""
        )
    }

    // Tables that must survive a schema migration with their rows intact --
    // each is the *only* local record that something the user did (typed a
    // message, picked a file to send) exists at all, for as long as the
    // server hasn't confirmed it. Asked for explicitly as a hard
    // requirement: "under no circumstances messages queued to be sent from
    // here are to be lost, they always must stay until they are
    // successfully accepted by the server". conversations/messages are not
    // in this list -- they're just a re-fetchable cache of what the server
    // already has, always safe to drop and let the next sync repopulate.
    private val DURABLE_TABLES = listOf("outbox", "attachment_outbox")

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val preserved = HashMap<String, MutableList<ContentValues>>()
        for (table in DURABLE_TABLES) {
            val rows = mutableListOf<ContentValues>()
            if (tableExists(db, table)) {
                db.query(table, null, null, null, null, null, null).use { c ->
                    while (c.moveToNext()) {
                        val cv = ContentValues()
                        android.database.DatabaseUtils.cursorRowToContentValues(c, cv)
                        rows.add(cv)
                    }
                }
            }
            preserved[table] = rows
        }
        db.execSQL("DROP TABLE IF EXISTS conversations")
        db.execSQL("DROP TABLE IF EXISTS messages")
        for (table in DURABLE_TABLES) db.execSQL("DROP TABLE IF EXISTS $table")
        onCreate(db)
        for (table in DURABLE_TABLES) {
            for (cv in preserved[table].orEmpty()) {
                // Backfill any column this migration just added that an
                // older row wouldn't have (e.g. client_msg_id, added in the
                // v2->v3 bump) rather than let insertOrThrow reject the
                // whole row for missing one field.
                if (!cv.containsKey("client_msg_id") || cv.getAsString("client_msg_id").isNullOrEmpty()) {
                    cv.put("client_msg_id", java.util.UUID.randomUUID().toString())
                }
                try {
                    db.insertOrThrow(table, null, cv)
                } catch (e: Exception) {
                    // Only reachable if a *future* migration removes a
                    // column with no safe default -- logged loudly rather
                    // than silently dropped, since these tables must never
                    // lose a row without a trace.
                    Log.e("Db", "Failed to preserve $table row across migration to v$newVersion: ${e.message} row=$cv")
                }
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
            return it.moveToFirst()
        }
    }

    fun upsertConversation(c: ConversationRow) {
        val cv = ContentValues().apply {
            put("id", c.id)
            put("account", c.account)
            put("account_label", c.accountLabel)
            put("title", c.title)
            put("mtime", c.mtime)
            put("line_count", c.lineCount)
            if (c.tokens != null) put("tokens", c.tokens) else putNull("tokens")
            put("live_pane", c.livePane)
        }
        writableDatabase.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // Single-row lookup -- used to check whether the conversation a chat
    // screen is showing is still live, e.g. to give an outbox row's status
    // label something truthful to say once the session it was delivered
    // into has since ended (see ChatActivity's outbox status computation).
    fun getConversation(id: String): ConversationRow? {
        readableDatabase.rawQuery(
            "SELECT id, account, account_label, title, mtime, line_count, tokens, live_pane FROM conversations WHERE id = ?",
            arrayOf(id)
        ).use {
            if (!it.moveToFirst()) return null
            return ConversationRow(
                id = it.getString(0),
                account = it.getString(1),
                accountLabel = it.getString(2),
                title = it.getString(3) ?: "",
                mtime = it.getDouble(4),
                lineCount = it.getInt(5),
                tokens = if (it.isNull(6)) null else it.getInt(6),
                livePane = it.getString(7)
            )
        }
    }

    fun listConversations(): List<ConversationRow> {
        val out = mutableListOf<ConversationRow>()
        val cur = readableDatabase.rawQuery(
            "SELECT id, account, account_label, title, mtime, line_count, tokens, live_pane FROM conversations ORDER BY mtime DESC",
            null
        )
        cur.use {
            while (it.moveToNext()) {
                out.add(
                    ConversationRow(
                        id = it.getString(0),
                        account = it.getString(1),
                        accountLabel = it.getString(2),
                        title = it.getString(3) ?: "",
                        mtime = it.getDouble(4),
                        lineCount = it.getInt(5),
                        tokens = if (it.isNull(6)) null else it.getInt(6),
                        livePane = it.getString(7)
                    )
                )
            }
        }
        return out
    }

    fun deleteConversation(id: String) {
        // messages was never actually cleaned up here -- a conversation
        // routinely disappears from the server's own list (renamed,
        // compacted, session ended) as normal churn across 300+
        // conversations, and every single one of those permanently
        // orphaned its message rows: gone from `conversations` so never
        // shown anywhere again, but never deleted from `messages` either.
        // Confirmed live 2026-09-20 while investigating "i hope it is not
        // accumulating stale data" -- it was. messages is documented
        // (DURABLE_TABLES's own comment) as "just a re-fetchable cache...
        // always safe to drop", so this is a pure cleanup with no data-
        // loss risk. outbox/attachment_outbox are deliberately NOT
        // touched here -- those are the durable "never lose a pending
        // send" tables and must survive independent of whether the
        // conversation row they reference still exists.
        writableDatabase.delete("messages", "conversation_id = ?", arrayOf(id))
        writableDatabase.delete("conversations", "id = ?", arrayOf(id))
    }

    /** One-time (well, every sync -- cheap once caught up) retroactive
     * cleanup for message rows orphaned by deleteConversation()'s
     * pre-2026-09-20 version, which never deleted from `messages` at
     * all. Safe for the same reason deleteConversation()'s own cleanup
     * is: `messages` is just a re-fetchable cache. Returns how many rows
     * were actually reclaimed, purely for logging. */
    fun pruneOrphanedMessages(): Int {
        return writableDatabase.delete(
            "messages",
            "conversation_id NOT IN (SELECT id FROM conversations)",
            null,
        )
    }

    fun getMaxLine(conversationId: String): Int {
        val cur = readableDatabase.rawQuery(
            "SELECT MAX(line) FROM messages WHERE conversation_id = ?", arrayOf(conversationId)
        )
        cur.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getInt(0) + 1
        }
        return 0
    }

    fun insertMessages(conversationId: String, msgs: List<MessageRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in msgs) {
                val cv = ContentValues().apply {
                    put("conversation_id", conversationId)
                    put("line", m.line)
                    put("role", m.role)
                    put("text", m.text)
                    put("ts", m.ts)
                    put("error_type", m.errorType)
                }
                db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listMessages(conversationId: String): List<MessageRow> {
        val out = mutableListOf<MessageRow>()
        val cur = readableDatabase.rawQuery(
            "SELECT line, role, text, ts, error_type FROM messages WHERE conversation_id = ? ORDER BY line ASC",
            arrayOf(conversationId)
        )
        cur.use {
            while (it.moveToNext()) {
                out.add(MessageRow(it.getInt(0), it.getString(1), it.getString(2) ?: "", it.getString(3), it.getString(4)))
            }
        }
        return out
    }

    fun insertOutbox(conversationId: String, text: String, viaResume: Boolean = false): Long {
        val cv = ContentValues().apply {
            put("conversation_id", conversationId)
            put("text", text)
            put("state", "pending")
            put("server_state", null as String?)
            put("created_at", System.currentTimeMillis())
            put("attempts", 0)
            put("client_msg_id", java.util.UUID.randomUUID().toString())
            put("via_resume", if (viaResume) 1 else 0)
        }
        // insertOrThrow, not insert() -- plain insert() swallows a write
        // failure and just returns -1, which is exactly how a message the
        // user just sent could vanish with zero trace anywhere (see the
        // singleton note above). This is the one write in the whole app
        // that must never fail silently: it's the only record that a send
        // was even attempted before the network is ever touched.
        return writableDatabase.insertOrThrow("outbox", null, cv)
    }

    private val OUTBOX_COLUMNS = "id, conversation_id, text, state, server_state, created_at, attempts, client_msg_id, via_resume"

    private fun cursorToOutboxRow(c: android.database.Cursor): OutboxRow = OutboxRow(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
        c.getString(4), c.getLong(5), c.getInt(6), c.getString(7), c.getInt(8) != 0
    )

    fun listOutbox(conversationId: String): List<OutboxRow> {
        val out = mutableListOf<OutboxRow>()
        readableDatabase.rawQuery(
            "SELECT $OUTBOX_COLUMNS FROM outbox WHERE conversation_id = ? ORDER BY id ASC",
            arrayOf(conversationId)
        ).use { c -> while (c.moveToNext()) out.add(cursorToOutboxRow(c)) }
        return out
    }

    fun listPendingOutbox(): List<OutboxRow> {
        val out = mutableListOf<OutboxRow>()
        readableDatabase.rawQuery(
            "SELECT $OUTBOX_COLUMNS FROM outbox WHERE state = 'pending' ORDER BY id ASC",
            null
        ).use { c -> while (c.moveToNext()) out.add(cursorToOutboxRow(c)) }
        return out
    }

    fun markOutboxHandedOff(id: Long, serverState: String) {
        val cv = ContentValues().apply {
            put("state", "handed_off")
            put("server_state", serverState)
        }
        writableDatabase.update("outbox", cv, "id = ?", arrayOf(id.toString()))
    }

    fun markOutboxDelivered(id: Long) {
        writableDatabase.update(
            "outbox", ContentValues().apply { put("server_state", "delivered") },
            "id = ?", arrayOf(id.toString())
        )
    }

    fun bumpOutboxAttempt(id: Long) {
        writableDatabase.execSQL("UPDATE outbox SET attempts = attempts + 1 WHERE id = ?", arrayOf(id.toString()))
    }

    // Retires a "delivered" outbox row once its content has actually shown
    // up as a real synced message -- see ChatActivity.reconcileDeliveredOutbox
    // for why "the daemon accepted it" and "it's visible anywhere" are not
    // the same moment, and must not be treated as if they were.
    fun deleteOutboxRow(id: Long) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id.toString()))
    }

    // local_path points at a copy already made in this app's own private
    // storage at pick time (see ChatActivity) -- never the picker's
    // original content:// URI, which can become unreadable the moment the
    // source app's own permission grant or cache entry goes away. Same
    // "durable before the network is ever touched" contract text sends
    // already have.
    fun insertAttachmentOutbox(
        conversationId: String, localPath: String, filename: String,
        mimeType: String, size: Long, caption: String
    ): Long {
        val cv = ContentValues().apply {
            put("conversation_id", conversationId)
            put("local_path", localPath)
            put("filename", filename)
            put("mime_type", mimeType)
            put("size", size)
            put("caption", caption)
            put("state", "pending")
            put("server_state", null as String?)
            put("attachment_id", null as String?)
            put("created_at", System.currentTimeMillis())
            put("attempts", 0)
            put("client_msg_id", java.util.UUID.randomUUID().toString())
        }
        return writableDatabase.insertOrThrow("attachment_outbox", null, cv)
    }

    private fun cursorToAttachmentOutboxRow(c: android.database.Cursor): AttachmentOutboxRow = AttachmentOutboxRow(
        id = c.getLong(0), conversationId = c.getString(1), localPath = c.getString(2),
        filename = c.getString(3), mimeType = c.getString(4), size = c.getLong(5),
        caption = c.getString(6) ?: "", state = c.getString(7), serverState = c.getString(8),
        attachmentId = c.getString(9), createdAt = c.getLong(10), attempts = c.getInt(11),
        clientMsgId = c.getString(12)
    )

    private val ATTACHMENT_OUTBOX_COLUMNS = "id, conversation_id, local_path, filename, mime_type, size, caption, state, server_state, attachment_id, created_at, attempts, client_msg_id"

    fun listAttachmentOutbox(conversationId: String): List<AttachmentOutboxRow> {
        val out = mutableListOf<AttachmentOutboxRow>()
        readableDatabase.rawQuery(
            "SELECT $ATTACHMENT_OUTBOX_COLUMNS FROM attachment_outbox WHERE conversation_id = ? ORDER BY id ASC",
            arrayOf(conversationId)
        ).use { c -> while (c.moveToNext()) out.add(cursorToAttachmentOutboxRow(c)) }
        return out
    }

    fun listPendingAttachmentOutbox(): List<AttachmentOutboxRow> {
        val out = mutableListOf<AttachmentOutboxRow>()
        readableDatabase.rawQuery(
            "SELECT $ATTACHMENT_OUTBOX_COLUMNS FROM attachment_outbox WHERE state = 'pending' ORDER BY id ASC",
            null
        ).use { c -> while (c.moveToNext()) out.add(cursorToAttachmentOutboxRow(c)) }
        return out
    }

    fun markAttachmentHandedOff(id: Long, serverState: String, attachmentId: String?) {
        val cv = ContentValues().apply {
            put("state", "handed_off")
            put("server_state", serverState)
            if (attachmentId != null) put("attachment_id", attachmentId)
        }
        writableDatabase.update("attachment_outbox", cv, "id = ?", arrayOf(id.toString()))
    }

    fun bumpAttachmentAttempt(id: Long) {
        writableDatabase.execSQL("UPDATE attachment_outbox SET attempts = attempts + 1 WHERE id = ?", arrayOf(id.toString()))
    }
}
