package dev.local.claudeagents

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.EditText

/**
 * Snapshot of the desktop's generated Material-You-ish scheme
 * (~/.local/state/quickshell/scheme.json, gen-theme.py) at the time this
 * app was built -- there's no live link to the desktop from the phone, so
 * this is a fixed copy rather than something that re-themes itself. Matches
 * ~/.config/quickshell/theme/Theme.qml's naming so the two are easy to
 * compare by eye.
 */
object Theme {
    const val bg = 0xFF201A17.toInt()
    const val surface = 0xFF2A221E.toInt()          // bg lifted slightly for cards/rows
    const val surfaceContainer = 0xFF52443C.toInt()
    const val onBackground = 0xFFECE0DA.toInt()
    const val onSurfaceVariant = 0xFFD7C2B8.toInt()
    const val outline = 0xFF9F8D84.toInt()
    const val outlineVariant = 0xFF52443C.toInt()
    const val primary = 0xFFFFB68A.toInt()
    const val onPrimary = 0xFF522300.toInt()
    const val secondary = 0xFFFFB77C.toInt()
    const val error = 0xFFFFB4AB.toInt()
    const val errorContainer = 0xFF4A1410.toInt()   // dark fill for the offline banner, error as its text/border
    const val live = 0xFF75C359.toInt()             // seriesPalette green -- "this session is live"
    const val muted = 0xFF8A7A70.toInt()

    // Syntax-highlight palette, pulled straight from the same generated
    // seriesPalette (scheme.json) the desktop uses for CPU-core/network-
    // interface series colors -- keeps hand-rolled code highlighting
    // visually part of the same theme rather than an unrelated fixed set.
    const val syntaxKeyword = 0xFF99ADFF.toInt() // seriesPalette[5]
    const val syntaxString = 0xFF75C359.toInt()  // seriesPalette[2]
    const val syntaxNumber = 0xFF00BEF6.toInt()  // seriesPalette[4]

    const val roundingDp = 10
    const val paddingDp = 14
    const val spacingDp = 8

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun roundedDrawable(color: Int, context: Context, radiusDp: Int = roundingDp, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(context, 1), strokeColor)
        return d
    }

    fun rippleOn(base: GradientDrawable): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(outline and 0x66FFFFFF.toInt()), base, base)

    fun styleEditText(e: EditText, context: Context) {
        e.setTextColor(onBackground)
        e.setHintTextColor(muted)
        e.background = roundedDrawable(surface, context, strokeColor = outlineVariant)
        e.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        try {
            e.highlightColor = primary and 0x55FFFFFF.toInt()
        } catch (t: Throwable) {
        }
    }

    fun stylePrimaryButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(primary, context))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }

    fun styleGhostButton(view: View, context: Context) {
        view.background = rippleOn(roundedDrawable(Color.TRANSPARENT, context, strokeColor = outlineVariant))
        val pad = dp(context, 12)
        view.setPadding(pad, dp(context, 10), pad, dp(context, 10))
    }
}
