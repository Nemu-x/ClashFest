package com.github.kr328.clash.design.motion

import kotlin.math.exp

internal fun liquidNavigationProgress(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return ((1f - (1f + 8f * t) * exp(-8f * t)) / (1f - 9f * exp(-8f))).coerceIn(0f, 1f)
}

/** Null uses a shared full-width caption instead of shrinking text or touch targets. */
internal fun navigationWidths(
    available: Float, labels: List<Float>, selected: Int, minimum: Float, iconSpace: Float,
): List<Float>? {
    if (labels.isEmpty() || selected !in labels.indices) return null
    if (labels.max() + iconSpace + minimum * (labels.size - 1) > available) return null
    val active = labels[selected] + iconSpace
    val inactive = (available - active) / (labels.size - 1).coerceAtLeast(1)
    return labels.indices.map { if (it == selected) active else inactive }
}
