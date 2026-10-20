package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.util.SubscriptionMetadataFetcher
import com.github.kr328.clash.service.store.ServiceStore
import java.util.UUID

/**
 * Operator policy headers that travel per subscription: `X-Network-Stack` (TUN stack lock),
 * `share-links` (share-link lock) and `X-Bypass-Preset` (bypass preset recommendation).
 *
 * They used to be stored only by the UI's metadata sync, which is throttled to once per ~6 hours
 * and only ever *set* a value: a changed header took hours to land, and a header the operator
 * stopped sending kept its old lock forever. They are now applied from the `fetch-headers.json`
 * snapshot on every successful import/update. The snapshot only exists for a fetch that
 * completed, so a header missing from it really means the operator withdrew it, and the stored
 * value is cleared.
 */
object OperatorPolicyHeaders {
    data class Policy(
        val networkStack: String?,
        val shareLinksLocked: Boolean,
        val bypassPreset: String?,
    )

    fun policyOf(headers: FetchHeadersFile): Policy {
        val meta = SubscriptionMetadataFetcher.parseHeaders { key -> headers.get(key) }
        return Policy(
            networkStack = meta.networkStack,
            shareLinksLocked = meta.shareLinksDisable == true,
            bypassPreset = meta.bypassPreset?.takeUnless { it == "none" },
        )
    }

    fun applyFromSnapshot(context: Context, uuid: UUID, headers: FetchHeadersFile) {
        val policy = policyOf(headers)
        val store = ServiceStore(context)
        store.setSubscriptionNetworkStackFor(uuid, policy.networkStack)
        store.setSubscriptionShareLinksLockedFor(uuid, policy.shareLinksLocked)
        store.setSubscriptionBypassPresetFor(uuid, policy.bypassPreset)
    }
}
