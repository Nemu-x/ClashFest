package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Subtle "blueprint" grid drawn behind a screen — part of the WOW design pilot.
 * Lines are a low-alpha tint of colorOnSurface so it adapts to light/dark.
 */
class GridBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val cell = 28f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        val tv = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOnSurface, tv, true,
        )
        // RGB of onSurface, alpha ~7%
        color = (tv.data and 0x00FFFFFF) or 0x12000000
    }

    override fun onDraw(canvas: Canvas) {
        var x = cell
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            x += cell
        }
        var y = cell
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += cell
        }
    }
}
