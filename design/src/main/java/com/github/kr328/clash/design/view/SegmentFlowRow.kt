package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/**
 * The picker's group-segment row. By default it is exactly a horizontal [LinearLayout] (the phone
 * scrolls it sideways). With [flowWidth] set (the TV side panel) it wraps the segments onto up to
 * [maxFlowLines] lines within that width, so every group stays in view instead of running off the
 * edge. A subscription with more groups than that keeps the single scrolling line, so the node
 * list below is never squeezed out.
 */
class SegmentFlowRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    /** Width in px to wrap within; 0 keeps the plain single-line LinearLayout behaviour. */
    var flowWidth: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var maxFlowLines: Int = 3

    private var flowing = false
    private val spacing = (4 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        flowing = false
        if (flowWidth > 0) {
            val inner = flowWidth - paddingLeft - paddingRight
            val childWidthSpec = MeasureSpec.makeMeasureSpec(inner, MeasureSpec.AT_MOST)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            var x = 0
            var lines = 1
            var lineHeight = 0
            var height = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                child.measure(childWidthSpec, childHeightSpec)
                val w = child.measuredWidth
                if (x > 0 && x + w > inner) {
                    height += lineHeight + spacing
                    x = 0
                    lineHeight = 0
                    lines++
                }
                x += w + spacing
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
            if (lines <= maxFlowLines) {
                flowing = true
                height += lineHeight
                setMeasuredDimension(
                    flowWidth,
                    maxOf(height + paddingTop + paddingBottom, suggestedMinimumHeight),
                )
                return
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!flowing) {
            super.onLayout(changed, l, t, r, b)
            return
        }
        val inner = flowWidth - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > inner) {
                y += lineHeight + spacing
                x = 0
                lineHeight = 0
            }
            val left = paddingLeft + x
            child.layout(left, y, left + w, y + h)
            x += w + spacing
            lineHeight = maxOf(lineHeight, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // LinearLayout's onDraw only paints the "|" dividers, which belong to the single line.
        if (!flowing) super.onDraw(canvas)
    }
}
