package com.github.kr328.clash.design.motion

internal fun dismissSheetAfterPull(offset: Float, height: Int, velocity: Float, density: Float, cancelled: Boolean): Boolean =
    !cancelled && (offset > height * .33f || (velocity > 1400f * density && offset > 24f * density))
