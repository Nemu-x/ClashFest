package com.github.kr328.clash.design.motion

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Normalized underdamped spring, shared by spatial transitions. */
internal fun expressiveSpringProgress(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f).toDouble()
    fun response(time: Double) = 1.0 - exp(-6.0 * time) * (cos(10.0 * time) + 0.6 * sin(10.0 * time))
    return (response(t) / response(1.0)).toFloat()
}
