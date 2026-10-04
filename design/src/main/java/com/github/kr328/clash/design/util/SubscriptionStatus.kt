package com.github.kr328.clash.design.util

import com.github.kr328.clash.common.util.SubscriptionUsage

internal data class SubscriptionStatus(val remainingBytes: Long?, val expireMs: Long)

internal fun subscriptionStatus(
    upload: Long, download: Long, total: Long, expireMs: Long, header: SubscriptionUsage?,
): SubscriptionStatus {
    val up = header?.upload?.takeIf { it >= 0 } ?: upload.coerceAtLeast(0)
    val down = header?.download?.takeIf { it >= 0 } ?: download.coerceAtLeast(0)
    val used = up + down.coerceAtMost(Long.MAX_VALUE - up)
    val limit = header?.total?.takeIf { it >= 0 } ?: total
    val expiry = header?.expireAt?.takeIf { it >= 0 }?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 }
        ?: expireMs.coerceAtLeast(0)
    return SubscriptionStatus(if (limit < 2) null else (limit - used).coerceAtLeast(0), expiry)
}
