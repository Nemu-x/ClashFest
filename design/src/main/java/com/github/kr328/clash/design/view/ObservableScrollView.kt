package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes

class ObservableScrollView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : ScrollView(context, attributeSet, defStyleAttr, defStyleRes) {
    fun interface OnScrollChangedListener {
        fun onChanged(scrollView: ObservableScrollView, x: Int, y: Int, oldl: Int, oldt: Int)
    }

    private val scrollChangedListeners: MutableSet<OnScrollChangedListener> = mutableSetOf()

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)

        scrollChangedListeners.forEach {
            it.onChanged(this, l, t, oldl, oldt)
        }
    }

    fun addOnScrollChangedListener(listener: OnScrollChangedListener) {
        scrollChangedListeners.add(listener)
    }

    /**
     * Height of a bar floating over the top edge (the screen's activity bar). ScrollView brings a
     * focused child on screen against its own top edge, which parked a D-pad-focused field under
     * that bar, and UP then kept scrolling back to it instead of reaching the bar's buttons. With
     * this set, the band counts as off-screen. 0 (the default) is exactly ScrollView.
     */
    var topFocusInset: Int = 0

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val delta = super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val inset = topFocusInset
        if (inset <= 0 || childCount == 0) return delta
        // A control that shows below the bar with the page at the very top (the first fields):
        // go all the way up, so what sits above it (the intro) shows again and UP from it can
        // leave the page for the bar instead of being answered with a scroll that never ends.
        if (rect.top >= inset && rect.bottom <= height) return -scrollY
        if (rect.top - (scrollY + delta) >= inset) return delta
        return (rect.top - inset - scrollY).coerceAtLeast(-scrollY)
    }
}