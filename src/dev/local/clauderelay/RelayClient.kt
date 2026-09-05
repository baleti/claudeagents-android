package dev.local.clauderelay

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
            conn.setRequestProperty("X-Claude-Relay-Token", token)
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
            conn.setRequestProperty("X-Claude-Relay-Token", token)
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

    fun send(sessionId: String, text: String): JSONObject {
        val body = JSONObject()
        body.put("text", text)
        return request("POST", "/api/v1/conversations/$sessionId/send", body)
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
                        ts = if (m.isNull("ts")) null else m.optString("ts")
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
                        livePane = live?.optString("pane")
                    )
                )
            }
            return out
        }
    }
}
