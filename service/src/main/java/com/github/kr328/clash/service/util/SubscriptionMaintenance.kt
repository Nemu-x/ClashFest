package com.github.kr328.clash.service.util

import java.io.File
import java.util.UUID

internal fun lastSubscriptionFetchAt(profileDir: File): Long =
    File(profileDir, ProfileComposer.SUBSCRIPTION_FILE).takeIf { it.isFile }?.lastModified()
        ?: File(profileDir, "config.yaml").lastModified()

internal inline fun activeProfileAfterDelete(active: UUID?, deleted: UUID, remaining: () -> List<UUID>): UUID? =
    if (active == deleted) remaining().firstOrNull { it != deleted } else active
