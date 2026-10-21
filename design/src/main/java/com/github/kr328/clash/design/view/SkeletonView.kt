package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.motion.motionEnabled
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.R as MaterialR

/** Shimmer only exists while this skeleton is actually visible. */
class SkeletonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val visibleArea = Rect()
    private var animator: ValueAnimator? = null
    private var offset = 0f
    private var lastFrame = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val base = context.resolveThemedColor(MaterialR.attr.colorSurfaceContainerHigh)
        val foreground = context.resolveThemedColor(MaterialR.attr.colorOnSurface)
        val highlight = ColorUtils.blendARGB(base, foreground, 0.08f)
        paint.shader = LinearGradient(-w.toFloat(), 0f, 0f, h.toFloat(),
            intArrayOf(base, highlight, base), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        refreshMotion()
        matrix.setTranslate(width * offset, 0f)
        paint.shader?.setLocalMatrix(matrix)
        val radius = minOf(height / 2f, 24f * resources.displayMetrics.density)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
    }

    private fun refreshMotion() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !motionEnabled() || !getGlobalVisibleRect(visibleArea)) {
            animator?.cancel()
            animator = null
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 2f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!getGlobalVisibleRect(visibleArea)) {
                    cancel()
                    animator = null
                    return@addUpdateListener
                }
                val now = System.nanoTime()
                if (now - lastFrame >= 33_000_000L) {
                    lastFrame = now
                    offset = it.animatedValue as Float
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshMotion() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        refreshMotion()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refreshMotion()
    }
    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
