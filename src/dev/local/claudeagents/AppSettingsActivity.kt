package dev.local.claudeagents

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * App-wide behavior settings, reachable from MainActivity's overflow menu
 * -- asked for explicitly 2026-09-11, alongside the back-button choice
 * this screen's own RadioGroup controls. Separate from TtsSettingsActivity
 * (read-aloud is its own concern) and from AppSettings itself, same split
 * as TtsSettings/TtsSettingsActivity.
 */
class AppSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()

        val pad = Theme.dp(this, 20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Theme.bg)
        }

        fun spacer(dp: Int) = TextView(this).apply { setPadding(0, Theme.dp(this@AppSettingsActivity, dp), 0, 0) }
        fun sectionLabel(label: String) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Theme.onSurfaceVariant)
        }

        root.addView(
            TextView(this).apply {
                text = "Settings"
                textSize = 22f
                setTextColor(Theme.onBackground)
            },
        )

        root.addView(spacer(20))
        root.addView(sectionLabel("Back button, in a conversation"))
        root.addView(spacer(6))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val historyRadio = RadioButton(this).apply {
            text = "Walk back through the conversations actually visited"
            id = 1
            setTextColor(Theme.onBackground)
            buttonTintList = ColorStateList.valueOf(Theme.primary)
        }
        val mainMenuRadio = RadioButton(this).apply {
            text = "Always go straight to the main conversation list"
            id = 2
            setTextColor(Theme.onBackground)
            buttonTintList = ColorStateList.valueOf(Theme.primary)
            setPadding(0, Theme.dp(this@AppSettingsActivity, 10), 0, 0)
        }
        group.addView(historyRadio)
        group.addView(mainMenuRadio)
        root.addView(group)
        if (AppSettings.getBackToMainMenu(this)) mainMenuRadio.isChecked = true else historyRadio.isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            AppSettings.setBackToMainMenu(this, checkedId == mainMenuRadio.id)
        }

        root.addView(spacer(20))
        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.bg); addView(root) })
    }
}
