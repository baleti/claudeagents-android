package dev.local.claudeagents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Hand-rolled slide-in-from-left navigation drawer -- no androidx
 * DrawerLayout available in this build (see build.sh's own doc: no
 * dependency resolver, platform SDK + Kotlin stdlib only). Opens via
 * open() (the caller wires a tap on a title-bar icon to this -- see
 * ChatActivity; an edge-swipe-to-open gesture was tried first but
 * asked to be replaced 2026-09-11 after repeatedly losing the race
 * against the system's own edge-swipe-back gesture). Closes by tapping
 * the dimmed scrim, dragging left while already open, or close().
 *
 * Usage: build one per Activity, hand it the screen's normal UI via
 * setContent() and whatever goes in the sliding panel via
 * setDrawerContent(), then `setContentView(drawer.root)` instead of the
 * content view directly.
 */
class SwipeDrawer(context: Context, drawerWidthDp: Int = 300) {
    private val widthPx = Theme.dp(context, drawerWidthDp)
    private var opened = false
    var onOpen: () -> Unit = {}

    private val scrim = View(context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        visibility = View.GONE
    }

    private val drawerPanel = FrameLayout(context).apply {
        setBackgroundColor(Theme.surface)
        elevation = Theme.dp(context, 8).toFloat()
        translationX = -Theme.dp(context, drawerWidthDp).toFloat()
    }

    val root: ShellView = ShellView(context)

    /** The actual touch-intercepting root. A plain FrameLayout +
     * setOnTouchListener only ever sees a gesture if no child underneath
     * already claimed it, which is no good for "swipe from the edge over
     * whatever's on screen" -- this overrides onInterceptTouchEvent
     * instead (the same mechanism ScrollView itself uses to steal a
     * vertical drag away from its children), so a real horizontal drag
     * starting in the edge zone (or anywhere, once the drawer is already
     * open) gets claimed away from underlying content, while a plain tap
     * always passes through untouched. */
    inner class ShellView(ctx: Context) : FrameLayout(ctx) {
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startTranslation = 0f
        private var dragging = false
        private var eligible = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    dragging = false
                    // Only a drag starting while the drawer is already open
                    // is eligible (drag-to-close) -- opening is a tap on the
                    // title-bar icon now, never a drag, so there's no edge
                    // zone to intercept and no more conflict with the
                    // system's own edge-swipe-back gesture.
                    eligible = opened
                    startTranslation = drawerPanel.translationX
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!eligible || dragging) return dragging
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                    }
                }
                else -> {}
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!dragging) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> setProgress(startTranslation + (ev.x - downX))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (drawerPanel.translationX > -widthPx / 2f) open() else close()
                }
                else -> {}
            }
            return true
        }
    }

    init {
        scrim.setOnClickListener { close() }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(drawerPanel, FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun setProgress(translationX: Float) {
        val clamped = translationX.coerceIn(-widthPx.toFloat(), 0f)
        drawerPanel.translationX = clamped
        val progress = 1f + clamped / widthPx // 0 (closed) .. 1 (fully open)
        scrim.visibility = if (progress > 0f) View.VISIBLE else View.GONE
        scrim.alpha = progress * 0.5f
    }

    // Inserted at index 0 -- underneath the scrim/drawer siblings added in
    // init{} above -- so it's always the bottom-most (background) layer
    // regardless of call order.
    fun setContent(view: View) {
        root.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setDrawerContent(view: View) {
        drawerPanel.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun isOpen(): Boolean = opened

    fun open() {
        opened = true
        scrim.visibility = View.VISIBLE
        onOpen.invoke()
        animateTo(0f, 0.5f)
    }

    fun close() {
        opened = false
        animateTo(-widthPx.toFloat(), 0f) { scrim.visibility = View.GONE }
    }

    private fun animateTo(targetTranslation: Float, targetScrimAlpha: Float, onEnd: (() -> Unit)? = null) {
        val startTranslation = drawerPanel.translationX
        val startAlpha = scrim.alpha
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 220
        animator.addUpdateListener { a ->
            val f = a.animatedValue as Float
            drawerPanel.translationX = startTranslation + (targetTranslation - startTranslation) * f
            scrim.alpha = startAlpha + (targetScrimAlpha - startAlpha) * f
        }
        if (onEnd != null) {
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
        }
        animator.start()
    }
}
