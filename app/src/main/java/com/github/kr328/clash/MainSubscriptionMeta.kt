package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import com.github.kr328.clash.common.util.SubscriptionOverrides
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.OperatorPolicyHeaders
import com.github.kr328.clash.service.util.TunStackResolver
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.SubscriptionMetaCache
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Operator-side subscription metadata for [MainActivity]: the throttled panel-header sync
 * (support / announce / interval / userinfo / operator policies), the Home announcement card,
 * the active profile's brand (logo + accent staleness check that triggers a soft recreate) and
 * opening operator-provided external links.
 */
internal class MainSubscriptionMeta(private val activity: MainActivity) {
    private val design: MainDesign?
        get() = activity.mainDesign

    private val uiStore
        get() = activity.mainUiStore

    private val subscriptionMetadataSyncMutex = Mutex()

    private var lastHintedTunStack: String? = null

    /**
     * The TUN stack is fixed when the tunnel opens. If an update or the metadata sync changed what
     * [TunStackResolver] picks now (an operator `X-Network-Stack` added, changed or withdrawn),
     * tell the user once that it applies after a reconnect.
     */
    internal suspend fun hintTunStackChange(design: MainDesign) {
        if (!activity.isClashRunning) return
        val change = withContext(Dispatchers.IO) {
            val store = ServiceStore(activity)
            val applied = store.appliedTunStack.takeIf { it.isNotBlank() } ?: return@withContext null
            val uuid = store.activeProfile
            val configYaml = uuid?.let {
                runCatching {
                    java.io.File(activity.importedDir.resolve(it.toString()), "config.yaml").readText()
                }.getOrNull()
            }
            TunStackResolver.resolve(
                store.tunStackMode,
                uuid?.let { store.subscriptionNetworkStackFor(it) },
                configYaml,
            ).takeIf { it != applied }?.let { applied to it }
        } ?: return
        // Keyed by the pair, so a later change after a reconnect is hinted again.
        val key = "${change.first}>${change.second}"
        if (key == lastHintedTunStack) return
        lastHintedTunStack = key
        design.showToast(
            activity.getString(R.string.tun_stack_changed_reconnect, change.second),
            ToastDuration.Long,
        )
    }

    /**
     * Reads announcement settings from [uiStore] and forwards them to the design.
     * Resets the dismissed-flag automatically when the announcement text changes.
     */
    internal suspend fun refreshAnnouncement(design: com.github.kr328.clash.design.MainDesign) {
        // Render current cache first for snappy UI.
        renderAnnouncementFromStore(design)

        // Then refresh metadata and re-render so support/announce updates appear immediately.
        activity.launch(Dispatchers.IO) {
            val updated = runCatching { syncSubscriptionMetadata() }.isSuccess
            if (updated) {
                withContext(Dispatchers.Main) {
                    renderAnnouncementFromStore(design)
                    hintTunStackChange(design)
                    // The sync may have just stored a brand (the dashboard fetch that applies it
                    // ran before it); show it now instead of on the next refresh.
                    applyActiveBrand()
                }
            }
        }
    }

    private suspend fun renderAnnouncementFromStore(design: com.github.kr328.clash.design.MainDesign) {
        val active = runCatching { withProfile { queryActive() } }.getOrNull()
        com.github.kr328.clash.util.SubscriptionMetaCache.hydrateUiStoreForProfile(uiStore, active)

        val text = uiStore.announcement
        val url = uiStore.announcementUrl
        val supportUrl = uiStore.supportUrl

        design.setAnnouncement(
            text = text.takeIf { it.isNotBlank() },
            url = url.takeIf { it.isNotBlank() },
            supportUrl = supportUrl.takeIf { it.isNotBlank() },
            sourceUuid = active?.uuid,
            sourceName = active?.name,
            onOpenUrl = { openExternalUrl(it) },
            onSupport = {
                supportUrl.takeIf { it.isNotBlank() }?.let { openExternalUrl(it) }
            },
        )
    }

