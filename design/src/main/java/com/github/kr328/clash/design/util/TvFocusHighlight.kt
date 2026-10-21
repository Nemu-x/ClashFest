package com.github.kr328.clash.design.util

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.Shapeable

/**
 * One focus indicator for the whole TV UI: a soft accent-tinted fill plus a colorPrimary ring that
 * follows the D-pad focus, takes the focused control's own corner shape (pill buttons, segmented
 * halves, round power orb, rounded cards and rows) and glides between controls.
 *
 * It is drawn in the window's decor overlay, so no layout needs its own focus drawable and the
 * look is identical on every screen and dialog that installs it. Television only: [install] is a
 * no-op elsewhere, so phones keep their current visuals.
 */
object TvFocusHighlight {
    fun install(window: Window) {
        val decor = window.decorView
        if (!decor.context.isTelevision()) return
        if (decor.getTag(R.id.tv_focus_halo) != null) return
        decor.setTag(R.id.tv_focus_halo, FocusHalo(decor))
    }
}

private class FocusHalo(private val root: View) {
    private val density = root.resources.displayMetrics.density
    private val stroke = 3f * density
    private val gap = 2f * density
    private val fallbackRadius = 14f * density

    private val drawable = HaloDrawable()
    private var target: View? = null
    private var windowFocused = true

