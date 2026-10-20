package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.SubscriptionNameGuesser
import com.github.kr328.clash.common.util.SubscriptionUsage
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.branding.BrandRefresh
import com.github.kr328.clash.service.util.FetchHeadersFile
import com.github.kr328.clash.service.util.OperatorPolicyHeaders
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged

/**
 * Owns the post-update header pass of [ProfileManager.update]: reads the persisted subscription
 * response headers, refreshes the operator brand, and updates the stored name, quota and expiry.
 */
internal class SubscriptionHeaderSync(private val context: Context) {
    suspend fun updateFlow(old: Imported) {
        try {
            // ProfileProcessor.update just re-downloaded the subscription; the Go
            // fetch persisted the response headers next to config.yaml
            // (fetch-headers.json). Re-requesting the panel here — the old OkHttp
            // GET — doubled traffic (panels count fetches against quota), could
            // race the primary download, and kept a second header-decoding path
            // alive. Absent snapshot = no HTTP response observed (content:// or a
            // failed fetch) — same early-return the old `!response.isSuccessful`
            // gave us.
            val headers = FetchHeadersFile.readFrom(context.importedDir.resolve(old.uuid.toString()))
                ?: return

            // Operator brand parses independently of subscription-userinfo —
            // panels that don't carry quota (free tiers, custom auth flows)
            // still need to brand the app. We do this BEFORE the
            // subscription-userinfo early-return so brand survives even
            // when the panel stops sending quota headers.
            //
            // parse(), NOT parseHttpHeaders(): the snapshot values are already
            // correct UTF-8 (Go passes header bytes through and JSON preserves
            // them). parseHttpHeaders' ISO-8859-1→UTF-8 recovery is only for
            // live transports and would mangle non-ASCII (Cyrillic brand /
            // display names) coming from the file.
            val brand = com.github.kr328.clash.common.branding.BrandManifestParser.parse { key ->
                headers.get(key)
            }
            // confirmedResponse=true: the header snapshot only exists for a fetch
            // that completed AND passed engine validation (update() succeeded), so
            // an empty brand here is a real "served the sub with no X-Brand-*
            // headers" signal (not a transient failure) and feeds the debounced
            // auto-clear.
            BrandRefresh.apply(context, old.uuid, brand, confirmedResponse = true)
            OperatorPolicyHeaders.applyFromSnapshot(context, old.uuid, headers)

            // Re-derive the display name on EVERY update — not only when the panel sends
            // subscription-userinfo. Tying the rename to the quota header meant subscriptions
            // that don't send it never had their name corrected on update (the "белиберда /
            // URL-tail" name bug). Usage/expiry still come from subscription-userinfo when
            // present, but its absence must not skip the rename. (E-16)
            val usage = SubscriptionUsage.parse(headers.get("subscription-userinfo"))
            val renamed = deriveTitleFromHeaders(headers)
            val effectiveName = if (looksLikeGeneratedTokenName(old.name) && !renamed.isNullOrBlank()) {
                renamed
            } else {
                old.name
            }

            // Keep the previous quota/expiry when the panel didn't send subscription-userinfo
            // this time, instead of zeroing them.
            val new = Imported(
                old.uuid,
                effectiveName,
                old.type,
                old.source,
                old.interval,
                usage?.upload ?: old.upload,
                usage?.download ?: old.download,
                usage?.total ?: old.total,
                usage?.expireAt?.times(1000L) ?: old.expire,
                old.createdAt,
                old.profileOrder,
                ageSecretKey = old.ageSecretKey,
            )

            if (new != old) {
                ImportedDao().update(new)
                PendingDao().remove(new.uuid)
                context.sendProfileChanged(new.uuid)
            }
        } catch (e: Exception) {
            Log.w("updateFlow failed", e)
        }
    }

    // Delegate to the shared resolver so import and update decode headers identically (E-19):
    // title headers → Content-Disposition filename* (RFC-5987) → Subscription-Userinfo. Keep the
    // 64-char safety cap.
    private fun deriveTitleFromHeaders(headers: FetchHeadersFile): String? =
        SubscriptionNameGuesser.titleFromHeaders { key -> headers.get(key) }
            ?.let { if (it.length > 64) it.take(64) else it }

    private fun looksLikeGeneratedTokenName(name: String): Boolean {
        val n = name.trim()
        // Guard rails: only ever reconsider a medium-length single-word label — never rename a
        // human name with spaces/dots or a very short/long one.
        if (n.length !in 12..48) return false
        if (n.contains(' ') || n.contains('.') || n.contains('/')) return false
        // Shared predicate so import and update agree on what a token is (E-17): a name import
        // deliberately kept (e.g. all-lowercase "premiumplan1") is no longer replaced on update.
        return SubscriptionNameGuesser.looksLikeOpaqueToken(n)
    }
}