    /**
     * Periodically pulls panel-style headers (support-url, announce, profile-title,
     * profile-update-interval, subscription-userinfo, …) from the *active* URL profile.
     * Throttled to once per ~6 hours; user-edited fields stay user-edited.
     */
    internal suspend fun syncSubscriptionMetadata() = subscriptionMetadataSyncMutex.withLock {
        val active = runCatching { withProfile { queryActive() } }.getOrNull() ?: return@withLock
        if (active.type != Profile.Type.Url) return@withLock
        val url = active.source.takeIf { it.isNotBlank() } ?: return@withLock

        val now = System.currentTimeMillis() / 1000L
        val cooldown = SubscriptionMetaCache.REFRESH_INTERVAL_SECONDS
        val activeId = active.uuid.toString()
        if (!SubscriptionMetaCache.isRefreshDue(
                activeId,
                uiStore.subscriptionMetadataLastFetchProfileId,
                uiStore.subscriptionMetadataLastFetch,
                now,
            )
        ) {
            return@withLock
        }

        // Prefer the per-profile header snapshot the Go fetch persisted
        // (fetch-headers.json): when the subscription itself was fetched within
        // the sync cooldown, this sync costs ZERO network requests. Snapshot
        // values are already correct UTF-8, so the decoded parser entry points
        // are used (no ISO-8859-1 transport recovery — it would mangle
        // Cyrillic). Stale/absent snapshot → ONE combined GET (this used to be
        // two identical back-to-back requests: metadata + brand; panels count
        // every fetch against quota).
        val profileDir = activity.importedDir.resolve(active.uuid.toString())
        val headersFile = com.github.kr328.clash.service.util.FetchHeadersFile.readFrom(profileDir)
        val headersFresh = java.io.File(profileDir, com.github.kr328.clash.service.util.FetchHeadersFile.FILE_NAME)
            .takeIf { it.isFile }
            ?.let { System.currentTimeMillis() - it.lastModified() < cooldown * 1000L } == true
        val plainHttp = url.substringBefore('#').startsWith("http://", ignoreCase = true)
        val allowCachedHeaders = !plainHttp || uiStore.subscriptionMetadataAllowInsecureHttp

        // A plain-http panel can never be probed live: the app permits no cleartext traffic
        // (network_security_config), so HttpURLConnection fails and the opted-in sync only ever
        // saw an empty manifest. The Go fetch is not bound by that policy and its snapshot is the
        // last real response, so an opted-in http subscription always reads it, fresh or not.
        val fromSnapshot = headersFile != null && allowCachedHeaders && (headersFresh || plainHttp)
        val (meta, brandManifest) = if (fromSnapshot) {
            com.github.kr328.clash.common.util.SubscriptionMetadataFetcher
                .parseHeaders { key -> headersFile.get(key) } to
                com.github.kr328.clash.common.branding.BrandManifestParser
                    .parse { key -> headersFile.get(key) }
        } else {
            com.github.kr328.clash.common.util.SubscriptionMetadataFetcher.fetchWithBrand(
                activity,
                url,
                SubscriptionOverrides.getUserAgent(activity, active.uuid),
                uiStore.subscriptionMetadataAllowInsecureHttp,
            )
        }
        com.github.kr328.clash.service.branding.BrandRefresh.apply(activity, active.uuid, brandManifest)
        if (fromSnapshot) {
            // A snapshot is a completed fetch: a policy header missing from it was withdrawn
            // by the operator and is cleared (import/update already applied the same snapshot).
            OperatorPolicyHeaders.applyFromSnapshot(activity, active.uuid, headersFile!!)
        } else {
            // Live GET: it may have failed, so an absent header proves nothing; only set.
            meta.shareLinksDisable?.let {
                ServiceStore(activity).setSubscriptionShareLinksLockedFor(active.uuid, it)
            }
            // Operator TUN-stack policy (X-Network-Stack). Store as-is; `auto` is the operator's explicit
            // "unlock". Applied at TUN open by TunStackResolver, independent of branding.
            meta.networkStack?.let {
                ServiceStore(activity).setSubscriptionNetworkStackFor(active.uuid, it)
            }
            // Operator desync policy (X-Desync), applied when the profile is composed.
            meta.desyncPolicy?.let {
                ServiceStore(activity).setSubscriptionDesyncPolicyFor(active.uuid, it)
            }
            // Operator bypass-preset recommendation (X-Bypass-Preset). Stored only — the offer
            // dialog rides the pre-start bypass prompt; `none` withdraws the recommendation.
            meta.bypassPreset?.let {
                ServiceStore(activity).setSubscriptionBypassPresetFor(
                    active.uuid,
                    it.takeUnless { v -> v == "none" },
                )
            }
        }
        uiStore.subscriptionHwidActive = meta.hwidActive?.toString().orEmpty()
        uiStore.subscriptionHwidNotSupported = meta.hwidNotSupported?.toString().orEmpty()
        uiStore.subscriptionHwidMaxDevicesReached = meta.hwidMaxDevicesReached?.toString().orEmpty()
        uiStore.subscriptionHwidLimit = meta.hwidLimit?.toString().orEmpty()
        if (meta.isEmpty()) {
            // still mark the attempt to avoid hammering the server every screen-on
            uiStore.subscriptionMetadataLastFetch = now
            uiStore.subscriptionMetadataLastFetchProfileId = activeId
            return@withLock
        }
        uiStore.subscriptionMetadataLastFetch = now
        uiStore.subscriptionMetadataLastFetchProfileId = activeId

        // Always-mirrored fields (cache only):
        meta.subscriptionUserinfo?.let { uiStore.subscriptionUserinfo = it }
        meta.profileWebPageUrl?.let { uiStore.profileWebPageUrl = it }
        meta.profileUpdateIntervalHours?.let { hours ->
            val previousHeaderMs =
                java.util.concurrent.TimeUnit.HOURS.toMillis(uiStore.profileUpdateIntervalHours.toLong())
            uiStore.profileUpdateIntervalHours = hours
            if (!uiStore.subscriptionMetadataLockUser) {
                // The header only *seeds* the interval — it must not override a value
                // the user set by hand (upstream 71cb215d class of bug: the panel
                // silently reverted a custom interval every metadata sync). Apply it
                // only when auto-update is unconfigured, or the current interval is
                // the one WE derived from the previous header value (i.e. untouched).
                val userCustomized = active.interval != 0L && active.interval != previousHeaderMs
                if (!userCustomized) {
                    val ms = java.util.concurrent.TimeUnit.HOURS.toMillis(hours.toLong())
                    runCatching {
                        withProfile { applySubscriptionUpdateInterval(active.uuid, ms) }
                    }
                }
            }
        }

        // User-overridable fields — skip if user opted out of operator overrides.
        if (!uiStore.subscriptionMetadataLockUser) {
            meta.supportUrl?.let { uiStore.supportUrl = it }
            meta.announcement?.let { uiStore.announcement = it }
            meta.announcementUrl?.let { uiStore.announcementUrl = it }
        }
        SubscriptionMetaCache.put(
            uiStore,
            active.uuid,
            SubscriptionMetaCache.Entry(
                announcement = meta.announcement.orEmpty(),
                announcementUrl = meta.announcementUrl.orEmpty(),
                supportUrl = meta.supportUrl.orEmpty(),
                userinfo = meta.subscriptionUserinfo.orEmpty(),
            ),
        )
    }