    private val shown = RectF()
    private var shownRadii = FloatArray(8)
    private val from = RectF()
    private var fromRadii = FloatArray(8)
    private val to = RectF()
    private var toRadii = FloatArray(8)
    private var visible = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 160
        interpolator = DecelerateInterpolator(1.6f)
        addUpdateListener {
            val t = it.animatedFraction
            shown.set(
                lerp(from.left, to.left, t),
                lerp(from.top, to.top, t),
                lerp(from.right, to.right, t),
                lerp(from.bottom, to.bottom, t),
            )
            for (i in 0 until 8) shownRadii[i] = lerp(fromRadii[i], toRadii[i], t)
            drawable.invalidateSelf()
        }
    }

    init {
        root.overlay.add(drawable)
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            drawable.setBounds(0, 0, v.width, v.height)
        }
        drawable.setBounds(0, 0, root.width, root.height)
        val observer = root.viewTreeObserver
        observer.addOnGlobalFocusChangeListener { _, newFocus -> retarget(newFocus) }
        observer.addOnPreDrawListener(
            ViewTreeObserver.OnPreDrawListener {
                follow()
                true
            },
        )
        observer.addOnWindowFocusChangeListener { hasFocus ->
            windowFocused = hasFocus
            drawable.invalidateSelf()
            if (hasFocus) focusFirstIfNone()
        }
        observer.addOnGlobalLayoutListener { focusFirstIfNone() }
        root.findFocus()?.let(::retarget)
    }

    /**
     * A screen whose layout asks for no initial focus (a plain list of cards, a list filled in
     * later) opened with nothing focused, so the first D-pad press only woke focus up somewhere.
     * Once the window has focus and is laid out, the first focusable in reading order gets it.
     * Screens that place focus themselves (Home's power orb, the picker's selected node) already
     * hold it by then and are left alone.
     */
    private fun focusFirstIfNone() {
        if (focusPosted || root.isInTouchMode || !root.hasWindowFocus() || !nothingFocused()) return
        focusPosted = true
        root.post {
            focusPosted = false
            if (root.isInTouchMode || !root.hasWindowFocus() || !nothingFocused()) return@post
            val focused = root.findFocus()
            if (focused is ViewGroup) {
                FocusFinder.getInstance().findNextFocus(focused, null, View.FOCUS_DOWN)?.let {
                    if (it.requestFocus()) return@post
                }
            }
            root.requestFocus(View.FOCUS_DOWN)
        }
    }

    /** Nothing, or only a scrolling container that has controls of its own (no ring, no place). */
    private fun nothingFocused(): Boolean {
        val focused = root.findFocus() ?: return true
        val container = focused is RecyclerView || focused is ScrollView || focused is NestedScrollView
        return container && FocusFinder.getInstance().findNextFocus(focused as ViewGroup, null, View.FOCUS_DOWN) != null
    }

    private var focusPosted = false

    private fun retarget(view: View?) {
        val next = view?.takeIf(::wantsHalo)
        target = next
        if (next == null) {
            visible = false
            animator.cancel()
            drawable.invalidateSelf()
            return
        }
        unclipAncestors(next)
        keepWideButtonsUnscaled(next)
        if (!measure(next, to)) return
        toRadii = radiiOf(next, to)
        if (visible && !shown.isEmpty) {
            from.set(shown)
            fromRadii = shownRadii.copyOf()
            animator.cancel()
            animator.start()
        } else {
            shown.set(to)
            shownRadii = toRadii.copyOf()
            visible = true
            drawable.invalidateSelf()
        }
    }

    /**
     * Buttons grow a little on focus (focus_scale). Where a button sits at the edge of its row, the
     * row (or its padding) clipped the grown body flat and the ring with it. Let the layouts between the control and
     * its scrolling container draw it whole; the scrolling container itself (a list, a page) keeps
     * clipping, so rows still never paint outside their list.
     */
    private fun unclipAncestors(view: View) {
        if (view.stateListAnimator == null) return
        var parent = view.parent
        while (parent is ViewGroup && parent !== root) {
            if (parent is RecyclerView || parent is ScrollView || parent is NestedScrollView ||
                parent is HorizontalScrollView
            ) {
                return
            }
            if (parent.clipChildren) parent.clipChildren = false
            if (parent.clipToPadding) parent.clipToPadding = false
            parent = parent.parent
        }
    }

    /**
     * The focus_scale grow is 5% of the width: a few dp on a normal button, but on a full-width one
     * (a list's "Add rule") it pushed the body past the list's edges, where the list clipped it and
     * the ring flat on both sides. Wide buttons skip the grow; the ring alone marks them.
     */
    private fun keepWideButtonsUnscaled(view: View) {
        if (view !is MaterialButton || view.stateListAnimator == null) return
        val wide = view.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT ||
            view.width * 0.025f > 6f * density
        if (!wide) return
        view.stateListAnimator = null
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /** Scrolling or a relayout moved the focused view: keep the ring on it. */
    private fun follow() {
        val view = target ?: return
        if (!view.isAttachedToWindow || !view.isShown || !wantsHalo(view)) {
            if (visible) {
                visible = false
                drawable.invalidateSelf()
            }
            return
        }
        val now = scratch
        if (!measure(view, now)) return
        if (now == to && visible) return
        to.set(now)
        toRadii = radiiOf(view, now)
        if (!animator.isRunning) {
            shown.set(now)
            shownRadii = toRadii.copyOf()
            visible = true
            drawable.invalidateSelf()
        }
    }

    private fun wantsHalo(view: View): Boolean {
        if (view is RecyclerView || view is ScrollView || view is NestedScrollView || view is HorizontalScrollView) {
            return false
        }
        // A focused full-screen container (a page that took focus itself) is not a "place" to show.
        // (A dialog can hand out its first focus before anything is laid out: no verdict on 0x0.)
        if (root.width == 0 || view.width == 0) return true
        return !(view.width >= root.width * 0.9f && view.height >= root.height * 0.9f)
    }

    private val scratch = RectF()
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)

    private fun measure(view: View, out: RectF): Boolean {
        if (view.width == 0 || view.height == 0) return false
        view.getLocationInWindow(location)
        root.getLocationInWindow(rootLocation)
        val left = (location[0] - rootLocation[0]).toFloat()
        val top = (location[1] - rootLocation[1]).toFloat()
        // getLocationInWindow already includes the view's own scale around its pivot; size follows.
        val sx = view.scaleX
        val sy = view.scaleY
        val body = visibleBody(view)
        out.set(
            left + body.left * sx - gap,
            top + body.top * sy - gap,
            left + body.right * sx + gap,
            top + body.bottom * sy + gap,
        )
        return true
    }

    private val body = Rect()

    /**
     * The part of the view that is actually drawn as the control: Material buttons and chips pad
     * themselves out to a 48dp touch target, and the ring belongs around the visible shape.
     */
    private fun visibleBody(view: View): Rect {
        body.set(0, 0, view.width, view.height)
        when (view) {
            is Chip -> view.chipDrawable?.bounds?.takeIf { !it.isEmpty }?.let(body::set)
            is MaterialButton -> {
                body.top += view.insetTop
                body.bottom -= view.insetBottom
            }
        }
        if (body.isEmpty) body.set(0, 0, view.width, view.height)
        return body
    }

    private fun radiiOf(view: View, rect: RectF): FloatArray {
        val w = rect.width() - 2 * gap
        val h = rect.height() - 2 * gap
        val max = minOf(w, h) / 2f
        val corners = shapeCorners(view, w, h) ?: outlineRadius(view)?.let { r -> FloatArray(4) { r } }
            ?: FloatArray(4) { fallbackRadius }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val r = (corners[i].coerceIn(0f, max) + gap)
            out[i * 2] = r
            out[i * 2 + 1] = r
        }
        return out
    }

    /** Corner sizes (TL, TR, BR, BL) of a Material shapeable view, e.g. a segmented button half. */
    private fun shapeCorners(view: View, w: Float, h: Float): FloatArray? {
        val model = (view as? Shapeable)?.shapeAppearanceModel ?: return null
        val bounds = RectF(0f, 0f, w, h)
        return floatArrayOf(
            model.topLeftCornerSize.getCornerSize(bounds),
            model.topRightCornerSize.getCornerSize(bounds),
            model.bottomRightCornerSize.getCornerSize(bounds),
            model.bottomLeftCornerSize.getCornerSize(bounds),
        )
    }

    private fun outlineRadius(view: View): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val provider = view.outlineProvider ?: return null
        val outline = Outline()
        provider.getOutline(view, outline)
        val r = outline.radius
        if (r <= 0f) return null
        val rect = Rect()
        if (!outline.getRect(rect)) return null
        // A background that only covers part of the view (an icon disc) is not the view's shape.
        if (rect.width() < view.width * 0.8f || rect.height() < view.height * 0.8f) return null
        return r
    }

    private inner class HaloDrawable : Drawable() {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()
        private val inner = RectF()
        private val clip = Rect()

        override fun draw(canvas: Canvas) {
            val view = target ?: return
            if (!visible || !windowFocused || shown.isEmpty) return
            val accent = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
            ring.color = accent
            ring.strokeWidth = stroke
            fill.color = ColorUtils.setAlphaComponent(accent, 0x24)

            canvas.save()
            // Never paint over what the focused view's scrolling parents hide (e.g. a half-scrolled
            // row under a list edge): clip to its visible part, grown by the ring.
            if (!animator.isRunning && view.getGlobalVisibleRect(clip)) {
                root.getLocationInWindow(rootLocation)
                val pad = (gap + stroke).toInt() + 1
                clip.offset(-rootLocation[0], -rootLocation[1])
                clip.inset(-pad, -pad)
                canvas.clipRect(clip)
            }
            inner.set(shown)
            inner.inset(gap, gap)
            path.reset()
            path.addRoundRect(inner, shrink(shownRadii, gap), Path.Direction.CW)
            canvas.drawPath(path, fill)

            val half = stroke / 2f
            inner.set(shown)
            inner.inset(-half, -half)
            path.reset()
            path.addRoundRect(inner, grow(shownRadii, half), Path.Direction.CW)
            canvas.drawPath(path, ring)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun shrink(radii: FloatArray, by: Float) = FloatArray(8) { (radii[it] - by).coerceAtLeast(0f) }
    private fun grow(radii: FloatArray, by: Float) = FloatArray(8) { radii[it] + by }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
