package com.github.kr328.clash.design.motion

import kotlin.math.sin

internal fun liquidNavigationProgress(fraction: Float, leading: Boolean): Float {
    val delay = if (leading) 0f else 0.16f
    val duration = if (leading) 0.72f else 0.84f
    val t = ((fraction - delay) / duration).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun liquidNavigationPinch(fraction: Float, initial: Float, strength: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    if (t == 0f) return initial.coerceIn(0f, 0.45f)
    if (t == 1f) return 0f
    return (initial * (1f - t) + strength * sin(Math.PI * t).toFloat()).coerceIn(0f, 0.45f)
}
