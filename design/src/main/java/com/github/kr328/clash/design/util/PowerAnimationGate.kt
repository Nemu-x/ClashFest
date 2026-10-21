package com.github.kr328.clash.design.util

internal fun shouldAnimatePowerAmbient(
    attached: Boolean,
    activityVisible: Boolean,
    homeVisible: Boolean,
    aboutOpen: Boolean,
    animationsEnabled: Boolean,
): Boolean = attached && activityVisible && homeVisible && !aboutOpen && animationsEnabled
