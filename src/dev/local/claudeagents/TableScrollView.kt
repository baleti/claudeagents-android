package dev.local.claudeagents

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs

// Horizontal scroller for wide markdown tables. The chat screen's
// SwipeNavFrame (and the ListView above this) intercept drags on their own,
// and a parent's onInterceptTouchEvent runs *before* the child ever sees the
// first MOVE -- so without help, dragging a table sideways switched
// conversation instead of panning it. On DOWN we therefore claim the gesture
// (when there's actually something to scroll), then hand it back as soon as
// it turns out to be vertical (ListView scrolls) or a horizontal drag
// pushing past either end of the table (conversation swipe still works).
class TableScrollView(context: Context) : HorizontalScrollView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var decided = false

    private fun overflows(): Boolean {
        val child = getChildAt(0) ?: return false
        return child.width > width - paddingLeft - paddingRight
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                decided = false
                parent?.requestDisallowInterceptTouchEvent(overflows())
            }
            MotionEvent.ACTION_MOVE -> if (!decided) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop || abs(dy) > slop) {
                    decided = true
                    val horizontal = abs(dx) > abs(dy)
                    // Finger moving right (dx>0) scrolls content toward the left edge.
                    val canScroll = horizontal && canScrollHorizontally(if (dx > 0) -1 else 1)
                    parent?.requestDisallowInterceptTouchEvent(canScroll)
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
