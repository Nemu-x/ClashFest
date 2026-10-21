package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import com.github.kr328.clash.design.motion.liquidNavigationPinch
import com.github.kr328.clash.design.motion.liquidNavigationProgress
import com.github.kr328.clash.design.motion.motionEnabled
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.R as MaterialR
import kotlin.math.abs
import kotlin.math.min

/** One connected indicator; native child views keep clicks, focus, ripples and accessibility. */
class LiquidNavigationBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.resolveThemedColor(MaterialR.attr.colorSecondaryContainer)
    }
    private val path = Path()
    private val current = RectF()
    private val start = RectF()
    private val target = RectF()
    private val measured = RectF()
    private val inset = 2f * resources.displayMetrics.density
    private var selectedItem: View? = null
    private var animator: ValueAnimator? = null
    private var pinch = 0f

    init { setWillNotDraw(false) }

    fun selectItem(item: View, animate: Boolean) {
        if (item.parent !== this) return
        val sameItem = selectedItem === item
        selectedItem = item
        if (!measureItem(item)) return
        if (sameItem && measured == target) return
        animator?.cancel()
        animator = null
        target.set(measured)
        if (!animate || current.isEmpty || !isAttachedToWindow || !isShown ||
            !hasWindowFocus() || !motionEnabled()
        ) {
            settle()
            return
        }
        start.set(current)
        val initialPinch = pinch
        val forward = target.centerX() >= start.centerX()
        val strength = (abs(target.centerX() - start.centerX()) / start.width())
            .coerceAtMost(1f) * 0.38f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                current.set(
                    start.left + (target.left - start.left) * liquidNavigationProgress(t, !forward),
                    start.top + (target.top - start.top) * t,
                    start.right + (target.right - start.right) * liquidNavigationProgress(t, forward),
                    start.bottom + (target.bottom - start.bottom) * t,
                )
                pinch = liquidNavigationPinch(t, initialPinch, strength)
                invalidate()
            }
            start()
        }
    }

    private fun measureItem(item: View): Boolean {
        if (item.visibility != VISIBLE || item.width <= inset * 2 || item.height <= inset * 2) return false
        measured.set(item.left + inset, item.top + inset, item.right - inset, item.bottom - inset)
        return true
    }

    private fun settle() {
        animator?.cancel()
        animator = null
        selectedItem?.takeIf { it.parent === this }?.let {
            if (measureItem(it)) target.set(measured)
        }
        current.set(target)
        pinch = 0f
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val item = selectedItem ?: return
        if (measureItem(item) && (changed || measured != target)) settle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (current.isEmpty || selectedItem?.visibility != VISIBLE) return
        val radius = min(28f * resources.displayMetrics.density, min(current.width(), current.height()) / 2f)
        val left = current.left + radius
        val right = current.right - radius
        val middle = (left + right) / 2f
        val halfSpan = (right - left) / 2f
        val neck = current.height() * 0.5f * (1f - pinch)
        val neckTop = current.centerY() - neck
        val neckBottom = current.centerY() + neck
        path.reset()
        path.moveTo(left, current.top)
        path.cubicTo(left + halfSpan * 0.55f, current.top, middle - halfSpan * 0.45f, neckTop, middle, neckTop)
        path.cubicTo(middle + halfSpan * 0.45f, neckTop, right - halfSpan * 0.55f, current.top, right, current.top)
        path.quadTo(current.right, current.top, current.right, current.top + radius)
        path.lineTo(current.right, current.bottom - radius)
        path.quadTo(current.right, current.bottom, right, current.bottom)
        path.cubicTo(right - halfSpan * 0.55f, current.bottom, middle + halfSpan * 0.45f, neckBottom, middle, neckBottom)
        path.cubicTo(middle - halfSpan * 0.45f, neckBottom, left + halfSpan * 0.55f, current.bottom, left, current.bottom)
        path.quadTo(current.left, current.bottom, current.left, current.bottom - radius)
        path.lineTo(current.left, current.top + radius)
        path.quadTo(current.left, current.top, left, current.top)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (isAttachedToWindow && !hasWindowFocus) settle()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow && visibility != VISIBLE) settle()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow && !isShown) settle()
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }
}
