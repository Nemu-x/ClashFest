package com.github.kr328.clash.util

internal fun shouldPollHomeTraffic(running: Boolean, activityStarted: Boolean, homeVisible: Boolean): Boolean =
    running && activityStarted && homeVisible
