package dev.local.claudeagents

data class ConversationRow(
    val id: String,
    val account: String,
    val accountLabel: String,
    val title: String,
    val mtime: Double,
    val lineCount: Int,
    val livePane: String?
) {
    val isLive: Boolean get() = livePane != null
}

data class MessageRow(
    val line: Int,
    val role: String,
    val text: String,
    val ts: String?
)

data class ChatDisplayRow(
    val role: String,
    val text: String,
    val status: String? // null for synced messages, "sending…"/"queued on server" for outbox-only rows
)

data class OutboxRow(
    val id: Long,
    val conversationId: String,
    val text: String,
    val state: String,      // "pending" | "handed_off"
    val serverState: String?, // "delivered" | "queued" | null
    val createdAt: Long,
    val attempts: Int
)
