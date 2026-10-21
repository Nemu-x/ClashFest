package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.motion.expressiveSpatial
import com.github.kr328.clash.design.motion.motionEnabled
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.R as MaterialR
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A tonal, twelve-lobed shape; its movement is gated by the host's visible page. */
class ExpressivePowerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private var transition: ValueAnimator? = null
    private var ambient: ValueAnimator? = null
    private var progress = 0f
    private var phase = 0f
    private var active = false
    private var connecting = false
    private var motionAllowed = false
    private var lastFrame = 0L
    private var idleColor = context.resolveThemedColor(MaterialR.attr.colorSecondaryContainer)
    private var activeColor = context.resolveThemedColor(MaterialR.attr.colorSecondaryContainer)
    private var leafColor = context.resolveThemedColor(MaterialR.attr.colorSecondary)

    fun setState(running: Boolean, starting: Boolean, animate: Boolean) {
        val changed = active != running
        val speedChanged = connecting != starting
        active = running
        connecting = starting
        idleColor = context.resolveThemedColor(MaterialR.attr.colorSecondaryContainer)
        val surface = context.resolveThemedColor(MaterialR.attr.colorSurface)
        activeColor = if (ColorUtils.calculateLuminance(surface) < 0.5)
            context.resolveThemedColor(MaterialR.attr.colorSecondary) else idleColor
        leafColor = context.resolveThemedColor(MaterialR.attr.colorSecondary)
        if (changed) {
            transition?.cancel()
            val target = if (running) 1f else 0f
            if (animate && motionEnabled()) {
                transition = ValueAnimator.ofFloat(progress, target).apply {
                    duration = 500L
                    interpolator = expressiveSpatial
                    addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else progress = target
        }
        if (speedChanged) { ambient?.cancel(); ambient = null }
        refreshMotion()
        invalidate()
    }

    fun allowMotion(allowed: Boolean) {
        motionAllowed = allowed
        refreshMotion()
        if (!allowed) {
            transition?.cancel()
            progress = if (active) 1f else 0f
            invalidate()
        }
    }

    private fun refreshMotion() {
        val shouldRun = motionAllowed && isAttachedToWindow && isShown && windowVisibility == VISIBLE && hasWindowFocus() && motionEnabled()
        if (!shouldRun) { ambient?.cancel(); ambient = null; return }
        if (ambient?.isRunning == true) return
        ambient = ValueAnimator.ofFloat(phase, phase + 1f).apply {
            duration = if (connecting) 2400L else if (active) 18000L else 24000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                if (now - lastFrame >= 33_000_000L) {
                    lastFrame = now
                    phase = (it.animatedValue as Float) % 1f
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) * 0.43f
        val cx = width / 2f
        val cy = height / 2f
        val amount = progress.coerceIn(0f, 1f)
        shape.rewind()
        for (i in 0..144) {
            val angle = i * Math.PI * 2 / 144
            val r = radius * (1 + (0.055 + 0.025 * amount + 0.008 * sin(phase * Math.PI * 6)) * cos(12 * angle))
            val rotation = angle + phase * Math.PI * 2
            val x = cx + (r * cos(rotation)).toFloat()
            val y = cy + (r * sin(rotation)).toFloat()
            if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
        }
        shape.close()
        paint.color = ColorUtils.blendARGB(idleColor, activeColor, amount)
        canvas.drawPath(shape, paint)
        paint.color = ColorUtils.setAlphaComponent(leafColor, (90 + 100 * amount).toInt())
        val dotAngle = phase * Math.PI * 2 - Math.PI / 4
        val dotRadius = radius * 1.13f
        canvas.drawCircle(cx + (cos(dotAngle) * dotRadius).toFloat(),
            cy + (sin(dotAngle) * dotRadius).toFloat(), radius * 0.045f, paint)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshMotion() }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
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

    override fun onDetachedFromWindow() {
        transition?.cancel()
        transition = null
        ambient?.cancel()
        ambient = null
        super.onDetachedFromWindow()
    }
}
