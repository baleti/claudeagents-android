package dev.local.claudeagents

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.AbsListView
import android.widget.ListView
import kotlin.math.abs

// ListView with a draggable scroll thumb on its right edge, replacing the
// stock isFastScrollEnabled thumb. The stock one grabs on ACTION_DOWN and
// jumps the list immediately, so the start of an edge back-swipe (gesture
// navigation) scrolled the list before the system took the gesture over.
// This one never acts on DOWN: it engages only once the finger has moved
// past touch slop and mostly vertically, or on a genuine tap (UP with no
// real movement). Horizontal moves and CANCEL (the system claiming a back
// gesture) do nothing. Touches starting in the strip are consumed here, so
// they never reach row clicks or the pull-to-refresh listener.
class ScrollerListView(context: Context) : ListView(context) {
    private val density = context.resources.displayMetrics.density
    private val stripPx = 28 * density
    private val thumbW = 5 * density
    private val thumbMinH = 40 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var tracking = false // DOWN landed in the strip
    private var dragging = false
    private var grabOffset = 0f  // finger offset inside the thumb at engage time
    private var visibleUntil = 0L
    private val hideTick = Runnable { invalidate() }

    init {
        isVerticalScrollBarEnabled = false
        setOnScrollListener(object : OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                if (total > visible) poke()
            }
        })
    }

    private fun poke() {
        visibleUntil = System.currentTimeMillis() + 1200
        removeCallbacks(hideTick)
        postDelayed(hideTick, 1250)
        invalidate()
    }

    private fun scrollable(): Boolean = childCount > 0 && count > childCount

    // Scroll position as 0..1 (fractional, using the first row's offset).
    private fun fraction(): Float {
        val max = (count - childCount).toFloat()
        if (max <= 0f) return 0f
        val c = getChildAt(0) ?: return 0f
        val pos = firstVisiblePosition + (-c.top).toFloat() / c.height.coerceAtLeast(1)
        return (pos / max).coerceIn(0f, 1f)
    }

    private fun thumbH(): Float =
        (height.toFloat() * childCount / count.coerceAtLeast(1))
            .coerceIn(thumbMinH.coerceAtMost(height.toFloat()), height.toFloat())

    private fun thumbTop(): Float = fraction() * (height - thumbH())

    private fun scrollToFraction(f: Float) {
        val max = (count - childCount).toFloat()
        val target = f.coerceIn(0f, 1f) * max
        val idx = target.toInt()
        val rowH = getChildAt(0)?.height ?: 0
        setSelectionFromTop(idx, -((target - idx) * rowH).toInt())
    }

    private fun fractionForThumbTop(top: Float): Float {
        val room = height - thumbH()
        return if (room <= 0f) 0f else (top / room).coerceIn(0f, 1f)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowing = false
                tracking = scrollable() && ev.x >= width - stripPx
                dragging = false
                if (tracking) {
                    downX = ev.x
                    downY = ev.y
                    return true // claim it; act later
                }
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (!dragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        dragging = true
                        val top = thumbTop()
                        // Grab where the finger is on the thumb; if it's off
                        // the thumb, centre the thumb under the finger.
                        grabOffset = if (downY in top..(top + thumbH())) downY - top else thumbH() / 2
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        tracking = false // horizontal: a back swipe, not ours
                        swallowing = true
                    }
                }
                if (dragging) {
                    scrollToFraction(fractionForThumbTop(ev.y - grabOffset))
                    poke()
                }
                return true
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                if (!dragging && abs(ev.y - downY) <= slop && abs(ev.x - downX) <= slop) {
                    // True tap: jump so the thumb centres on the tap.
                    scrollToFraction(fractionForThumbTop(ev.y - thumbH() / 2))
                    poke()
                }
                tracking = false
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                dragging = false
                return true
            }
        }
        // A DOWN claimed above whose pointer later went horizontal: swallow
        // the rest of the gesture instead of handing a mid-gesture event
        // stream (no DOWN) to the list.
        return if (!tracking && ev.actionMasked != MotionEvent.ACTION_DOWN && swallowing) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            true
        } else super.dispatchTouchEvent(ev)
    }

    private var swallowing = false

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!scrollable()) return
        if (!dragging && System.currentTimeMillis() > visibleUntil) return
        val top = thumbTop()
        val right = width - 2 * density
        rect.set(right - thumbW, top, right, top + thumbH())
        paint.color = if (dragging) Theme.primary else Theme.outline
        paint.alpha = if (dragging) 230 else 160
        canvas.drawRoundRect(rect, thumbW / 2, thumbW / 2, paint)
    }
}
