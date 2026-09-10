package dev.local.claudeagents

data class ConversationRow(
    val id: String,
    val account: String,
    val accountLabel: String,
    val title: String,
    val mtime: Double,
    val lineCount: Int,
    val tokens: Int?,
    val livePane: String?
) {
    val isLive: Boolean get() = livePane != null
}

data class MessageRow(
    val line: Int,
    val role: String,
    val text: String,
    val ts: String?,
    // "context_limit" | "rate_limit" | "api_error" | null -- set only for
    // role == "error" (the daemon's synthetic-turn tagging of a real
    // Claude Code API error, see claude-agents-daemon.py parse_transcript_line).
    val errorType: String? = null
)

// One tool call ("→ Bash" etc, possibly paired with its tool_result) or one
// standalone tool_result (a result whose call fell in a *previous* bundle,
// or whose call never synced) within a ToolBundle. Each renders as its own
// independently collapsible line inside the bundle's single outer bubble
// (asked for explicitly: "bundle them into one message that can be folded
// individually").
data class ToolCallItem(
    val callText: String,
    val resultText: String?,
    val isStandaloneResult: Boolean,
    // Transcript line number -- MessageAdapter's expanded/collapsed set
    // key, survives notifyDataSetChanged()/view recycling unlike position.
    val id: String
)

// A picked-and-sent image or file, rendered inline (image: decoded thumbnail;
// other mime types: a filename/size card with a Download action). localPath
// is this device's own private copy (survives even after the picker's
// original content:// URI is gone); attachmentId is the server's id, set
// once uploaded, used to re-fetch the bytes later (GET /api/v1/attachments/<id>)
// if the local copy is ever missing.
data class AttachmentInfo(
    val localPath: String?,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val caption: String,
    val attachmentId: String?
)

data class ChatDisplayRow(
    val role: String,
    val text: String,
    val status: String?, // null for synced messages, "sending…"/"queued on server" for outbox-only rows
    // Stable key for a plain (non-tool-bundle) row -- transcript line
    // number for synced rows, "outbox_<id>" for not-yet-synced ones.
    val id: String? = null,
    // Non-null => this row is a run of one-or-more consecutive tool calls/
    // results (ChatActivity's groupToolCallsWithResults), rendered as one
    // bubble by MessageAdapter with each item independently foldable. Null
    // for an ordinary human/assistant prose message.
    val toolItems: List<ToolCallItem>? = null,
    // Non-null => this row is a sent image/file attachment, rendered by
    // MessageAdapter as an image thumbnail or a file+Download card instead
    // of plain text.
    val attachment: AttachmentInfo? = null,
    // See MessageRow.errorType -- carried through so MessageAdapter can
    // render this row as a warning bubble instead of a normal one, and
    // (for "context_limit") offer a one-tap Compact action.
    val errorType: String? = null
)

data class OutboxRow(
    val id: Long,
    val conversationId: String,
    val text: String,
    val state: String,      // "pending" | "handed_off"
    val serverState: String?, // "delivered" | "queued" | null
    val createdAt: Long,
    val attempts: Int,
    // Generated once at insert time and resent unchanged on every retry --
    // lets the daemon recognize a retried POST (response lost after it was
    // already delivered/queued) as the same send instead of acting on it
    // twice (see claude-agents-daemon.py's record_send_result/lookup_send_result).
    val clientMsgId: String,
    // True for a message typed into a conversation opened from the Archive
    // view (no live tmux pane at open time) -- OutboxLogic posts these to
    // POST .../resume instead of .../send, which relaunches the session via
    // `claude --resume` if it's still not live. Stays true for the rest of
    // that ChatActivity's outbox rows even if the conversation goes live in
    // the meantime; the daemon's /resume handles that case by delivering
    // straight into the now-live pane instead of resuming a second time.
    val viaResume: Boolean = false
)

data class AttachmentOutboxRow(
    val id: Long,
    val conversationId: String,
    val localPath: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val caption: String,
    val state: String,        // "pending" | "handed_off"
    val serverState: String?, // "delivered" | "queued" | null
    val attachmentId: String?,
    val createdAt: Long,
    val attempts: Int,
    val clientMsgId: String
)
