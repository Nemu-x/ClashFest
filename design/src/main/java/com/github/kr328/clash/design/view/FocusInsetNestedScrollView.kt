package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
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

    /**
     * Where DOWN goes once it leaves the page from its last row (null: the default search, which
     * picks whichever nav button is geometrically nearest, not the one this page belongs to).
     */
    var focusDownExit: (() -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if (direction != View.FOCUS_DOWN || next == null || isDescendant(next)) return next
        return focusDownExit?.invoke() ?: next
    }

    /**
     * With [focusDownExit] set, DOWN on the last focusable row goes straight out instead of first
     * scrolling through the empty bottom padding (which took an extra press that seemed to do
     * nothing).
     */
    override fun arrowScroll(direction: Int): Boolean {
        val exit = focusDownExit
        if (direction == View.FOCUS_DOWN && exit != null) {
            val current = findFocus()
            if (current != null && current !== this &&
                FocusFinder.getInstance().findNextFocus(this, current, View.FOCUS_DOWN) == null
            ) {
                exit()?.let { if (it.requestFocus()) return true }
            }
        }
        return super.arrowScroll(direction)
    }

    private fun isDescendant(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }

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
                // A row that still fits with the page at the very top (the first rows under the
                // page title): go all the way up, so the title shows again instead of the row
                // being parked at the top edge with the title cut off.
                if (rect.bottom <= height - bottomFocusInset) return -scrollY
                val delta = if (rect.height() > visible) rect.bottom - screenBottom else rect.top - screenTop
                delta.coerceAtLeast(-scrollY)
            }
            // Already on screen, but the page is scrolled a little past the title (e.g. focus came
            // back to the first row on return from a sub-screen): same rule as above.
            scrollY > 0 && rect.top < visible / 3 && rect.bottom <= height - bottomFocusInset -> -scrollY
            else -> 0
        }
    }
}
