package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.github.kr328.clash.design.motion.motionEnabled
import com.github.kr328.clash.design.util.hostActivity
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.R as MaterialR
import kotlin.math.sin

/** A continuous tonal light wave; one driver, only in the foreground. */
class ExpressiveBackdropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradientMatrix = Matrix()
    private val lifecycle = (context.hostActivity() as? LifecycleOwner)?.lifecycle
    private var animator: ValueAnimator? = null
    private var phase = 0f
    private var lastFrame = 0L
    private val observer = LifecycleEventObserver { _, _ -> refreshMotion() }
    private val primary = context.resolveThemedColor(MaterialR.attr.colorPrimary)
    private val secondary = context.resolveThemedColor(MaterialR.attr.colorSecondary)

    init {
        if (com.github.kr328.clash.design.store.UiStore(context).homeBackgroundStyle ==
            com.github.kr328.clash.design.model.HomeBackgroundStyle.MaterialYou
        ) visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val band = 240f * resources.displayMetrics.density
        paint.shader = LinearGradient(0f, -band, 0f, band,
            intArrayOf(ColorUtils.setAlphaComponent(secondary, 0),
                ColorUtils.setAlphaComponent(secondary, 7),
                ColorUtils.setAlphaComponent(secondary, 25),
                ColorUtils.setAlphaComponent(primary, 12),
                ColorUtils.setAlphaComponent(primary, 0)),
            floatArrayOf(0f, 0.22f, 0.5f, 0.72f, 1f), Shader.TileMode.CLAMP)
    }

    private fun refreshMotion() {
        val visible = isAttachedToWindow && isShown && windowVisibility == VISIBLE && hasWindowFocus() &&
            lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true && motionEnabled()
        if (!visible) { animator?.cancel(); animator = null; return }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(phase, phase + 1f).apply {
            duration = 28000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                if (now - lastFrame >= 50_000_000L) {
                    lastFrame = now
                    phase = (it.animatedValue as Float) % 1f
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        val angle = phase * Math.PI * 2
        val step = 6f * dp
        var x = 0f
        while (x < width) {
            val wave = sin(x / width.coerceAtLeast(1) * Math.PI * 1.3 + angle)
            val center = height * 0.42f + wave.toFloat() * 84f * dp + sin(angle).toFloat() * 28f * dp
            gradientMatrix.setTranslate(0f, center)
            paint.shader?.setLocalMatrix(gradientMatrix)
            canvas.drawRect(x, 0f, x + step, height.toFloat(), paint)
            x += step
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycle?.addObserver(observer)
        refreshMotion()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        refreshMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refreshMotion()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        refreshMotion()
    }

    override fun onDetachedFromWindow() {
        lifecycle?.removeObserver(observer)
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
