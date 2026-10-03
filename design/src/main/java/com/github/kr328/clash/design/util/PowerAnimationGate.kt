package com.github.kr328.clash.design.util

internal fun shouldAnimatePowerAmbient(
    running: Boolean,
    attached: Boolean,
    activityVisible: Boolean,
    homeVisible: Boolean,
    aboutOpen: Boolean,
    animationsEnabled: Boolean,
): Boolean = running && attached && activityVisible && homeVisible && !aboutOpen && animationsEnabled
