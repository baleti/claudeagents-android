package dev.local.claudeagents

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

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
    // Deliberately darker than every other surface tone (bg/surface/
    // surfaceContainer are all a warm brown within a few shades of each
    // other) plus a bright primary-colored border -- showMenu() reused
    // surfaceContainer at first and it just blended into the message
    // bubbles behind it (reported live: "stylize this popup differently,
    // make it contrast more with rest of ui").
    const val popupSurface = 0xFF120D0B.toInt()

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

    // Small themed dropdown menu -- android.widget.PopupMenu ignores this
    // app's colors entirely and renders as the platform's default light
    // popup (reported live 2026-09-08: "just a plain white rectangle list"
    // against the rest of this dark-themed, hand-built-widgets app), and
    // there's no XML style resource in this project to hand it via the
    // popupTheme constructor either -- this app has no res/values at all
    // (every view is built in code, same as CommandRowAdapter/SpeedPicker/
    // PlayerControlBar). A plain PopupWindow over a rounded, dark
    // LinearLayout matches those existing hand-built popups instead.
    fun showMenu(context: Context, anchor: View, items: List<String>, onSelect: (String) -> Unit) {
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL
        container.background = roundedDrawable(popupSurface, context, radiusDp = 12, strokeColor = primary)

        val popup = PopupWindow(context)
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        // Transparent window background so the container's own rounded
        // corners actually show instead of being clipped to a square by
        // PopupWindow's default opaque one.
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = dp(context, 12).toFloat()

        for ((i, item) in items.withIndex()) {
            if (i > 0) {
                val divider = View(context)
                divider.setBackgroundColor(primary and 0x33FFFFFF.toInt())
                container.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)))
            }
            val row = TextView(context)
            row.text = item
            row.textSize = 14f
            row.setTypeface(null, Typeface.BOLD)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setTextColor(primary)
            row.setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14))
            row.background = rippleOn(roundedDrawable(Color.TRANSPARENT, context, radiusDp = 0))
            row.isClickable = true
            row.setOnClickListener {
                popup.dismiss()
                onSelect(item)
            }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        popup.contentView = container
        popup.width = LinearLayout.LayoutParams.WRAP_CONTENT
        popup.height = LinearLayout.LayoutParams.WRAP_CONTENT

        // showAsDropDown(anchor) alone always opens straight downward with
        // no fallback -- tapping a message near the bottom of the screen
        // left this popup clipped against the screen/keyboard edge with
        // only its first row actually visible (reported live 2026-09-10:
        // "only copy message shows up, there is no read aloud message
        // below it... i think it needs to be higher"). Measure the real
        // content height first and open upward (bottom-aligned to the
        // anchor's top) instead whenever there isn't room below it.
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = container.measuredHeight
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val visibleFrame = Rect()
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        val spaceBelow = visibleFrame.bottom - (anchorLoc[1] + anchor.height)
        val yOffset = if (popupHeight > spaceBelow) -(anchor.height + popupHeight) else 0
        popup.showAsDropDown(anchor, 0, yOffset)
    }
}
