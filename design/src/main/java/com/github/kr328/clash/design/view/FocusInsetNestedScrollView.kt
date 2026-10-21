package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * A [NestedScrollView] that can keep the focused child clear of something floating over its
 * bottom edge (the main screen's bottom nav). NestedScrollView brings a focused child on screen
 * using its full height and never scrolls into its bottom padding, so the last rows stayed behind
 * the nav. With [bottomFocusInset] set, that band counts as off-screen and the padding is part of
 * the scroll range. 0 (the default) is exactly NestedScrollView.
 */
class FocusInsetNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    var bottomFocusInset: Int = 0

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (bottomFocusInset <= 0 || childCount == 0) {
            return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        }
        val screenTop = scrollY
        val screenBottom = scrollY + height - bottomFocusInset
        val visible = screenBottom - screenTop
        if (visible <= 0) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val child = getChildAt(0)
        val lp = child.layoutParams as MarginLayoutParams
        val maxScroll = (child.bottom + lp.bottomMargin + paddingBottom - height).coerceAtLeast(0)

        return when {
            rect.bottom > screenBottom && rect.top > screenTop -> {
                val delta = if (rect.height() > visible) rect.top - screenTop else rect.bottom - screenBottom
                delta.coerceAtMost(maxScroll - scrollY)
            }
            rect.top < screenTop && rect.bottom < screenBottom -> {
                val delta = if (rect.height() > visible) rect.bottom - screenBottom else rect.top - screenTop
                delta.coerceAtLeast(-scrollY)
            }
            else -> 0
        }
    }
}
