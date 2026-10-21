package com.github.kr328.clash.design.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnAttach
import com.github.kr328.clash.design.R

/**
 * For screens that lay themselves out edge to edge (their own window-inset handling, not the
 * surface insets that [landscape] caps): on a TV, centre them in the same content width the other
 * screens use (surface_landscape_min_width) instead of stretching rows across the whole set with a
 * phone gutter at the overscan edge. No-op off TV.
 */
fun View.capContentWidthOnTv() {
    if (!context.isTelevision()) return
    doOnAttach { view ->
        val frame = view.parent as? ViewGroup ?: return@doOnAttach
        val cap = context.getPixels(R.dimen.surface_landscape_min_width)
        val width = resources.displayMetrics.widthPixels
        val side = ((width - cap) / 2).coerceAtLeast(0)
        frame.setPaddingRelative(side, frame.paddingTop, side, frame.paddingBottom)
    }
}
