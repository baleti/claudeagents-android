package dev.local.claudeagents

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class RelayException(val code: Int, message: String) : IOException(message)

/**
 * Thin HttpURLConnection wrapper -- no OkHttp, no androidx: the Termux
 * build has no dependency resolver, so only java.net + org.json (both
 * part of the plain Android SDK) are available. See RelayClient's callers
 * for the offline/queue behavior; this class only knows how to talk to
 * one request at a time and surface failures as exceptions.
 */
class RelayClient(context: Context) {
    private val host = TokenStore.getHost(context)
    private val port = TokenStore.getPort(context)
    private val token = TokenStore.getToken(context) ?: ""

    private fun request(method: String, path: String, body: JSONObject?, readTimeoutMs: Int = 15000): JSONObject {
        val url = URL("http://$host:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("X-Claude-Agents-Token", token)
            conn.connectTimeout = 8000
            conn.readTimeout = readTimeoutMs
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                val os: OutputStream = conn.outputStream
                os.write(bytes)
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RelayException(code, "HTTP $code: $text")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun requestArray(method: String, path: String): JSONArray {
        val url = URL("http://$host:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("X-Claude-Agents-Token", token)
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RelayException(code, "HTTP $code: $text")
            }
            return JSONArray(text)
        } finally {
            conn.disconnect()
        }
    }

    fun getAccounts(): JSONArray = requestArray("GET", "/api/v1/accounts")

    fun getConversations(): JSONArray = requestArray("GET", "/api/v1/conversations")

    fun getMessages(sessionId: String, since: Int): JSONObject =
        request("GET", "/api/v1/conversations/$sessionId/messages?since=$since", null)

    fun stream(sessionId: String, since: Int, timeoutSec: Int = 25): JSONObject =
        request(
            "GET",
            "/api/v1/conversations/$sessionId/stream?since=$since&timeout=$timeoutSec",
            null,
            readTimeoutMs = (timeoutSec + 10) * 1000
        )

    fun getQueue(sessionId: String): JSONObject =
        request("GET", "/api/v1/conversations/$sessionId/queue", null)

    // Starts a brand-new tmux+claude session on host3 under the given
    // account's CLAUDE_CONFIG_DIR (the daemon's own spawn_session -- same
    // `tmux new-session ... claude --dangerously-skip-permissions
    // --session-id <uuid>` primitive host3's other per-account launch
    // points already use) and primes it with `text`. Response carries the
    // new session_id once the transcript file exists (daemon-side wait, up
    // to ~15s) -- readTimeoutMs generous to match.
    fun spawn(account: String, text: String): JSONObject {
        val body = JSONObject()
        body.put("account", account)
        body.put("text", text)
        return request("POST", "/api/v1/spawn", body, readTimeoutMs = 25000)
    }

    // `id` is the outbox row's stable client_msg_id, resent unchanged on
    // every retry -- lets the daemon recognize a retry of an already-
    // handled send (its 200 response lost after delivery, e.g. the tunnel
    // dropped right after) and replay that result instead of typing the
    // same text into the live session or queuing it a second time.
    fun send(sessionId: String, text: String, id: String): JSONObject {
        val body = JSONObject()
        body.put("text", text)
        body.put("id", id)
        return request("POST", "/api/v1/conversations/$sessionId/send", body)
    }

    // Archive view's equivalent of send() -- for a conversation with no
    // live tmux pane, the daemon relaunches it via `claude --resume
    // <sessionId>` in a fresh tmux session (same cwd/account it originally
    // used) and primes it with `text`, rather than queuing the text at
    // nothing (see claude-agents-daemon.py resume_session). If the
    // conversation turns out to already be live by the time this lands,
    // the daemon just delivers into the real pane instead, same as send().
    // Generous timeout to match spawn(): a resumed session has a whole
    // prior transcript to reload before its first prompt is interactive.
    fun resume(sessionId: String, text: String, id: String): JSONObject {
        val body = JSONObject()
        body.put("text", text)
        body.put("id", id)
        return request("POST", "/api/v1/conversations/$sessionId/resume", body, readTimeoutMs = 25000)
    }

    // Same base64-in-JSON shape the daemon expects (see claude-agents-daemon.py --
    // deliberately not multipart, a hand-rolled multipart parser is itself a
    // common source of bugs; base64 via android.util.Base64 (platform, not a
    // third-party library) keeps the whole API uniformly JSON). `id` is the
    // same idempotent client_msg_id convention `send` uses. Generous timeout:
    // a 25MB file base64-inflates to ~33MB, real upload time on a slow link.
    fun uploadAttachment(sessionId: String, filename: String, mimeType: String, data: ByteArray, caption: String, id: String): JSONObject {
        val body = JSONObject()
        body.put("filename", filename)
        body.put("mime_type", mimeType)
        body.put("data_base64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
        body.put("caption", caption)
        body.put("id", id)
        return request("POST", "/api/v1/conversations/$sessionId/attachments", body, readTimeoutMs = 90000)
    }

    class DownloadedAttachment(val filename: String, val mimeType: String, val data: ByteArray)

    fun downloadAttachment(attachmentId: String): DownloadedAttachment {
        val resp = request("GET", "/api/v1/attachments/$attachmentId", null, readTimeoutMs = 90000)
        val data = android.util.Base64.decode(resp.getString("data_base64"), android.util.Base64.DEFAULT)
        return DownloadedAttachment(
            filename = resp.optString("filename", "file"),
            mimeType = resp.optString("mime_type", "application/octet-stream"),
            data = data
        )
    }

    companion object {
        fun parseMessages(obj: JSONObject): List<MessageRow> {
            val out = mutableListOf<MessageRow>()
            val arr = obj.optJSONArray("messages") ?: return out
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                out.add(
                    MessageRow(
                        line = m.getInt("line"),
                        role = m.getString("role"),
                        text = m.optString("text", ""),
                        ts = if (m.isNull("ts")) null else m.optString("ts"),
                        errorType = if (m.isNull("error_type")) null else m.optString("error_type")
                    )
                )
            }
            return out
        }

        fun parseConversations(arr: JSONArray): List<ConversationRow> {
            val out = mutableListOf<ConversationRow>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val live = c.optJSONObject("live")
                out.add(
                    ConversationRow(
                        id = c.getString("id"),
                        account = c.optString("account", "unknown"),
                        accountLabel = c.optString("account_label", "unknown"),
                        title = c.optString("title", "(untitled)"),
                        mtime = c.optDouble("mtime", 0.0),
                        lineCount = c.optInt("line_count", 0),
                        tokens = if (c.isNull("tokens")) null else c.optInt("tokens"),
                        livePane = live?.optString("pane")
                    )
                )
            }
            return out
        }
    }
}
