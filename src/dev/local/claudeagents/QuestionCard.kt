package dev.local.claudeagents

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

// Answer card for a pending AskUserQuestion, shown above the input row.
// The daemon reports the pending question on /stream and turns an answer
// into keystrokes for the live TUI (claude-agents-daemon.py answer_question).
// Rebuilt only when the question id changes, so polling never wipes a
// half-made selection or typed free text.
class QuestionCard(
    private val context: Context,
    private val onSubmit: (toolUseId: String, answers: JSONArray) -> Unit,
    private val onDismiss: (toolUseId: String) -> Unit
) {
    private class QState(val multi: Boolean, val optionCount: Int) {
        val selected = linkedSetOf<Int>()
        var other: String = ""
        fun answered() = selected.isNotEmpty() || other.isNotBlank()
    }

    val view = ScrollView(context)
    private val body = LinearLayout(context)
    private var currentId: String? = null
    private var states = listOf<QState>()
    private var submit: Button? = null
    private var busy = false
    private var doneId: String? = null
    private var rewindMode = false

    init {
        view.visibility = View.GONE
        view.setBackgroundColor(Theme.surface)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(14), dp(10), dp(14), dp(10))
        view.addView(body)
    }

    private fun dp(v: Int) = Theme.dp(context, v)

    fun maxHeightPx(): Int = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()

    fun update(q: JSONObject?) {
        val id = q?.optString("id")
        if (q == null || id.isNullOrEmpty()) {
            currentId = null
            busy = false
            view.visibility = View.GONE
            return
        }
        if (id == currentId || id == doneId) return
        currentId = id
        busy = false
        build(id, q.getJSONArray("questions"), q.optString("kind") == "rewind")
        view.layoutParams = (view.layoutParams as? LinearLayout.LayoutParams)
        view.visibility = View.VISIBLE
        view.post {
            val lp = view.layoutParams
            if (lp != null && body.height > maxHeightPx()) {
                lp.height = maxHeightPx()
                view.layoutParams = lp
            }
        }
    }

    // Answered/dismissed/gone: hide, and ignore this id while the daemon's
    // transcript catches up with the tool_result.
    fun done(id: String) {
        doneId = id
        update(null)
    }

    // Undo done() when an optimistic hide turns out to have failed.
    fun undo(id: String) {
        if (doneId == id) doneId = null
    }

    // Called after a failed submit/dismiss so the card is usable again.
    fun failed() {
        busy = false
        refreshSubmit()
    }

    private fun build(id: String, qs: JSONArray, rewind: Boolean = false) {
        rewindMode = rewind
        body.removeAllViews()
        view.layoutParams?.let { it.height = LinearLayout.LayoutParams.WRAP_CONTENT }
        val st = mutableListOf<QState>()
        val tag = TextView(context)
        tag.text = if (rewind) "Rewind" else "Claude is asking"
        tag.textSize = 11f
        tag.setTypeface(null, Typeface.BOLD)
        tag.setTextColor(Theme.primary)
        body.addView(tag)

        for (qi in 0 until qs.length()) {
            val q = qs.getJSONObject(qi)
            val opts = q.getJSONArray("options")
            val state = QState(q.optBoolean("multi"), opts.length())
            st.add(state)

            val title = TextView(context)
            val header = q.optString("header")
            title.text = if (header.isNotEmpty()) "$header · ${q.optString("question")}" else q.optString("question")
            title.textSize = 14f
            title.setTextColor(Theme.onBackground)
            title.setPadding(0, dp(10), 0, dp(4))
            body.addView(title)
            if (state.multi) {
                val hint = TextView(context)
                hint.text = "Select all that apply"
                hint.textSize = 11f
                hint.setTextColor(Theme.muted)
                body.addView(hint)
            }

            val rows = mutableListOf<TextView>()
            fun paint() {
                for ((i, r) in rows.withIndex()) {
                    val on = state.selected.contains(i + 1)
                    r.background = Theme.roundedDrawable(
                        if (on) Theme.surfaceContainer else Theme.bg, context,
                        strokeColor = if (on) Theme.primary else Theme.outlineVariant
                    )
                }
            }
            for (oi in 0 until opts.length()) {
                val o = opts.getJSONObject(oi)
                val r = TextView(context)
                val d = o.optString("description")
                val mark = if (state.multi) "☐ " else ""
                r.text = if (d.isNotEmpty()) "${o.optString("label")}\n$d" else o.optString("label")
                r.textSize = 13f
                r.setTextColor(Theme.onBackground)
                r.setPadding(dp(12), dp(9), dp(12), dp(9))
                rows.add(r)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(5)
                body.addView(r, lp)
                r.setOnClickListener {
                    if (busy) return@setOnClickListener
                    val n = oi + 1
                    if (state.multi) {
                        if (!state.selected.remove(n)) state.selected.add(n)
                    } else {
                        state.selected.clear()
                        state.selected.add(n)
                        state.other = ""
                    }
                    paint()
                    refreshSubmit()
                }
                if (mark.isEmpty()) Unit
            }
            paint()

            if (rewind) continue
            val other = EditText(context)
            Theme.styleEditText(other, context)
            other.hint = "Or type your own answer"
            other.textSize = 13f
            other.setSingleLine(true)
            other.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    state.other = s?.toString() ?: ""
                    if (!state.multi && state.other.isNotBlank()) {
                        state.selected.clear()
                        paint()
                    }
                    refreshSubmit()
                }
            })
            val olp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            olp.topMargin = dp(5)
            body.addView(other, olp)
        }
        states = st

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END
        val dismiss = Button(context)
        dismiss.text = if (rewind) "Cancel" else "Dismiss"
        dismiss.isAllCaps = false
        dismiss.setTextColor(Theme.onSurfaceVariant)
        dismiss.background = Theme.roundedDrawable(Theme.surfaceContainer, context)
        dismiss.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true
            refreshSubmit()
            onDismiss(id)
        }
        val ok = Button(context)
        ok.text = "Submit"
        ok.isAllCaps = false
        ok.setTypeface(null, Typeface.BOLD)
        ok.setOnClickListener {
            if (busy || !states.all { it.answered() }) return@setOnClickListener
            busy = true
            refreshSubmit()
            val arr = JSONArray()
            for (s in states) {
                arr.put(JSONObject().apply {
                    put("selected", JSONArray(s.selected.toList()))
                    put("other", s.other.trim().ifEmpty { JSONObject.NULL })
                })
            }
            onSubmit(id, arr)
        }
        submit = ok
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
        blp.marginStart = dp(8)
        actions.addView(dismiss, blp)
        actions.addView(ok, LinearLayout.LayoutParams(blp))
        val alp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        alp.topMargin = dp(10)
        body.addView(actions, alp)
        refreshSubmit()
    }

    private fun refreshSubmit() {
        val b = submit ?: return
        val ready = !busy && states.isNotEmpty() && states.all { it.answered() }
        b.isEnabled = ready
        b.text = if (busy) "Sending…" else if (rewindMode) "Rewind" else "Submit"
        b.setTextColor(if (ready) Theme.onPrimary else Theme.muted)
        b.background = Theme.roundedDrawable(if (ready) Theme.primary else Theme.surfaceContainer, context)
    }
}
