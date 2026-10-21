package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.github.kr328.clash.design.motion.liquidNavigationProgress
import com.github.kr328.clash.design.motion.navigationWidths
import com.github.kr328.clash.design.motion.motionEnabled
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.R as MaterialR
import kotlin.math.roundToInt

/** Expanding active capsule; native children retain touch, focus and accessibility. */
class LiquidNavigationBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.resolveThemedColor(MaterialR.attr.colorSecondaryContainer)
    }
    private val captionColor = context.resolveThemedColor(MaterialR.attr.colorOnSecondaryContainer)
    private val current = RectF()
    private val start = RectF()
    private val target = RectF()
    private var selectedItem: View? = null
    private var animator: ValueAnimator? = null
    private var progress = 1f
    private var items = emptyList<ViewGroup>()
    private var widths = FloatArray(0)
    private var startWidths = FloatArray(0)
    private var reveal = FloatArray(0)
    private var startReveal = FloatArray(0)
    private var expanded = true
    private var lastWidth = 0

    init { setWillNotDraw(false) }

    private fun dp(value: Int) = value * resources.displayMetrics.density
    private fun label(item: ViewGroup) = item.getChildAt(1) as TextView

    fun selectItem(item: View, animate: Boolean) {
        if (item.parent !== this || (selectedItem === item && !current.isEmpty)) return
        animator?.cancel()
        animator = null
        selectedItem = item
        for (i in 0 until childCount) getChildAt(i).isSelected = getChildAt(i) === item
        start.set(current)
        startWidths = widths.copyOf()
        startReveal = reveal.copyOf()
        progress = 1f
        if (animate && !current.isEmpty && isAttachedToWindow && isShown &&
            hasWindowFocus() && motionEnabled()
        ) {
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480L
                interpolator = LinearInterpolator()
                addUpdateListener {
                    progress = liquidNavigationProgress(it.animatedFraction)
                    requestLayout()
                    invalidate()
                }
                start()
            }
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val visible = (0 until childCount).map { getChildAt(it) }
            .filterIsInstance<ViewGroup>().filter { it.visibility != GONE }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val textWidths = visible.map { label(it).paint.measureText(label(it).text.toString()) }
        val selection = visible.indexOf(selectedItem).coerceAtLeast(0)
        val next = navigationWidths(available, textWidths, selection, dp(48), dp(56))
        val nextExpanded = next != null
        if (visible != items || lastWidth != width || expanded != nextExpanded) {
            animator?.cancel()
            animator = null
            progress = 1f
            items = visible
            widths = FloatArray(items.size)
            reveal = FloatArray(items.size)
        }
        expanded = nextExpanded
        lastWidth = width
        val targets = next ?: List(items.size) { available / items.size.coerceAtLeast(1) }
        val textHeight = items.maxOfOrNull { label(it).lineHeight } ?: dp(16).roundToInt()
        val contentHeight = if (expanded) maxOf(dp(56).roundToInt(), textHeight + dp(24).roundToInt())
            else dp(40).roundToInt() + textHeight + dp(8).roundToInt()
        val height = resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(width, height)
        var targetLeft = paddingLeft.toFloat()
        for (i in items.indices) {
            widths[i] = if (progress < 1f && startWidths.size == items.size) {
                startWidths[i] + (targets[i] - startWidths[i]) * progress
            } else targets[i]
            val selected = if (i == selection) 1f else 0f
            reveal[i] = if (progress < 1f && startReveal.size == items.size) {
                startReveal[i] + (selected - startReveal[i]) * progress
            } else selected
            items[i].measure(MeasureSpec.makeMeasureSpec(widths[i].roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height - paddingTop - paddingBottom, MeasureSpec.EXACTLY))
            label(items[i]).measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (i == selection) {
                val indicatorWidth = if (expanded) targets[i] else minOf(dp(56), targets[i])
                val left = targetLeft + (targets[i] - indicatorWidth) / 2f
                target.set(left, paddingTop.toFloat(), left + indicatorWidth,
                    if (expanded) (height - paddingBottom).toFloat() else paddingTop + dp(32))
            }
            targetLeft += targets[i]
        }
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            val left = width - target.right
            target.set(left, target.top, width - target.left, target.bottom)
        }
        if (progress >= 1f) current.set(target) else current.set(
            start.left + (target.left - start.left) * progress,
            start.top + (target.top - start.top) * progress,
            start.right + (target.right - start.right) * progress,
            start.bottom + (target.bottom - start.bottom) * progress,
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var offset = paddingLeft.toFloat()
        for (i in items.indices) {
            val item = items[i]
            val end = offset + widths[i]
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - end else offset
            item.layout(left.roundToInt(), paddingTop, (left + widths[i]).roundToInt(), height - paddingBottom)
            val text = label(item)
            val groupWidth = dp(24) + if (expanded) (text.measuredWidth + dp(8)) * reveal[i] else 0f
            val iconLeft = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                (item.width + groupWidth) / 2f - dp(24)
            } else (item.width - groupWidth) / 2f
            val iconTop = if (expanded) (item.height - dp(24)) / 2f else dp(4)
            item.getChildAt(0).layout(iconLeft.roundToInt(), iconTop.roundToInt(),
                (iconLeft + dp(24)).roundToInt(), (iconTop + dp(24)).roundToInt())
            val textLeft = if (layoutDirection == LAYOUT_DIRECTION_RTL) iconLeft - dp(8) - text.measuredWidth
                else iconLeft + dp(32)
            val textTop = (item.height - text.measuredHeight) / 2
            text.layout(textLeft.roundToInt(), textTop, textLeft.roundToInt() + text.measuredWidth,
                textTop + text.measuredHeight)
            text.alpha = if (expanded) reveal[i] * reveal[i] else 0f
            offset = end
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (current.isEmpty) return
        canvas.drawRoundRect(current, dp(28), dp(28), paint)
        if (!expanded) for (i in items.indices) {
            if (reveal[i] <= 0f) continue
            val text = label(items[i])
            val textPaint = text.paint
            val oldColor = textPaint.color
            val oldAlpha = textPaint.alpha
            textPaint.color = captionColor
            textPaint.alpha = (255 * reveal[i]).roundToInt()
            val baseline = paddingTop + dp(40) - textPaint.fontMetrics.top
            canvas.drawText(text.text.toString(), (width - textPaint.measureText(text.text.toString())) / 2f,
                baseline, textPaint)
            textPaint.color = oldColor
            textPaint.alpha = oldAlpha
        }
    }

    private fun settle() {
        animator?.cancel()
        animator = null
        progress = 1f
        requestLayout()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) settle()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown) settle()
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }
}
