package dev.local.claudeagents

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context) : SQLiteOpenHelper(context, "claudeagents.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                account TEXT,
                account_label TEXT,
                title TEXT,
                mtime REAL,
                line_count INTEGER,
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
                attempts INTEGER
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS conversations")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS outbox")
        onCreate(db)
    }

    fun upsertConversation(c: ConversationRow) {
        val cv = ContentValues().apply {
            put("id", c.id)
            put("account", c.account)
            put("account_label", c.accountLabel)
            put("title", c.title)
            put("mtime", c.mtime)
            put("line_count", c.lineCount)
            put("live_pane", c.livePane)
        }
        writableDatabase.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listConversations(): List<ConversationRow> {
        val out = mutableListOf<ConversationRow>()
        val cur = readableDatabase.rawQuery(
            "SELECT id, account, account_label, title, mtime, line_count, live_pane FROM conversations ORDER BY mtime DESC",
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
                        livePane = it.getString(6)
                    )
                )
            }
        }
        return out
    }

    fun deleteConversation(id: String) {
        writableDatabase.delete("conversations", "id = ?", arrayOf(id))
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
            "SELECT line, role, text, ts FROM messages WHERE conversation_id = ? ORDER BY line ASC",
            arrayOf(conversationId)
        )
        cur.use {
            while (it.moveToNext()) {
                out.add(MessageRow(it.getInt(0), it.getString(1), it.getString(2) ?: "", it.getString(3)))
            }
        }
        return out
    }

    fun insertOutbox(conversationId: String, text: String): Long {
        val cv = ContentValues().apply {
            put("conversation_id", conversationId)
            put("text", text)
            put("state", "pending")
            put("server_state", null as String?)
            put("created_at", System.currentTimeMillis())
            put("attempts", 0)
        }
        return writableDatabase.insert("outbox", null, cv)
    }

    fun listOutbox(conversationId: String): List<OutboxRow> {
        val out = mutableListOf<OutboxRow>()
        val cur = readableDatabase.rawQuery(
            "SELECT id, conversation_id, text, state, server_state, created_at, attempts FROM outbox WHERE conversation_id = ? ORDER BY id ASC",
            arrayOf(conversationId)
        )
        cur.use {
            while (it.moveToNext()) {
                out.add(
                    OutboxRow(
                        it.getLong(0), it.getString(1), it.getString(2), it.getString(3),
                        it.getString(4), it.getLong(5), it.getInt(6)
                    )
                )
            }
        }
        return out
    }

    fun listPendingOutbox(): List<OutboxRow> {
        val out = mutableListOf<OutboxRow>()
        val cur = readableDatabase.rawQuery(
            "SELECT id, conversation_id, text, state, server_state, created_at, attempts FROM outbox WHERE state = 'pending' ORDER BY id ASC",
            null
        )
        cur.use {
            while (it.moveToNext()) {
                out.add(
                    OutboxRow(
                        it.getLong(0), it.getString(1), it.getString(2), it.getString(3),
                        it.getString(4), it.getLong(5), it.getInt(6)
                    )
                )
            }
        }
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
}