    /**
     * Reads the persisted brand state and pushes a [BrandHolder] snapshot into
     * MainDesign. Picks the theme-appropriate cached logo. Called on every
     * UI refresh tick so the header reflects the latest operator brand without
     * the design layer having to know about persistence.
     */
    internal suspend fun applyActiveBrand() {
        val design = design as? com.github.kr328.clash.design.MainDesign ?: return
        // try/finally guarantees the recreate-check runs on EVERY exit path —
        // including the "no active profile" / "no brand for active profile"
        // branches. Otherwise a brand → no-brand switch sheds the UI layer but
        // leaves the theme overlay branded until the app is force-stopped.
        try {
            val activeUuid = runCatching { withProfile { queryActive() } }.getOrNull()?.uuid
            if (activeUuid == null) {
                design.applyBrand(com.github.kr328.clash.design.branding.BrandHolder.EMPTY)
                return
            }
            val json = runCatching { withProfile { readBrandJsonFor(activeUuid) } }.getOrNull()
            if (json.isNullOrBlank()) {
                design.applyBrand(com.github.kr328.clash.design.branding.BrandHolder.EMPTY)
                return
            }
            val manifest = com.github.kr328.clash.common.branding.BrandManifest.fromJson(json)
            val nightMode = (activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val logoPath = runCatching {
                withProfile { brandLogoPathFor(activeUuid, nightMode) }
            }.getOrNull()
            com.github.kr328.clash.common.log.Log.d(
                "applyActiveBrand: uuid=$activeUuid, nightMode=$nightMode, " +
                    "manifest.logoUrl=${manifest.logoUrl}, manifest.logoLightUrl=${manifest.logoLightUrl}, " +
                    "resolvedPath=$logoPath",
            )
            design.applyBrand(
                com.github.kr328.clash.design.branding.BrandHolder(
                    manifest = manifest,
                    logoPath = logoPath,
                ),
            )
        } finally {
            // Theme overlay is captured at design-inflation time. If the active profile's accent
            // diverges from what the current design's wrapper carries, resolve the dashboard loop
            // with a soft recreate so the new (or absent) harmonised palette flows into every
            // widget that reads ?attr/colorPrimary etc. — without ever destroying the Activity
            // (Android 16 ContentCapture SIGABRT). Runs on EVERY fetch, so switches, updates and
            // brand clears all converge through the same level-based check.
            if (com.github.kr328.clash.design.branding.BrandThemeApplier.accentStale(activity)) {
                activity.softRecreateRequests.trySend(Unit)
            }
        }
    }

    internal fun openExternalUrl(url: String) {
        val target = url.trim().let {
            if (it.startsWith("t.me/", ignoreCase = true)) "https://$it" else it
        }
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
