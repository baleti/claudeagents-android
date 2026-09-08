package dev.local.claudeagents

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sticky media-style control row shown for the whole time a read-aloud
 * session is active - rewind/play-pause/forward plus a speed picker,
 * replacing the old single "Stop" action's all-or-nothing control.
 * Speed lives here now, as its own icon, rather than behind the action
 * bar's "..." overflow. Placed above the scrolling content by the
 * caller (like SynthesizingBanner) so it stays visible regardless of
 * scroll position.
 */
class PlayerControlBar(
    context: Context,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onSpeedClick: (anchor: View) -> Unit,
) {
    private val playPauseButton: TextView
    private val speedButton: TextView
    val view: LinearLayout

    init {
        fun iconButton(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = if (primary) 20f else 16f
            setTextColor(if (primary) Theme.onPrimary else Theme.onBackground)
            gravity = Gravity.CENTER
            val padH = Theme.dp(context, 14)
            val padV = Theme.dp(context, 10)
            setPadding(padH, padV, padH, padV)
            background = Theme.rippleOn(
                Theme.roundedDrawable(if (primary) Theme.primary else Color.TRANSPARENT, context, radiusDp = 20),
            )
            setOnClickListener { onClick() }
        }

        val rewindButton = iconButton("⏪ 15") { onRewind() }
        playPauseButton = iconButton("⏸", primary = true) { onPlayPause() }
        val forwardButton = iconButton("15 ⏩") { onForward() }
        speedButton = iconButton("1x") { onSpeedClick(speedButton) }

        view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Theme.surface)
            val padH = Theme.dp(context, 12)
            val padV = Theme.dp(context, 8)
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
            addView(rewindButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(playPauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(forwardButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(speedButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private var playing = false

    fun show() { view.visibility = View.VISIBLE }
    fun hide() { view.visibility = View.GONE }
    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        playPauseButton.text = if (isPlaying) "⏸" else "▶"
    }
    fun isPlayingIcon(): Boolean = playing
    fun setSpeed(speed: Float) { speedButton.text = SpeedPicker.formatSpeed(speed) }
}
