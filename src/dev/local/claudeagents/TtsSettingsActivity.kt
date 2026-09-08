package dev.local.claudeagents

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Read-aloud config, reachable from MainActivity's overflow menu ("asked
 * for explicitly: add a setting window accessible via three dots menu").
 * Host is whatever this app is already paired against (TokenStore.getHost)
 * -- the shared TTS server (~/src/newsdigest-android/server/server.py)
 * runs on the same box, just a different port, so only the port + engine
 * choice need their own field here. Adapted from newsdigest-android's own
 * SettingsActivity, trimmed to just the TTS section (the rest of that
 * screen is feed/account config this app has no equivalent of).
 */
class TtsSettingsActivity : Activity() {

    private fun watch(field: EditText, onChanged: (String) -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onChanged(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()

        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Theme.bg)
        }

        fun spacer(dp: Int) = TextView(this).apply { setPadding(0, Theme.dp(this@TtsSettingsActivity, dp), 0, 0) }
        fun sectionLabel(label: String) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Theme.onSurfaceVariant)
        }

        root.addView(
            TextView(this).apply {
                text = "Read aloud"
                textSize = 22f
                setTextColor(Theme.onBackground)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "Uses the same TTS server news-digest talks to, on ${TokenStore.getHost(this@TtsSettingsActivity)}."
                textSize = 13f
                setTextColor(Theme.onSurfaceVariant)
                setPadding(0, Theme.dp(this@TtsSettingsActivity, 8), 0, Theme.dp(this@TtsSettingsActivity, 20))
            },
        )

        val portField = EditText(this).apply {
            hint = "TTS server port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(TtsSettings.getTtsPort(this@TtsSettingsActivity).toString())
            Theme.styleEditText(this, this@TtsSettingsActivity)
        }
        root.addView(portField)
        watch(portField) { it.trim().toIntOrNull()?.let { port -> TtsSettings.setTtsPort(this, port) } }

        root.addView(spacer(24))
        root.addView(sectionLabel("Engine"))
        root.addView(spacer(6))
        val engineGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val kokoroRadio = RadioButton(this).apply {
            text = "Kokoro (fast)"
            id = 1
            setTextColor(Theme.onBackground)
            buttonTintList = ColorStateList.valueOf(Theme.primary)
        }
        val chatterboxRadio = RadioButton(this).apply {
            text = "Chatterbox (natural)"
            id = 2
            setTextColor(Theme.onBackground)
            buttonTintList = ColorStateList.valueOf(Theme.primary)
            setPadding(Theme.dp(this@TtsSettingsActivity, 20), 0, 0, 0)
        }
        engineGroup.addView(kokoroRadio)
        engineGroup.addView(chatterboxRadio)
        root.addView(engineGroup)
        if (TtsSettings.getTtsEngine(this) == "chatterbox") chatterboxRadio.isChecked = true else kokoroRadio.isChecked = true
        engineGroup.setOnCheckedChangeListener { _, checkedId ->
            TtsSettings.setTtsEngine(this, if (checkedId == chatterboxRadio.id) "chatterbox" else "kokoro")
        }

        val engineStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.muted)
            setPadding(0, Theme.dp(this@TtsSettingsActivity, 8), 0, 0)
        }
        root.addView(engineStatus)
        checkEngineStatus(portField, engineStatus)

        root.addView(spacer(20))
        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.bg); addView(root) })
    }

    private fun checkEngineStatus(portField: EditText, statusView: TextView) {
        Thread {
            try {
                val host = TokenStore.getHost(this).trim()
                val port = portField.text.toString().trim().toIntOrNull() ?: return@Thread
                if (host.isEmpty()) return@Thread
                val url = java.net.URL("http://$host:$port/status")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("X-Peer-Agent", "1")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(body)
                val text = "kokoro: ${obj.optString("kokoro", "?")} · chatterbox: ${obj.optString("chatterbox", "?")}"
                runOnUiThread { statusView.text = text }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "TTS server unreachable — check port" }
            }
        }.apply { isDaemon = true }.start()
    }
}
