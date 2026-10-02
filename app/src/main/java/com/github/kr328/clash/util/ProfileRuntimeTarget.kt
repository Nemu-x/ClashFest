package com.github.kr328.clash.util

import java.util.UUID

/** Browsing or testing another subscription must not modify the running profile. */
internal object ProfileRuntimeTarget {
    fun canUseEngine(running: Boolean, activeUuid: UUID?, targetUuid: UUID): Boolean =
        running && activeUuid == targetUuid
}
