package dev.local.claudeagents

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Hand-rolled slide-in-from-left navigation drawer -- no androidx
 * DrawerLayout available in this build (see build.sh's own doc: no
 * dependency resolver, platform SDK + Kotlin stdlib only). Opens via a
 * drag starting near the left edge of the screen (asked for explicitly
 * 2026-09-10: "a list on the left that can be shown by dragging from
 * left screen border"), or programmatically via open(); closes by
 * tapping the dimmed scrim, dragging left while open, or close().
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
        private val edgeZonePx = Theme.dp(ctx, 24)
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
                    eligible = opened || ev.x <= edgeZonePx
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

        // Without this, a gesture-navigation Android build's own "swipe
        // from the very edge = go back" system gesture claims the touch
        // first and this view's onInterceptTouchEvent above never even
        // sees it -- confirmed live 2026-09-10: an edge swipe just
        // finished the Activity (system Back) instead of opening the
        // drawer. This is the documented fix (View.setSystemGestureExclusionRects,
        // API 29+, matching this app's own minSdk) for exactly this "app
        // has its own edge-swipe UI" conflict -- it tells the system to
        // hand touches starting in this strip to the app instead of
        // treating them as the system gesture.
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (changed && height > 0) {
                systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, edgeZonePx, height))
            }
        }
    }

    // Persistent, always-visible grab tab on the left edge -- asked for
    // explicitly 2026-09-10: an edge-swipe alone was hard to land
    // reliably and kept triggering the system's own back gesture instead
    // (same edge, same gesture shape - see ShellView.onLayout's exclusion-
    // rect fix, which reduces but evidently doesn't fully eliminate that
    // conflict for every real touch). A plain TAP on this handle sidesteps
    // the conflict entirely - the system back gesture only ever fires for
    // a swipe, never a tap - so this is the reliable path; the handle
    // stays draggable too since it's just an ordinary part of the
    // ShellView's touch area.
    private val handle = View(context).apply {
        val radius = Theme.dp(context, 10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Theme.primary)
            cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
        }
        alpha = 0.75f
        isClickable = true
        setOnClickListener { if (opened) close() else open() }
    }

    init {
        scrim.setOnClickListener { close() }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(drawerPanel, FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT))
        val handleParams = FrameLayout.LayoutParams(Theme.dp(context, 10), Theme.dp(context, 56))
        handleParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        root.addView(handle, handleParams)
    }

    private fun setProgress(translationX: Float) {
        val clamped = translationX.coerceIn(-widthPx.toFloat(), 0f)
        drawerPanel.translationX = clamped
        val progress = 1f + clamped / widthPx // 0 (closed) .. 1 (fully open)
        scrim.visibility = if (progress > 0f) View.VISIBLE else View.GONE
        scrim.alpha = progress * 0.5f
        handle.alpha = 0.75f * (1f - progress)
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
        val startHandleAlpha = handle.alpha
        val targetHandleAlpha = if (targetScrimAlpha > 0f) 0f else 0.75f
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 220
        animator.addUpdateListener { a ->
            val f = a.animatedValue as Float
            drawerPanel.translationX = startTranslation + (targetTranslation - startTranslation) * f
            scrim.alpha = startAlpha + (targetScrimAlpha - startAlpha) * f
            handle.alpha = startHandleAlpha + (targetHandleAlpha - startHandleAlpha) * f
        }
        if (onEnd != null) {
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
        }
        animator.start()
    }
}
