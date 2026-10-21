package com.github.kr328.clash.design.motion

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

internal val expressiveSpatial = TimeInterpolator { expressiveSpringProgress(it) }

internal fun motionEnabled(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

internal fun animateNavigationItem(view: View, selected: Boolean, animate: Boolean) {
    view.isSelected = selected
    (view as? ViewGroup)?.let { group ->
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.animate().cancel()
            val scale = if (child is ImageView && selected) 1.12f else 1f
            val y = if (child is ImageView && selected) -2f * view.resources.displayMetrics.density else 0f
            val opacity = if (child is TextView && !selected) 0.8f else 1f
            child.alpha = opacity
            if (animate && motionEnabled()) {
                child.animate().scaleX(scale).scaleY(scale).translationY(y)
                    .setDuration(420L).setInterpolator(expressiveSpatial).start()
            } else {
                child.scaleX = scale
                child.scaleY = scale
                child.translationY = y
                child.alpha = opacity
            }
        }
    }
}

internal fun transformExpressivePage(page: View, position: Float) {
    val distance = abs(position).coerceAtMost(1f)
    page.alpha = if (motionEnabled()) 1f - distance * 0.25f else 1f
    val scale = if (motionEnabled()) 1f - distance * 0.025f else 1f
    page.scaleX = scale
    page.scaleY = scale
}

internal fun animateExpressivePress(view: View, pressed: Boolean) {
    view.animate().cancel()
    val scale = if (pressed) 0.96f else 1f
    if (motionEnabled()) {
        view.animate().scaleX(scale).scaleY(scale).setDuration(if (pressed) 90L else 420L)
            .setInterpolator(expressiveSpatial).start()
    } else {
        view.scaleX = scale
        view.scaleY = scale
    }
}

internal fun animateDisclosure(view: View, key: Any, rotation: Float) {
    val previous = view.getTag(com.github.kr328.clash.design.R.id.disclosure_icon_state) as? Pair<*, *>
    view.setTag(com.github.kr328.clash.design.R.id.disclosure_icon_state, key to rotation)
    if (previous?.first == key && previous.second == rotation) return
    view.animate().cancel()
    if (previous?.first == key && view.isAttachedToWindow && motionEnabled()) {
        view.animate().rotation(rotation).setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    } else view.rotation = rotation
}
