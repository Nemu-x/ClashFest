package com.github.kr328.clash.design

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.common.branding.BrandManifest
import com.github.kr328.clash.service.branding.BrandStore
import com.github.kr328.clash.service.store.ServiceStore
import java.io.File
import java.io.FileOutputStream
import androidx.core.view.doOnLayout
import com.google.android.material.R as MaterialR
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.SubscriptionUsage
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.BottomSheetMainModeBinding
import com.github.kr328.clash.design.databinding.BottomSheetSubscriptionBinding
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.HomeBackgroundStyle
import com.github.kr328.clash.design.model.AppLanguage
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.ClickGuard
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.patchDataSet
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.FlagParser
import com.github.kr328.clash.design.util.ParsedFlag
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProxyGroupPreviewRow
import com.github.kr328.clash.service.model.ProxyTransportInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.UUID

class MainDesign(context: Context) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenNewProfile,
        OpenSettings,
        OpenThemeSettings,
        OpenLanguageSettings,
        OpenAppSettings,
        OpenAbout,
        PatchModeDirect,
        PatchModeGlobal,
        PatchModeRule,
        OpenImportClipboard,
        OpenImportQr,
        CycleTheme,
        OpenLogs,
        OpenConnections,
        /** Routing rules list screen. */
        OpenRouting,
        RefreshRouting,
        OpenNewRule,
        OpenEffectiveRules,
        /** Rule snippets / editor screen. */
        OpenRules,
        /** Proxy chain screen. */
        OpenProxyChain,
        /** Direct entry to per-app routing for quick access from the routing tab. */
        OpenPerAppRouting,
        /** Subscriptions / profiles list. */
        OpenProfiles,
        /** Companion remote-control hub (quick entry surfaced on TV home). */
        OpenCompanion,
    }

    /** Public: MainActivity captures/restores the active tab across a soft recreate. */
    enum class MainTab {
        Home,
        Profiles,
        Routing,
        Operator,
        Settings,
    }

    /**
     * The set of tabs currently visible in bottom nav + ViewPager, in display order.
     * Default mode is 4 tabs (no Operator). Operator appears between Routing and
     * Settings when the operator brand is active; when the brand additionally sets
     * Hide-Routing=true, Operator replaces Routing in place (still 4 tabs).
     */
    private var activeTabs: List<MainTab> = DEFAULT_TABS

    private companion object {
        private val DEFAULT_TABS = listOf(MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Settings)

        /** ~30fps gate for the ambient power animations (33ms between rendered frames). */
        private const val AMBIENT_MIN_FRAME_NS = 33_000_000L
    }

    /** Set by MainActivity to react to taps on the in-header update badge. */
    var onUpdateBadgeTap: (() -> Unit)? = null

    val profileActivateRequests = Channel<Profile>(Channel.UNLIMITED)
    val profileMenuRequests = Channel<Pair<Profile, View>>(Channel.UNLIMITED)
    val profileEditRequests = Channel<Profile>(Channel.UNLIMITED)
    val patchHomeProxyRequests = Channel<Triple<Profile, String, String>>(Channel.CONFLATED)
    /**
     * A ping-all request. Was a Triple until the custom latency target arrived; [testUrl] is blank
     * for a normal tap and carries a one-shot host when the user long-pressed and typed one.
     */
    data class PingAllRequest(
        val profile: Profile,
        val group: String,
        val proxyNames: List<String>,
        val testUrl: String = "",
    )

    val profilePingAllRequests = Channel<PingAllRequest>(Channel.UNLIMITED)
    /** Single-node latency test (tap on a capsule): profile, group, proxy. */
    val proxyPingNodeRequests = Channel<Triple<Profile, String, String>>(Channel.UNLIMITED)
    val profileForceUpdateRequests = Channel<Profile>(Channel.UNLIMITED)
    val profileProxyYamlRequests = Channel<Triple<Profile, String, String>>(Channel.UNLIMITED)
    /** Fires when user expands/collapses any profile panel so the host can reload proxy previews. */
    val profileExpandChanged = Channel<Unit>(Channel.CONFLATED)
    val profileVisibleGroupChanged = Channel<Pair<Profile, String>>(Channel.CONFLATED)
    /** Profiles-tab manager: drag-reorder committed + update-all tapped. */
    val profileReorderRequests = Channel<List<Profile>>(Channel.UNLIMITED)
    val profileUpdateAllRequests = Channel<Unit>(Channel.UNLIMITED)

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    private val clickGuard = ClickGuard<Request>(600L, SystemClock::elapsedRealtime)
    private val subscriptionClickGuard = ClickGuard<Pair<UUID, Int>>(600L, SystemClock::elapsedRealtime)

    private var clashRunningState: Boolean = false
    private var tunnelStartingState: Boolean = false
    /**
     * Breath loop for the power button + concentric rings — slow infinite
     * "ripple outwards" pulse that plays **only** while the tunnel is
     * running. Each layer (button → halo → inner ring → outer ring) shares
     * the same 2s period but starts with an increasing [startDelay], so the
     * inhale visually radiates from the button outward instead of all four
     * layers pulsing in lockstep. Idle and connecting states pin every
     * animated property back to its static value — no motion distraction.
     *
     * Held as separate animators so we can cancel them all in one place.
     */
    private var powerBreathAnimator: ValueAnimator? = null
    private var powerHaloBreathAnimator: ValueAnimator? = null
    private var powerRingInnerBreathAnimator: ValueAnimator? = null
    private var powerRingOuterBreathAnimator: ValueAnimator? = null
    private var powerSweepAnimator: ValueAnimator? = null
    private var powerIconAnimator: ValueAnimator? = null
    private var powerIconProgress = 0f
    private var powerIconRunning: Boolean? = null

    /**
     * The three ambient power effects (button breath, halo breath, conic sweep) are driven by a
     * SINGLE animator that ticks the clock; each vsync we derive all three values from elapsed
     * time. This replaced three separate INFINITE ValueAnimators — a method trace showed their
     * per-vsync machinery (Keyframe/clampFraction ×3) dominating the main thread on the connected
     * dashboard. One driver = one machinery pass, and all view writes land in a single frame gate
     * (~30fps) so the redraw fires once, not three staggered times. Visual output is identical:
     * the formulas reproduce each animator's period, interpolator and phase exactly.
     */
    private var ambientStartNs = 0L
    private var lastAmbientFrameNs = 0L

    /**
     * Tracks the running flag the breath animators were last spun up for, so
     * repeated [applyPowerVisuals] calls (brand updates, profile refresh,
     * mode toggles) don't restart the breath mid-cycle and visibly blip the
     * rings. Only a real state transition (running ↔ not-running) tears down
     * and rebuilds the animators.
     */
    private var lastBreathRunning: Boolean? = null
    private var brandHolder: com.github.kr328.clash.design.branding.BrandHolder =
        com.github.kr328.clash.design.branding.BrandHolder.EMPTY
    private val uiStore = UiStore(context)
    private val expandedProfileUuids: LinkedHashSet<UUID> = linkedSetOf()
    private val subscriptionPreviewUuids: LinkedHashSet<UUID> = linkedSetOf()
    private val pendingSubscriptionPickers = mutableSetOf<UUID>()
    private var subscriptionDetailsDialog: AppBottomSheetDialog? = null
    private var subscriptionDetailsBinding: BottomSheetSubscriptionBinding? = null
    private var subscriptionDetailsUuid: UUID? = null
    private var activatingSubscriptionUuid: UUID? = null
    private var currentModeSegment: TunnelState.Mode = TunnelState.Mode.Rule
    private var suppressModeSegment = false
    private var activeProfileForQuickActions: Profile? = null
    private var activeAnnouncementSupportUrl: String? = null
    private var activeAnnouncementOnOpenUrl: ((String) -> Unit)? = null
    private var activeAnnouncementOnSupport: (() -> Unit)? = null

    private class StaticPageAdapter(
        private val pages: List<View>,
    ) : RecyclerView.Adapter<StaticPageAdapter.Holder>() {
        class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        private fun isBoundaryPage(position: Int): Boolean {
            val count = pages.size
            return position == 0 || position == count + 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return Holder(container)
        }

        private fun logicalPosition(position: Int): Int {
            val count = pages.size
            return when (position) {
                0 -> count - 1
                count + 1 -> 0
                else -> position - 1
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.container.removeAllViews()
            val page = pages[logicalPosition(position)]
            if (isBoundaryPage(position)) {
                // Circular wrap placeholders: avoid drawToBitmap (memory churn / jank on low-RAM devices).
                val bg = FrameLayout(holder.container.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(context.resolveThemedColor(MaterialR.attr.colorSurface))
                }
                holder.container.addView(bg)
                return
            }

            (page.parent as? ViewGroup)?.removeView(page)
            page.visibility = View.VISIBLE
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        override fun onViewRecycled(holder: Holder) {
            holder.container.removeAllViews()
        }

        override fun getItemCount(): Int = pages.size + 2
    }

    private val profileAdapter = ProfileAdapter(
        { profile -> profileActivateRequests.trySend(profile) },
        { profile, anchor -> profileMenuRequests.trySend(profile to anchor) },
        { profile -> toggleProfileExpand(profile) },
        { profile, group, proxyName ->
            patchHomeProxyRequests.trySend(Triple(profile, group, proxyName))
        },
        { profile, group, proxyNames, testUrl ->
            profilePingAllRequests.trySend(PingAllRequest(profile, group, proxyNames, testUrl))
        },
        { profile -> profileForceUpdateRequests.trySend(profile) },
        { profile, group, proxy -> profileProxyYamlRequests.trySend(Triple(profile, group, proxy)) },
        { profile, group -> profileVisibleGroupChanged.trySend(profile to group) },
        { profile, group, proxy -> proxyPingNodeRequests.trySend(Triple(profile, group, proxy)) },
        expandOnProfileClick = true,
    )

    /** Subscription cards open details; activation is an explicit action inside the sheet. */
    private val tabProfileAdapter = ProfileAdapter(
        { profile -> showSubscriptionDetails(profile) },
        { profile, anchor -> profileMenuRequests.trySend(profile to anchor) },
        { profile -> openSubscriptionPicker(profile) },
        { profile, group, proxyName ->
            patchHomeProxyRequests.trySend(Triple(profile, group, proxyName))
        },
        { profile, group, proxyNames, testUrl ->
            profilePingAllRequests.trySend(PingAllRequest(profile, group, proxyNames, testUrl))
        },
        { profile ->
            requestSubscriptionUpdate(profile)
        },
        { profile, group, proxy -> profileProxyYamlRequests.trySend(Triple(profile, group, proxy)) },
        { profile, group -> profileVisibleGroupChanged.trySend(profile to group) },
        { profile, group, proxy -> proxyPingNodeRequests.trySend(Triple(profile, group, proxy)) },
        expandOnProfileClick = false,
        compactSubscriptions = true,
    )
    private var tabProfilesAll: List<Profile> = emptyList()
    private val tabItemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(
        object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean =
                uiStore.profileSortMode == com.github.kr328.clash.design.model.ProfileSortMode.Manual

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = tabProfileAdapter.moveProfile(vh.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                profileReorderRequests.trySend(tabProfileAdapter.profiles.toList())
            }
        },
    )

    private fun sortTabProfiles(profiles: List<Profile>): List<Profile> =
        com.github.kr328.clash.design.util.ProfileOrdering.sortForDisplay(
            profiles = profiles,
            mode = uiStore.profileSortMode,
            active = Profile::active,
            name = Profile::name,
            updatedAt = Profile::updatedAt,
        )

    private fun tabSortModeTitle(mode: com.github.kr328.clash.design.model.ProfileSortMode): String =
        context.getString(
            when (mode) {
                com.github.kr328.clash.design.model.ProfileSortMode.Manual -> R.string.profiles_sort_manual
                com.github.kr328.clash.design.model.ProfileSortMode.ActiveFirst -> R.string.profiles_sort_active_first
                com.github.kr328.clash.design.model.ProfileSortMode.Name -> R.string.profiles_sort_name
                com.github.kr328.clash.design.model.ProfileSortMode.LastUpdated -> R.string.profiles_sort_last_updated
            },
        )

    private fun showProfilesTabSort(anchor: View) {
        val popup = android.widget.PopupMenu(context, anchor)
        popup.menu.add(0, 1000, 0, R.string.subscriptions_update_all).isEnabled =
            !tabProfileAdapter.states.allUpdating &&
                tabProfilesAll.any { it.imported && it.type != Profile.Type.File }
        val modes = com.github.kr328.clash.design.model.ProfileSortMode.values()
        modes.forEachIndexed { i, m -> popup.menu.add(1, i, i + 1, tabSortModeTitle(m)).isCheckable = true }
        val current = uiStore.profileSortMode.ordinal
        popup.menu.findItem(current).isChecked = true
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1000) {
                requestAllSubscriptionUpdates()
                return@setOnMenuItemClickListener true
            }
            uiStore.profileSortMode = modes.getOrElse(item.itemId) {
                com.github.kr328.clash.design.model.ProfileSortMode.Manual
            }
            tabProfileAdapter.profiles = sortTabProfiles(tabProfilesAll)
            tabProfileAdapter.notifyDataSetChanged()
            true
        }
        popup.show()
    }

    override val root: View
        get() = binding.root

    fun getExpandedProfileUuids(): Set<UUID> = (expandedProfileUuids + subscriptionPreviewUuids).toSet()

    /**
     * Pushes operator-pushed subscription metadata into the home UI and the active profile card.
     */
    suspend fun setAnnouncement(
        text: String?,
        url: String?,
        supportUrl: String? = null,
        sourceUuid: UUID? = null,
        sourceName: String? = null,
        onOpenUrl: ((String) -> Unit)? = null,
        onSupport: (() -> Unit)? = null,
    ) {
        withContext(Dispatchers.Main) {
            val message = text
                ?.takeIf { it.isNotBlank() }
                ?.let { com.github.kr328.clash.common.util.MaybeBase64.decode(it).trim() }
                .orEmpty()
            val announcementUrl = url?.takeIf { it.isNotBlank() }
            val support = supportUrl?.takeIf { it.isNotBlank() }
            val hasAnnouncement = message.isNotBlank()
            activeAnnouncementSupportUrl = support
            activeAnnouncementOnOpenUrl = onOpenUrl
            activeAnnouncementOnSupport = onSupport

            binding.mainHeaderSummary.visibility = View.GONE
            binding.mainHeaderSummary.setOnClickListener(null)
            binding.mainHeaderSummary.isClickable = false

            binding.mainAnnouncementCard.visibility = if (hasAnnouncement) View.VISIBLE else View.GONE
            if (hasAnnouncement) {
                binding.mainAnnouncementPreview.text = message.replace('\n', ' ')

                val hash = announcementHash(message, announcementUrl, support, sourceName)
                val unread = sourceUuid != null && uiStore.announcementReadHashFor(sourceUuid) != hash
                binding.mainAnnouncementNewDot.visibility = if (unread) View.VISIBLE else View.GONE

                binding.mainAnnouncementCard.setOnClickListener {
                    if (sourceUuid != null) {
                        uiStore.setAnnouncementReadHashFor(sourceUuid, hash)
                        binding.mainAnnouncementNewDot.visibility = View.GONE
                    }
                    com.github.kr328.clash.design.dialog.AnnouncementSheet.show(
                        context = context,
                        text = message,
                        url = announcementUrl,
                        supportUrl = support,
                        sourceName = sourceName,
                        onOpenUrl = { target -> onOpenUrl?.invoke(target) },
                        onSupport = onSupport.takeIf { support != null || onSupport != null },
                    )
                }
            } else {
                binding.mainAnnouncementCard.setOnClickListener(null)
                binding.mainAnnouncementNewDot.visibility = View.GONE
            }

            profileAdapter.setActiveAnnouncement(
                text = text,
                url = url,
                supportUrl = supportUrl,
                onOpenUrl = onOpenUrl,
                onSupport = onSupport,
            )
            renderActiveProfileCard(activeProfileForQuickActions)
        }
    }

    private fun announcementHash(
        message: String,
        url: String?,
        supportUrl: String?,
        source: String?,
    ): String {
        val payload = "$message\u0000${url.orEmpty()}\u0000${supportUrl.orEmpty()}\u0000${source.orEmpty()}"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(payload.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            val hex = "0123456789abcdef"
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(hex[v ushr 4]); append(hex[v and 0x0F])
            }
        }
    }

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    /**
     * Apply the latest operator-brand snapshot to the main screen surfaces:
     * operator tagline, profile cards, operator page and power-button accent.
     * The main header keeps the app name and logo defined in the layout.
     */
    var onOpenBrandUrl: ((String) -> Unit)? = null
    var onOpenAbout: (() -> Unit)? = null

    suspend fun applyBrand(holder: com.github.kr328.clash.design.branding.BrandHolder) {
        withContext(Dispatchers.Main) {
            brandHolder = holder
            renderBrandHeader()
            // BrandThemeApplier installs the M3 harmonised palette plus the
            // neutral-surface overlay at Activity onCreate / after recreate.
            // Once that overlay is in the theme, every widget reading
            // ?attr/colorPrimary etc. picks up the brand automatically — no
            // programmatic walker needed. Power button is the one place
            // where we explicitly override (running state) because the
            // attrs we picked for it are intentionally state-aware in code,
            // not in XML.
            applyPowerVisuals()
            reconcileTabsForBrand()
            reconcileGlobalModeForBrand()
            renderOperatorPage()
            profileAdapter.setBrandManifest(holder.manifest) { url ->
                onOpenBrandUrl?.invoke(url)
            }
        }
    }

    private fun renderOperatorPage() {
        if (!brandHolder.isActive) return
        val brand = brandHolder.manifest

        // Logo
        val logo = binding.operatorLogo
        val logoPath = brandHolder.logoPath
        if (logoPath != null) {
            com.github.kr328.clash.design.branding.BrandLogoBinder.bind(logo, logoPath)
            logo.visibility = View.VISIBLE
        } else {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
        }

        // Brand name (fallback to ClashFest if operator didn't supply — the page only
        // exists when brand is active, so something meaningful should always be shown).
        binding.operatorName.text = brand.name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.launch_name_meta)

        val tagline = brand.tagline?.takeIf { it.isNotBlank() }
        if (tagline != null) {
            binding.operatorTagline.text = tagline
            binding.operatorTagline.visibility = View.VISIBLE
        } else {
            binding.operatorTagline.visibility = View.GONE
        }

        // Greeting hero line. Operators typically wire this through a panel
        // template variable so the panel substitutes the user's name / days
        // remaining / etc. into the header before sending. If the operator
        // only supplied a display name (no greeting), fall back to a built-in
        // "Hello, <name>!" so the hero block still feels personal.
        val greeting = brand.greeting?.takeIf { it.isNotBlank() }
            ?: brand.userDisplayName?.takeIf { it.isNotBlank() }?.let {
                context.getString(R.string.operator_greeting_default, it)
            }
        if (greeting != null) {
            binding.operatorGreeting.text = greeting
            binding.operatorGreeting.visibility = View.VISIBLE
        } else {
            binding.operatorGreeting.visibility = View.GONE
        }

        // Renew CTA (primary action)
        val renew = brand.renewUrl?.takeIf { it.isNotBlank() }
        if (renew != null) {
            binding.operatorRenewButton.visibility = View.VISIBLE
            binding.operatorRenewButton.setOnClickListener { onOpenBrandUrl?.invoke(renew) }
        } else {
            binding.operatorRenewButton.visibility = View.GONE
        }

        // Personal cabinet entry-point. Stays tonal-secondary even when Renew
        // is absent — the visual difference between Renew (filled) and
        // Cabinet (tonal) is a deliberate hierarchy cue: Renew converts the
        // user into a paying / renewing customer, Cabinet only navigates.
        // `profile-web-page-url` (common namespace, parsed for a long time but never surfaced) is
        // the fallback when the operator did not ship a dedicated X-Brand-Cabinet-URL.
        val cabinet = brand.cabinetUrl?.takeIf { it.isNotBlank() }
            ?: uiStore.profileWebPageUrl.takeIf { it.isNotBlank() }
        if (cabinet != null) {
            binding.operatorCabinetButton.visibility = View.VISIBLE
            binding.operatorCabinetButton.setOnClickListener { onOpenBrandUrl?.invoke(cabinet) }
        } else {
            binding.operatorCabinetButton.visibility = View.GONE
        }

        // Grouped link rows. Each row gets a leading icon and a trailing chevron;
        // groups are separated by small headers so the user can scan the page
        // by intent (talk to humans / read docs / check status).
        val container = binding.operatorLinks
        container.removeAllViews()

        val operatorLinks = listOfNotNull(
            brand.websiteUrl?.let { OperatorLink(it, R.string.about_brand_website, R.drawable.ic_baseline_language) },
            brand.supportUrl?.let { OperatorLink(it, R.string.about_brand_support, R.drawable.ic_baseline_headset_mic) },
            brand.telegramUrl?.let { OperatorLink(it, R.string.about_brand_telegram, R.drawable.ic_baseline_campaign) },
            brand.botUrl?.let { OperatorLink(it, R.string.about_brand_bot, R.drawable.ic_baseline_subscriptions) },
        )
        val helpLinks = listOfNotNull(
            brand.helpUrl?.let { OperatorLink(it, R.string.about_brand_help, R.drawable.ic_outline_info) },
            brand.statusUrl?.let { OperatorLink(it, R.string.about_brand_status, R.drawable.ic_baseline_info) },
        )
        val legalLinks = listOfNotNull(
            brand.privacyUrl?.let { OperatorLink(it, R.string.about_brand_privacy, R.drawable.ic_outline_article) },
            brand.termsUrl?.let { OperatorLink(it, R.string.about_brand_terms, R.drawable.ic_baseline_article) },
        )

        addOperatorGroup(container, R.string.operator_group_contact, operatorLinks)
        addOperatorGroup(container, R.string.operator_group_help, helpLinks)
        addOperatorGroup(container, R.string.operator_group_legal, legalLinks)
    }

    private data class OperatorLink(
        val url: String,
        val labelRes: Int,
        val iconRes: Int,
    )

    private fun addOperatorGroup(
        container: android.widget.LinearLayout,
        titleRes: Int,
        links: List<OperatorLink>,
    ) {
        if (links.isEmpty()) return
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        // Group header — Material Title-Small in colorOnSurfaceVariant
        val header = android.widget.TextView(context).apply {
            text = context.getString(titleRes)
            setTextAppearance(R.style.TextAppearance_App_LabelLarge)
            setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(px(4), px(14), px(4), px(6))
            isAllCaps = true
            letterSpacing = 0.08f
            textSize = 12f
        }
        container.addView(header)

        // Rows wrapper card so dividers render cleanly between rows
        val card = com.google.android.material.card.MaterialCardView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            radius = px(14).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(context.resolveThemedColor(MaterialR.attr.colorSurfaceContainerHigh))
        }
        val cardInner = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        card.addView(cardInner)
        container.addView(card)

        links.forEachIndexed { i, link ->
            cardInner.addView(buildOperatorRow(link))
            if (i < links.lastIndex) {
                val divider = View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, px(1),
                    ).apply { setMargins(px(48), 0, 0, 0) }
                    setBackgroundColor(
                        context.resolveThemedColor(MaterialR.attr.colorOutlineVariant)
                    )
                }
                cardInner.addView(divider)
            }
        }
    }

    private fun buildOperatorRow(link: OperatorLink): View {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
            background = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(context, R.drawable.bg_proxy_node_row)
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenBrandUrl?.invoke(link.url) }
        }
        // Leading icon
        val icon = android.widget.ImageView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(px(22), px(22)).apply {
                marginEnd = px(14)
            }
            setImageResource(link.iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                context.resolveThemedColor(MaterialR.attr.colorPrimary)
            )
        }
        row.addView(icon)
        // Label takes remaining width
        val label = android.widget.TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            text = context.getString(link.labelRes)
            setTextAppearance(R.style.TextAppearance_App_BodyMedium)
            setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurface))
        }
        row.addView(label)
        // Trailing chevron
        val chevron = android.widget.ImageView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(px(16), px(16))
            setImageResource(R.drawable.ic_baseline_expand_more)
            rotation = -90f
            imageTintList = android.content.res.ColorStateList.valueOf(
                context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant)
            )
        }
        row.addView(chevron)
        return row
    }

    private fun renderBrandHeader() {
        val brand = brandHolder.manifest

        // Tagline: only show / write when operator supplied one.
        val taglineView = binding.mainHeaderTagline
        val tagline = brand.tagline?.takeIf { it.isNotBlank() }
        if (tagline != null) {
            taglineView.text = tagline
            taglineView.visibility = View.VISIBLE
        } else {
            taglineView.visibility = View.GONE
        }
    }

    /** Active operator accent (validated + contrast-OK) or null. Used by applyPowerVisuals. */
    private fun brandAccentColor(): Int? {
        val hex = brandHolder.manifest.accentColor?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() ?: return null
        val surface = context.resolveThemedColor(MaterialR.attr.colorSurface)
        return if (com.github.kr328.clash.common.branding.BrandValidation
                .hasMinContrast(parsed, surface)
        ) parsed else null
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            clashRunningState = running
            binding.clashRunning = running
            if (running) {
                tunnelStartingState = false
                binding.tunnelStarting = false
            }
            applyPowerVisuals()
        }
    }

    suspend fun setTunnelStarting(starting: Boolean) {
        withContext(Dispatchers.Main) {
            tunnelStartingState = starting
            binding.tunnelStarting = starting
            applyPowerVisuals()
        }
    }

    suspend fun setPingingProfile(uuid: UUID?) {
        withContext(Dispatchers.Main) {
            profileAdapter.setPingingUuid(uuid)
            tabProfileAdapter.setPingingUuid(uuid)
        }
    }

    private fun applyPowerVisuals() {
        val button = binding.mainPowerCard
        val running = clashRunningState
        val starting = tunnelStartingState && !running
        val elevationDp = when {
            running -> 10f
            starting -> 8f
            else -> 7f
        }
        val accentBg = brandAccentColor() ?: context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val bgColor = context.resolveThemedColor(MaterialR.attr.colorSurfaceContainerHighest)
        button.backgroundTintList = ColorStateList.valueOf(bgColor)
        button.elevation = elevationDp * context.resources.displayMetrics.density
        button.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
        animatePowerIcon(running, bgColor, accentBg)
        binding.powerSheen.alpha = when {
            running -> 0.35f
            starting -> 0.25f
            else -> 0.22f
        }
        val overlayAlpha = if (running) 0.25f else 0.0f
        binding.powerDome.animate().alpha(overlayAlpha).setDuration(240L).start()
        binding.powerSweep.backgroundTintList = null
        binding.powerSweep.animate().alpha(overlayAlpha).setDuration(240L).start()
        // Live speed shows only while connected (redesign 1.0, #101).
        binding.mainSpeed.visibility = if (running) View.VISIBLE else View.GONE

        val targetAlpha = when {
            running -> 0.34f
            starting -> 0.24f
            else -> 0.12f
        }
        val targetScale = when {
            running -> 1.08f
            starting -> 1.04f
            else -> 0.96f
        }
        // Halo: when running, the breath loop owns the alpha channel — we
        // only ramp scale here. When idle/connecting, breath is dormant so
        // we drive alpha normally (ramp to targetAlpha).
        binding.powerHalo.animate().cancel()
        val haloAnim = binding.powerHalo.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(260L)
        if (!running) {
            haloAnim.alpha(targetAlpha)
        }
        haloAnim.start()

        // Lumen single-glow: the outer ring is retired (kept in the view tree at alpha 0). The
        // halo is the ONE soft glow — when running it tints to the active accent for the
        // "lamp is on" feel; idle stays neutral.
        binding.powerRingOuter.animate().cancel()
        binding.powerRingOuter.alpha = 0f

        val glowTint = if (running) {
            accentBg
        } else {
            context.resolveThemedColor(com.google.android.material.R.attr.colorSurfaceVariant)
        }
        binding.powerHalo.backgroundTintList = ColorStateList.valueOf(glowTint)
        // The faint idle ambient ring (inner) reads neutral, never accent.
        binding.powerRingInner.backgroundTintList = ColorStateList.valueOf(
            context.resolveThemedColor(com.google.android.material.R.attr.colorOutline),
        )

        // Status pill: accent-tinted background + on-primary text when running,
        // neutral chip otherwise. Subtle but immediately readable as "active".
        val statusBgRes = if (running) R.drawable.bg_m3_status_chip else R.drawable.bg_m3_status_chip_neutral
        binding.mainStatusLabel.setBackgroundResource(statusBgRes)
        val statusTextColor = if (running) {
            context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        } else {
            context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        binding.mainStatusLabel.setTextColor(statusTextColor)

        updateBreathAnimator(running, starting)
    }

    private fun animatePowerIcon(running: Boolean, background: Int, accent: Int) {
        val button = binding.mainPowerCard
        val icon = button.icon ?: return
        button.iconTint = null
        val gray = ContextCompat.getColor(context, R.color.mikan_power_icon_idle)
        val idleRim = ColorUtils.blendARGB(background, Color.WHITE, 0.10f)
        fun render(progress: Float) {
            powerIconProgress = progress
            val grayWeight = 1f - progress
            // Blend every original SVG color with gray, keeping the silhouette opaque.
            icon.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                progress, 0f, 0f, 0f, Color.red(gray) * grayWeight,
                0f, progress, 0f, 0f, Color.green(gray) * grayWeight,
                0f, 0f, progress, 0f, Color.blue(gray) * grayWeight,
                0f, 0f, 0f, 1f, 0f,
            )))
            button.setStrokeColor(ColorStateList.valueOf(ColorUtils.blendARGB(idleRim, accent, progress)))
        }
        val previous = powerIconRunning
        powerIconRunning = running
        val target = if (running) 1f else 0f
        if (previous == null || !button.isAttachedToWindow ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())
        ) {
            powerIconAnimator?.cancel()
            powerIconAnimator = null
            render(target)
        } else if (previous != running) {
            powerIconAnimator?.cancel()
            powerIconAnimator = ValueAnimator.ofFloat(powerIconProgress, target).apply {
                duration = 560L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { render(it.animatedValue as Float) }
                start()
            }
        } else {
            render(powerIconProgress)
        }
    }

    /**
     * (Re)starts the power-button + concentric-ring breath loop when the
     * tunnel is running. Each ring uses an increasing [startDelay] so the
     * pulse visually ripples outward from the button rather than every
     * layer beating in lockstep. In idle / connecting we cancel everything
     * and pin properties back to their static values so nothing wobbles.
     *
     * Animators are recreated rather than mutated so each new state change
     * starts at a known frame instead of jumping from a mid-cycle value.
     */
    private fun updateBreathAnimator(requestedRunning: Boolean, starting: Boolean) {
        val running = requestedRunning && !clickGuard.isOpen(Request.OpenAbout)
        // Skip rebuild when the state hasn't actually changed — keeps the
        // breath loop running smoothly through unrelated applyPowerVisuals
        // calls (brand refresh, mode change, etc).
        if (lastBreathRunning == running && powerBreathAnimator?.isRunning == true) {
            return
        }
        lastBreathRunning = running

        powerBreathAnimator?.cancel()
        powerHaloBreathAnimator?.cancel()
        powerRingInnerBreathAnimator?.cancel()
        powerRingOuterBreathAnimator?.cancel()
        powerSweepAnimator?.cancel()
        powerBreathAnimator = null
        powerHaloBreathAnimator = null
        powerRingInnerBreathAnimator = null
        powerRingOuterBreathAnimator = null
        powerSweepAnimator = null

        val button = binding.mainPowerCard
        val halo = binding.powerHalo
        val innerRing = binding.powerRingInner
        val outerRing = binding.powerRingOuter

        // Outer ring is retired in the single-glow design — always dark.
        outerRing.animate().cancel()
        outerRing.alpha = 0f

        if (!running) {
            // Idle / connecting: no ripple. A faint neutral ambient ring sits under the raised
            // button (Lumen); the halo glow is dim (its alpha is driven in applyPowerVisuals).
            button.scaleX = 1.0f
            button.scaleY = 1.0f
            innerRing.animate().cancel()
            innerRing.animate()
                .alpha(0.18f)
                .setDuration(260L)
                .start()
            return
        }

        // Running: ONE soft glow (Lumen). No ring ripple — the halo breathes slowly for a calm
        // "lamp is on" feel and the button gives a gentle scale. Rings stay dark. A longer period
        // (2.6s) reads premium/unhurried vs the old 2s ripple.
        val period = 2600L
        button.scaleX = 1.0f
        button.scaleY = 1.0f
        innerRing.animate().cancel()
        innerRing.alpha = 0.0f
        halo.alpha = 0.20f

        // One driver ticks the clock; the gated listener derives button scale (breath, 2.6s
        // REVERSE accel-decel), halo alpha (same, +120ms phase) and sweep rotation (4.6s linear)
        // from elapsed time — reproducing the three former animators without their per-vsync cost.
        val breath = AccelerateDecelerateInterpolator()
        val sweep = binding.powerSweep
        ambientStartNs = System.nanoTime()
        lastAmbientFrameNs = 0L
        powerBreathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = period
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                if (now - lastAmbientFrameNs < AMBIENT_MIN_FRAME_NS) return@addUpdateListener
                lastAmbientFrameNs = now
                val elapsedMs = (now - ambientStartNs) / 1_000_000L
                // REVERSE triangle over 2*period, accel-decel on each half — matches ofFloat REVERSE.
                fun breathe(ms: Long): Float {
                    if (ms < 0) return 0f
                    val cyc = ms % (2 * period)
                    val tri = if (cyc < period) cyc.toFloat() / period else (2 * period - cyc).toFloat() / period
                    return breath.getInterpolation(tri)
                }
                val s = 1.0f + 0.04f * breathe(elapsedMs)
                button.scaleX = s
                button.scaleY = s
                halo.alpha = 0.20f + 0.20f * breathe(elapsedMs - 120L)
                sweep.rotation = (elapsedMs % 4600L).toFloat() / 4600f * 360f
            }
            start()
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    /** Live ↓/↑ speed under the connect hero (redesign 1.0, issue #101). */
    suspend fun setSpeed(down: String, up: String) {
        withContext(Dispatchers.Main) {
            binding.mainSpeed.text = context.getString(R.string.main_speed_fmt, down, up)
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            val normalized = normalizeMode(mode)
            currentModeSegment = normalized
            binding.mode = when (normalized) {
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                else -> context.getString(R.string.rule_mode)
            }
            // Reflect on the segmented control without re-triggering the change request.
            suppressModeSegment = true
            binding.mainModeSegment.check(
                if (normalized == TunnelState.Mode.Global) R.id.main_mode_global else R.id.main_mode_rule,
            )
            suppressModeSegment = false
        }
    }

    /**
     * Applies saved tunnel mode preference to the dashboard before the first [fetch],
     * so mode chips do not briefly show a stale default while the core state loads.
     */
    suspend fun applyInitialModeFromPreference(pref: String?) {
        val mode = when (pref) {
            TunnelState.Mode.Rule.name -> TunnelState.Mode.Rule
            TunnelState.Mode.Global.name -> TunnelState.Mode.Global
            TunnelState.Mode.Direct.name -> TunnelState.Mode.Rule
            else -> return
        }
        setMode(mode)
    }

    private fun normalizeMode(mode: TunnelState.Mode): TunnelState.Mode =
        if (mode == TunnelState.Mode.Direct) TunnelState.Mode.Rule else mode

    suspend fun syncThemeToggleIcon(mode: DarkMode) {
        withContext(Dispatchers.Main) {
            binding.btnThemeToggle.text = context.getString(
                when (mode) {
                    DarkMode.ForceLight -> R.string.main_emoji_moon
                    else -> R.string.main_emoji_sun
                },
            )
        }
    }

    suspend fun patchProfiles(profiles: List<Profile>) {
        withContext(Dispatchers.Main) {
            binding.hasProfiles = profiles.isNotEmpty()
            val homeProfiles = profiles.filter { it.active }.ifEmpty { profiles.take(1) }
            activeProfileForQuickActions = profiles.firstOrNull { it.active } ?: profiles.firstOrNull()
            renderActiveProfileCard(activeProfileForQuickActions)
            profileAdapter.apply {
                val ids = homeProfiles.map { it.uuid }.toSet()
                expandedProfileUuids.retainAll { it in ids }
                patchDataSet(this::profiles, homeProfiles, id = { it.uuid })
            }
            profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())

            tabProfilesAll = profiles
            tabProfileAdapter.apply {
                val ids = profiles.map { it.uuid }.toSet()
                subscriptionPreviewUuids.retainAll(ids)
                states.retainProfiles(ids)
                patchDataSet(this::profiles, sortTabProfiles(profiles), id = { it.uuid })
            }
            tabProfileAdapter.setExpandedUuids(subscriptionPreviewUuids.toSet())
            binding.profilesTabList.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
            binding.profilesTabEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            val tabUpdatable = profiles.any { it.imported && it.type != Profile.Type.File }
            binding.profilesTabRefresh.isEnabled = tabUpdatable
            refreshSubscriptionDetails()
        }
    }

    private fun renderActiveProfileCard(profile: Profile?) {
        val p = profile
        binding.mainActiveProfileValue.text = p?.name.orEmpty().ifBlank { context.getString(R.string.not_selected) }
        binding.mainActiveProfileMeta.text = p?.let { usageLabel(it) }.orEmpty()
        binding.mainActiveProfileMeta.visibility = if (p != null) View.VISIBLE else View.GONE
        val showUpdate = p?.imported == true && p.type != Profile.Type.File
        binding.mainActiveProfileUpdate.visibility = if (showUpdate) View.VISIBLE else View.GONE
        val showSupport = !resolveSupportUrl().isNullOrBlank()
        binding.mainActiveProfileSupport.visibility = if (showSupport) View.VISIBLE else View.GONE
        binding.mainActiveProfileActions.visibility = if (showUpdate || showSupport) View.VISIBLE else View.GONE
        // Redesign 1.0: Node row shows the active node as a circular flag icon (like the picker) +
        // the clean name, and opens the proxy picker for the active profile (loads groups first).
        val nodeName = p?.let { profileAdapter.activeNodeDisplayName(it) }
        val nodeFlag = FlagParser.parse(nodeName)
        bindMainNodeFlag(nodeFlag)
        val cleanName = nodeName?.let { n ->
            nodeFlag?.let { n.removePrefix(it.emoji).trimStart(' ', '|', '-', '_', '.', ':').ifBlank { n } } ?: n
        }
        binding.mainActiveNodeValue.text = cleanName ?: context.getString(R.string.main_node_choose)
        binding.mainActiveNodeRow.setOnClickListener { p?.let { openNodePicker(it) } }
    }

    /** SVG → [ImageView.setImageBitmap] at the view's pixel width; emoji/globe fallbacks otherwise. */
    private fun bindMainNodeFlag(flag: ParsedFlag?) {
        val image = binding.mainActiveNodeFlag
        val emoji = binding.mainActiveNodeFlagEmoji

        fun applyGlobe() {
            emoji.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (9 * context.resources.displayMetrics.density).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.setImageResource(R.drawable.ic_baseline_language)
            image.imageTintList = ColorStateList.valueOf(
                context.resolveThemedColor(MaterialR.attr.colorPrimary),
            )
        }

        if (flag == null) {
            applyGlobe()
            return
        }

        fun applyFlag(sizePx: Int) {
            if (sizePx <= 0) return
            val bitmap = FlagDrawableLoader.loadBitmap(context, flag.code, sizePx)
            if (bitmap != null) {
                image.setPadding(0, 0, 0, 0)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.imageTintList = null
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                emoji.visibility = View.GONE
            } else {
                image.visibility = View.GONE
                emoji.text = flag.emoji
                emoji.visibility = View.VISIBLE
            }
        }

        if (image.width > 0) {
            applyFlag(image.width)
        } else {
            image.doOnLayout { applyFlag(it.width.coerceAtLeast(1)) }
        }
    }

    private fun resolveSupportUrl(): String? =
        activeAnnouncementSupportUrl?.takeIf { it.isNotBlank() }
            ?: uiStore.supportUrl.takeIf { it.isNotBlank() }

    private fun usageLabel(profile: Profile): String {
        val headerUsage = if (profile.type == Profile.Type.Url) {
            com.github.kr328.clash.common.util.SubscriptionUsage.parse(
                uiStore.subscriptionUserinfo.takeIf { it.isNotBlank() }
            )
        } else null
        val used = headerUsage?.used ?: (profile.upload + profile.download)
        val usedText = used.toBytesString(context)
        val total = headerUsage?.total ?: profile.total
        if (total < 2L) return context.getString(R.string.subscription_usage_format, usedText, context.getString(R.string.sub_announcement_unlimited))
        return context.getString(R.string.subscription_usage_format, usedText, total.toBytesString(context))
    }

    suspend fun patchProxyGroups(
        names: List<String>,
        running: Boolean,
        mode: TunnelState.Mode?,
        lastGroupHint: String?,
        offlinePreviewByProfile: Map<UUID, Map<String, ProxyGroupPreviewRow>> = emptyMap(),
        activeProfileUuid: UUID? = null,
        offlineSelectionsByProfile: Map<UUID, Map<String, String>> = emptyMap(),
        transportInfoByProfile: Map<UUID, Map<String, ProxyTransportInfo>> = emptyMap(),
    ) {
        withContext(Dispatchers.Main) {
            profileAdapter.setProxyContext(
                names,
                running,
                mode,
                lastGroupHint,
                offlinePreviewByProfile,
                activeProfileUuid,
                offlineSelectionsByProfile,
                transportInfoByProfile,
                // Same flag [names] was queried with (MainActivity -> queryProxyGroupNames), so the
                // offline preview hides exactly the groups the engine hides.
                uiStore.proxyExcludeNotSelectable,
            )
            profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
            tabProfileAdapter.setProxyContext(
                names,
                running,
                mode,
                lastGroupHint,
                offlinePreviewByProfile,
                activeProfileUuid,
                offlineSelectionsByProfile,
                transportInfoByProfile,
                // Same flag [names] was queried with (MainActivity -> queryProxyGroupNames), so the
                // offline preview hides exactly the groups the engine hides.
                uiStore.proxyExcludeNotSelectable,
            )
            refreshSubscriptionDetails()
        }
    }

    suspend fun patchProxyDetails(details: Map<String, ProxyGroup>) {
        withContext(Dispatchers.Main) {
            profileAdapter.setProxyDetails(details)
            tabProfileAdapter.setProxyDetails(details)
            refreshSubscriptionDetails()
        }
    }

    /** Group whose selection the Home "Node" row summarises for [profile] (for connect-time priming). */
    fun summaryGroupFor(profile: Profile): String? = profileAdapter.summaryGroupForProfile(profile)

    /** True when live engine detail for [groupName] is already cached (skip the connect-time prime). */
    fun hasLiveDetailForGroup(groupName: String): Boolean = profileAdapter.hasLiveDetailForGroup(groupName)

    suspend fun patchSingleProxyDelay(group: String, proxy: String, delayMs: Int) {
        withContext(Dispatchers.Main) {
            profileAdapter.patchSingleProxyDelay(group, proxy, delayMs)
            tabProfileAdapter.patchSingleProxyDelay(group, proxy, delayMs)
        }
    }

    /** Drops the "…" of a single-node test that produced no measurement (offline miss, engine error). */
    suspend fun clearNodePingPending(proxy: String) {
        withContext(Dispatchers.Main) {
            profileAdapter.completeNodePing(proxy)
            tabProfileAdapter.completeNodePing(proxy)
        }
    }

    suspend fun clearProxyDetails() {
        withContext(Dispatchers.Main) {
            profileAdapter.clearProxyDetails()
        }
    }

    suspend fun markProxySelectionPending(profile: Profile, group: String, proxy: String) {
        withContext(Dispatchers.Main) {
            profileAdapter.setPendingProxySelection(profile.uuid, group, proxy)
        }
    }

    suspend fun clearStandalonePingForProfile(uuid: UUID) {
        withContext(Dispatchers.Main) {
            profileAdapter.clearStandalonePingDelays(uuid)
            tabProfileAdapter.clearStandalonePingDelays(uuid)
        }
    }

    suspend fun patchStandalonePingResults(uuid: UUID, results: Map<String, Int>) {
        withContext(Dispatchers.Main) {
            profileAdapter.setStandalonePingResults(uuid, results)
            tabProfileAdapter.setStandalonePingResults(uuid, results)
        }
    }

    private fun toggleProfileExpand(profile: Profile) {
        if (!profile.imported) {
            return
        }
        val hadGroups = profileAdapter.hasProxyGroupsFor(profile)
        val needsRefresh = expandedProfileUuids.add(profile.uuid)
        profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
        if (needsRefresh || !hadGroups) {
            profileExpandChanged.trySend(Unit)
        }
        openProxySheetWhenReady(profile)
    }

    /** Load this subscription's groups before opening the searchable server picker. */
    private fun openSubscriptionPicker(profile: Profile, attempt: Int = 0) {
        if (!profile.imported) return
        if (attempt > 0 && profile.uuid !in pendingSubscriptionPickers) return
        if (attempt == 0) {
            if (!pendingSubscriptionPickers.add(profile.uuid)) return
            subscriptionPreviewUuids.add(profile.uuid)
            tabProfileAdapter.setExpandedUuids(subscriptionPreviewUuids.toSet())
            profileExpandChanged.trySend(Unit)
            refreshSubscriptionDetails()
        }
        val current = tabProfilesAll.firstOrNull { it.uuid == profile.uuid }
        if (current == null) {
            pendingSubscriptionPickers.remove(profile.uuid)
            return
        }
        if (tabProfileAdapter.hasProxyGroupsFor(current) || attempt >= 6) {
            pendingSubscriptionPickers.remove(profile.uuid)
            refreshSubscriptionDetails()
            tabProfileAdapter.showProxySheet(context, current)
        } else {
            binding.profilesTabList.postDelayed({ openSubscriptionPicker(current, attempt + 1) }, 400L)
        }
    }

    private fun showSubscriptionDetails(profile: Profile) {
        if (subscriptionDetailsDialog?.isShowing == true) return
        val sheet = BottomSheetSubscriptionBinding.inflate(context.layoutInflater)
        val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
        subscriptionDetailsBinding = sheet
        subscriptionDetailsUuid = profile.uuid
        subscriptionDetailsDialog = dialog
        subscriptionPreviewUuids.clear()
        subscriptionPreviewUuids.add(profile.uuid)
        tabProfileAdapter.setExpandedUuids(subscriptionPreviewUuids.toSet())
        profileExpandChanged.trySend(Unit)
        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener {
            pendingSubscriptionPickers.remove(profile.uuid)
            subscriptionDetailsBinding = null
            subscriptionDetailsUuid = null
            subscriptionDetailsDialog = null
        }
        refreshSubscriptionDetails()
        dialog.show()
    }

    private fun refreshSubscriptionDetails() {
        val sheet = subscriptionDetailsBinding ?: return
        val profile = tabProfilesAll.firstOrNull { it.uuid == subscriptionDetailsUuid }
        if (profile == null) {
            subscriptionDetailsDialog?.dismiss()
            return
        }
        val updating = tabProfileAdapter.states.isUpdating(profile.uuid)
        val failed = tabProfileAdapter.states.hasUpdateError(profile.uuid)
        val activating = activatingSubscriptionUuid == profile.uuid
        sheet.detailName.text = profile.name
        sheet.detailStatus.setText(if (profile.active) R.string.subscription_in_use else R.string.subscription_not_in_use)
        sheet.detailUsage.text = if (profile.imported) tabProfileAdapter.formatUsageLine(context, profile)
            else context.getString(R.string.subscription_not_ready)
        val hasLimit = profile.imported && profile.total >= 2L
        sheet.detailUsageProgress.visibility = if (hasLimit) View.VISIBLE else View.GONE
        sheet.detailUsageProgress.progress =
            (((profile.upload + profile.download).toDouble() / profile.total.coerceAtLeast(1L))
                .coerceIn(0.0, 1.0) * 1000).toInt()
        sheet.detailExpiry.visibility = if (profile.expire > 0L) View.VISIBLE else View.GONE
        sheet.detailExpiry.text = if (profile.expire > 0L) {
            context.getString(R.string.subscription_valid_until,
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(profile.expire)))
        } else ""
        sheet.detailExpiry.setTextColor(context.resolveThemedColor(
            if (profile.expire > 0L && profile.expire <= System.currentTimeMillis()) MaterialR.attr.colorError
            else MaterialR.attr.colorOnSurfaceVariant,
        ))
        sheet.detailUpdated.text = if (profile.updatedAt > 0L) {
            context.getString(R.string.subscription_updated_at,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(java.util.Date(profile.updatedAt)))
        } else context.getString(R.string.subscription_never_updated)
        sheet.detailAutoUpdate.text = if (profile.interval > 0L) {
            context.getString(R.string.subscription_auto_update_interval, profile.interval / 60_000L)
        } else context.getString(R.string.subscription_auto_update_off)
        sheet.detailAutoUpdate.visibility = if (profile.type == Profile.Type.File) View.GONE else View.VISIBLE
        sheet.detailMenu.setOnClickListener {
            if (subscriptionClickGuard.accept(profile.uuid to it.id)) profileMenuRequests.trySend(profile to it)
        }
        sheet.detailUse.visibility = if (profile.active) View.GONE else View.VISIBLE
        sheet.detailUse.isEnabled = !profile.active && !activating
        sheet.detailUse.setText(when {
            activating -> R.string.subscription_activating
            profile.active -> R.string.subscription_in_use
            !profile.imported -> R.string.subscription_finish_setup
            else -> R.string.profile_use
        })
        sheet.detailUse.setOnClickListener {
            if (!subscriptionClickGuard.accept(profile.uuid to it.id)) return@setOnClickListener
            if (profile.imported) {
                setSubscriptionActivating(profile.uuid)
                profileActivateRequests.trySend(profile)
            } else {
                subscriptionDetailsDialog?.dismiss()
                profileEditRequests.trySend(profile)
            }
        }
        val loadingServers = profile.uuid in pendingSubscriptionPickers
        sheet.detailServers.isEnabled = profile.imported && !loadingServers
        sheet.detailServers.setOnClickListener { openSubscriptionPicker(profile) }
        sheet.detailServerSummary.text = if (loadingServers) {
            context.getString(R.string.subscription_loading_servers)
        } else tabProfileAdapter.activeNodeDisplayName(profile)
            ?: context.getString(R.string.subscription_server_hint)
        sheet.detailUpdate.visibility = if (profile.imported && profile.type != Profile.Type.File) View.VISIBLE else View.GONE
        sheet.detailUpdate.isEnabled = !updating
        sheet.detailUpdate.setText(when {
            updating -> R.string.subscription_updating
            failed -> R.string.subscription_retry
            else -> R.string.update
        })
        sheet.detailUpdate.setOnClickListener { requestSubscriptionUpdate(profile) }
        sheet.detailUpdateStatus.visibility = if (failed) View.VISIBLE else View.GONE
        sheet.detailUpdateStatus.setText(R.string.subscription_update_error)
        sheet.detailUpdateStatus.setTextColor(context.resolveThemedColor(MaterialR.attr.colorError))
    }

    fun setSubscriptionActivating(uuid: UUID?) {
        activatingSubscriptionUuid = uuid
        refreshSubscriptionDetails()
    }

    private fun requestSubscriptionUpdate(profile: Profile) {
        if (!profile.imported || profile.type == Profile.Type.File) return
        if (!tabProfileAdapter.states.beginUpdate(profile.uuid)) return
        refreshSubscriptionUpdateUi(profile.uuid)
        profileForceUpdateRequests.trySend(profile)
    }

    fun markSubscriptionUpdateStarted(uuid: UUID): Boolean {
        if (!tabProfileAdapter.states.beginUpdate(uuid)) return false
        refreshSubscriptionUpdateUi(uuid)
        return true
    }

    fun finishSubscriptionUpdate(uuid: UUID, success: Boolean) {
        tabProfileAdapter.states.finishUpdate(uuid, success)
        refreshSubscriptionUpdateUi(uuid)
    }

    fun recordSubscriptionUpdateResult(uuid: UUID, success: Boolean) {
        tabProfileAdapter.states.recordObservedUpdate(uuid, success)
        refreshSubscriptionUpdateUi(uuid)
    }

    private fun refreshSubscriptionUpdateUi(uuid: UUID) {
        val index = tabProfileAdapter.profiles.indexOfFirst { it.uuid == uuid }
        if (index >= 0) tabProfileAdapter.notifyItemChanged(index)
        refreshSubscriptionDetails()
    }

    private fun requestAllSubscriptionUpdates() {
        if (tabProfileAdapter.states.allUpdating) return
        tabProfileAdapter.states.allUpdating = true
        binding.profilesTabRefresh.isRefreshing = true
        profileUpdateAllRequests.trySend(Unit)
    }

    fun finishAllSubscriptionUpdates() {
        tabProfileAdapter.states.allUpdating = false
        binding.profilesTabRefresh.isRefreshing = false
    }

    /**
     * Node-row entry to the proxy picker (redesign 1.0). Unlike a bare [openProxySheetWhenReady],
     * this first ensures the profile's proxy groups are loaded (the same load `toggleProfileExpand`
     * triggers) so the sheet isn't empty; then opens it. Does not toggle the card's expand state.
     */
    /**
     * Notification "Change node" action: open the Home node picker for the active profile.
     * @return false when no active profile is known yet (cold start), so the caller can retry.
     */
    fun openNodePickerForActiveProfile(): Boolean {
        val profile = activeProfileForQuickActions?.takeIf { it.imported } ?: return false
        selectTab(MainTab.Home)
        openNodePicker(profile)
        return true
    }

    private fun openNodePicker(profile: Profile) {
        if (!profile.imported) return
        if (!profileAdapter.hasProxyGroupsFor(profile)) {
            expandedProfileUuids.add(profile.uuid)
            profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
            profileExpandChanged.trySend(Unit)
        }
        openProxySheetWhenReady(profile)
    }

    private fun openProxySheetWhenReady(profile: Profile, attempt: Int = 0) {
        // Was 180ms x 15 ≈ 2.7s of recursive postDelayed wakeups while waiting for
        // proxy groups to load. 400ms x 6 ≈ 2.4s but with ~2.5x fewer wakeups.
        if (profileAdapter.hasProxyGroupsFor(profile) || attempt >= 6) {
            profileAdapter.showProxySheet(context, profile)
            return
        }
        binding.profileList.postDelayed({
            openProxySheetWhenReady(profile, attempt + 1)
        }, 400L)
    }

    suspend fun requestSave(profile: Profile) {
        showToast(R.string.active_unsaved_tips, ToastDuration.Long) {
            setAction(R.string.edit) {
                profileEditRequests.trySend(profile)
            }
        }
    }

    fun updateElapsed() {
        profileAdapter.updateElapsed()
    }

    /**
     * Hidden dev/testing panel (About logo ×10, debug builds only): inject a full fake operator
     * [BrandManifest] into the active profile so branding can be exercised live, without a real
     * branded subscription. Every field editable; the three flags are Unset/Yes/No dropdowns;
     * Randomize fills sample values; "Mock logo" writes the app icon as the brand logo.
     * Branding is explicit opt-in — nothing applies unless **Enabled = Yes** (X-Branding-Enabled).
     * See architecture/backend/branding.md (F-BRAND-3).
     */
    private fun showBrandDevDialog() {
        val uuid = ServiceStore(context).activeProfile
        if (uuid == null) {
            Toast.makeText(context, R.string.brand_dev_no_profile, Toast.LENGTH_LONG).show()
            return
        }
        val store = BrandStore(context)
        val cur = store.manifestFor(uuid)
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        fun field(value: String?): EditText = EditText(context).apply {
            setText(value ?: ""); setSingleLine()
        }
        val boolOpts = arrayOf(R.string.brand_dev_unset, R.string.brand_dev_yes, R.string.brand_dev_no).map(context::getString).toTypedArray()
        fun boolSpinner(v: Boolean?): Spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, boolOpts)
            setSelection(when (v) { true -> 1; false -> 2; else -> 0 })
        }
        fun Spinner.boolValue(): Boolean? = when (selectedItemPosition) { 1 -> true; 2 -> false; else -> null }

        val fName = field(cur.name); val fTagline = field(cur.tagline); val fAccent = field(cur.accentColor)
        val fLogo = field(cur.logoUrl); val fLogoLight = field(cur.logoLightUrl)
        val fWebsite = field(cur.websiteUrl); val fSupport = field(cur.supportUrl)
        val fTelegram = field(cur.telegramUrl); val fBot = field(cur.botUrl)
        val fPrivacy = field(cur.privacyUrl); val fTerms = field(cur.termsUrl)
        val fHelp = field(cur.helpUrl); val fStatus = field(cur.statusUrl)
        val fRenew = field(cur.renewUrl); val fCabinet = field(cur.cabinetUrl)
        val fUser = field(cur.userDisplayName); val fGreeting = field(cur.greeting)
        val sEnabled = boolSpinner(cur.enabled ?: true)      // default Yes so Apply themes immediately
        val sHideRouting = boolSpinner(cur.hideRouting)
        val sHideGlobal = boolSpinner(cur.hideGlobalMode)
        val sOperatorTab = boolSpinner(cur.showOperatorTab)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(4), px(20), 0)
        }
        fun row(label: Int, v: View, vararg args: Any) {
            container.addView(TextView(context).apply {
                text = context.getString(label, *args); textSize = 12f; setPadding(0, px(10), 0, px(2))
                setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant))
            })
            container.addView(v)
        }

        fun urlRow(label: Int, view: View) {
            row(R.string.brand_dev_url_format, view, context.getString(label))
        }

        val randomBtn = Button(context).apply { setText(R.string.brand_dev_randomize) }
        val mockLogoBtn = Button(context).apply { setText(R.string.brand_dev_mock_logo) }
        container.addView(randomBtn); container.addView(mockLogoBtn)

        row(R.string.brand_dev_enabled, sEnabled)
        row(R.string.name, fName); row(R.string.brand_dev_tagline, fTagline); row(R.string.brand_dev_accent, fAccent)
        row(R.string.brand_dev_logo, fLogo); row(R.string.brand_dev_logo_light, fLogoLight)
        row(R.string.brand_dev_hide_routing, sHideRouting); row(R.string.brand_dev_show_operator, sOperatorTab)
        row(R.string.brand_dev_hide_global, sHideGlobal)
        row(R.string.brand_dev_user_name, fUser); row(R.string.brand_dev_greeting, fGreeting)
        urlRow(R.string.about_brand_website, fWebsite); urlRow(R.string.about_brand_support, fSupport); urlRow(R.string.about_brand_telegram, fTelegram)
        urlRow(R.string.about_brand_bot, fBot); urlRow(R.string.about_brand_privacy, fPrivacy); urlRow(R.string.about_brand_terms, fTerms)
        urlRow(R.string.about_brand_help, fHelp); urlRow(R.string.about_brand_status, fStatus); urlRow(R.string.about_brand_renew, fRenew); urlRow(R.string.about_brand_cabinet, fCabinet)

        randomBtn.setOnClickListener {
            val palette = listOf("#7C5CFF", "#5EE6A8", "#FF5E35", "#7FB2FF", "#F2C14E", "#EC4899", "#22D3EE")
            val n = (100..999).random()
            fName.setText(context.getString(R.string.brand_dev_sample_name, n)); fTagline.setText(R.string.brand_dev_sample_tagline)
            fAccent.setText(palette.random())
            fWebsite.setText("https://example.com"); fSupport.setText("https://t.me/nemux_dev")
            fTelegram.setText("https://t.me/nemux_dev"); fBot.setText("https://t.me/some_bot")
            fPrivacy.setText("https://example.com/privacy"); fTerms.setText("https://example.com/terms")
            fHelp.setText("https://example.com/help"); fStatus.setText("https://example.com/status")
            fRenew.setText("https://example.com/renew"); fCabinet.setText("https://t.me/some_bot?startapp=demo")
            fUser.setText("dev_user_$n"); fGreeting.setText(context.getString(R.string.brand_dev_sample_greeting, 30))
            sEnabled.setSelection(1); sHideRouting.setSelection((0..2).random()); sOperatorTab.setSelection(1)
        }
        mockLogoBtn.setOnClickListener {
            runCatching {
                val bmp = context.packageManager.getApplicationIcon(context.packageName).toBitmap(px(96), px(96))
                val f = File(context.filesDir, "brand_mock_logo.png")
                FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                store.setLogoPaths(uuid, f.absolutePath, f.absolutePath)
                Toast.makeText(context, R.string.brand_dev_mock_logo_ready, Toast.LENGTH_SHORT).show()
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.brand_dev_title)
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton(R.string.yaml_preview_apply) { _, _ ->
                fun s(e: EditText) = e.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                store.setManifest(
                    uuid,
                    BrandManifest(
                        name = s(fName), tagline = s(fTagline), accentColor = s(fAccent),
                        logoUrl = s(fLogo), logoLightUrl = s(fLogoLight),
                        websiteUrl = s(fWebsite), supportUrl = s(fSupport), telegramUrl = s(fTelegram),
                        botUrl = s(fBot), privacyUrl = s(fPrivacy), termsUrl = s(fTerms),
                        helpUrl = s(fHelp), statusUrl = s(fStatus), renewUrl = s(fRenew),
                        cabinetUrl = s(fCabinet), userDisplayName = s(fUser), greeting = s(fGreeting),
                        hideRouting = sHideRouting.boolValue(), showOperatorTab = sOperatorTab.boolValue(),
                        hideGlobalMode = sHideGlobal.boolValue(),
                        enabled = sEnabled.boolValue(),
                    ),
                )
                // Kick a dashboard refresh: fetch() re-reads the manifest and the accent
                // staleness check resolves a soft recreate — same convergence path a real
                // operator brand change takes (never Activity.recreate on Main).
                profileExpandChanged.trySend(Unit)
            }
            .setNeutralButton(R.string.about_brand_reset) { _, _ ->
                store.clearFor(uuid)
                profileExpandChanged.trySend(Unit)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    suspend fun showAbout(
        versionName: String,
        coreVersion: String,
        initialUpdateStatus: String? = null,
        onCheckUpdates: (((Boolean) -> Unit, (String?) -> Unit) -> Unit)? = null,
    ) {
        withContext(Dispatchers.Main) {
            if (clickGuard.isOpen(Request.OpenAbout)) return@withContext
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
                this.coreVersion = coreVersion
            }
            val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
            dialog.setContentView(binding.root)
            applyBrandToAbout(binding)

            // Hidden dev brand tester: tap the app icon ×10 (debug builds only).
            val debuggable = (context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                var iconTaps = 0
                binding.aboutAppIcon.setOnClickListener {
                    if (++iconTaps >= 10) {
                        iconTaps = 0
                        showBrandDevDialog()
                    }
                }
            }

            binding.aboutGithubIcon.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    runCatching {
                        val url = context.getString(R.string.clashfest_repo_url)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            binding.aboutTelegramIcon.apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    runCatching {
                        val url = context.getString(R.string.clashfest_telegram_url)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }

            val support = uiStore.supportUrl.takeIf { it.isNotBlank() }
            if (support != null) {
                binding.aboutSupportButton.apply {
                    visibility = View.VISIBLE
                    text = context.getString(R.string.about_support)
                    icon = null
                    setOnClickListener {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(support))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }

            if (onCheckUpdates != null) {
                // Status line shows the current/known update state up front (so "About & updates"
                // reflects a cached update without re-checking); the button is the action.
                val statusLine = binding.aboutUpdateStatus
                fun showStatus(text: String?) {
                    statusLine.text = text.orEmpty()
                    statusLine.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                showStatus(initialUpdateStatus)

                binding.aboutCheckUpdatesButton.apply {
                    visibility = View.VISIBLE

                    fun setLoading(loading: Boolean) {
                        isEnabled = !loading
                        alpha = if (loading) 0.65f else 1f
                        text = context.getString(
                            if (loading) R.string.about_checking_updates else R.string.about_check_updates,
                        )
                    }

                    setOnClickListener {
                        if (!isEnabled) return@setOnClickListener
                        showStatus(null)
                        setLoading(true)
                        onCheckUpdates(::setLoading) { text -> showStatus(text) }
                    }
                }
            }

            if (!clickGuard.tryOpen(Request.OpenAbout)) return@withContext
            dialog.setOnDismissListener {
                clickGuard.close(Request.OpenAbout)
                applyPowerVisuals()
            }
            try {
                updateBreathAnimator(false, false)
                dialog.show()
            } catch (error: Throwable) {
                clickGuard.close(Request.OpenAbout)
                applyPowerVisuals()
                throw error
            }
        }
    }

    private fun applyBrandToAbout(binding: DesignAboutBinding) {
        // Layout defaults already render the unbranded About correctly:
        //   - about_brand_name = "powered by ClashFest"
        //   - about_default_subtitle = "ClashFest"
        //   - about_brand_tagline / about_brand_powered_by / brand chips /
        //     renew button / reset button all start hidden.
        // When no brand is active we leave everything as-is.
        if (!brandHolder.isActive) return

        val brand = brandHolder.manifest
        val brandName = brand.name?.takeIf { it.isNotBlank() }
        if (brandName != null) {
            binding.aboutBrandName.text = brandName
            binding.aboutDefaultSubtitle.visibility = View.GONE
            binding.aboutBrandPoweredBy.visibility = View.VISIBLE
        }
        val tagline = brand.tagline?.takeIf { it.isNotBlank() }
        if (tagline != null) {
            binding.aboutBrandTagline.text = tagline
            binding.aboutBrandTagline.visibility = View.VISIBLE
        }
        brandHolder.logoPath?.let {
            com.github.kr328.clash.design.branding.BrandLogoBinder.bind(
                binding.aboutAppIcon, it,
            )
        }

        // Per-user display name. Operators typically wire this via a panel
        // template variable (e.g. `X-Brand-User-Display-Name: {{USERNAME}}`)
        // so the panel substitutes the actual name before sending.
        val displayName = brand.userDisplayName?.takeIf { it.isNotBlank() }
        if (displayName != null) {
            binding.aboutBrandUserDisplayName.text = context.getString(
                R.string.about_brand_logged_in_as,
                displayName,
            )
            binding.aboutBrandUserDisplayName.visibility = View.VISIBLE
        }

        // Operator links chip group.
        val linksGroup = binding.aboutBrandLinks
        linksGroup.removeAllViews()
        val cabinetLink = brand.cabinetUrl?.takeIf { it.isNotBlank() }
            ?: uiStore.profileWebPageUrl.takeIf { it.isNotBlank() }
        val links = listOfNotNull(
            cabinetLink?.let { it to R.string.about_brand_cabinet },
            brand.websiteUrl?.let { it to R.string.about_brand_website },
            brand.supportUrl?.let { it to R.string.about_brand_support },
            brand.telegramUrl?.let { it to R.string.about_brand_telegram },
            brand.botUrl?.let { it to R.string.about_brand_bot },
            brand.helpUrl?.let { it to R.string.about_brand_help },
            brand.privacyUrl?.let { it to R.string.about_brand_privacy },
            brand.termsUrl?.let { it to R.string.about_brand_terms },
            brand.statusUrl?.let { it to R.string.about_brand_status },
        )
        if (links.isNotEmpty()) {
            binding.aboutBrandDivider.visibility = View.VISIBLE
            linksGroup.visibility = View.VISIBLE
            links.forEach { (url, label) ->
                val chip = com.google.android.material.chip.Chip(context).apply {
                    text = context.getString(label)
                    isClickable = true
                    isCheckable = false
                    setEnsureMinTouchTargetSize(false)
                    minHeight = (28 * resources.displayMetrics.density).toInt()
                    chipMinHeight = 28 * resources.displayMetrics.density
                    chipStartPadding = 10 * resources.displayMetrics.density
                    chipEndPadding = 10 * resources.displayMetrics.density
                    textStartPadding = 0f
                    textEndPadding = 0f
                    setTextAppearance(R.style.TextAppearance_App_LabelSmall)
                    setOnClickListener {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                linksGroup.addView(chip)
            }
        } else {
            binding.aboutBrandDivider.visibility = View.GONE
            linksGroup.visibility = View.GONE
        }

        // Renew button — separate from chip group because it's a primary CTA.
        val renew = brand.renewUrl
        if (!renew.isNullOrBlank()) {
            binding.aboutBrandRenewButton.visibility = View.VISIBLE
            binding.aboutBrandRenewButton.setOnClickListener {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(renew))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        } else {
            binding.aboutBrandRenewButton.visibility = View.GONE
        }

    }

    private fun showModeSheet() {
        val sheet = BottomSheetMainModeBinding.inflate(context.layoutInflater)
        val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
        val isGlobal = currentModeSegment == TunnelState.Mode.Global

        sheet.modeRuleSelected.visibility = if (isGlobal) View.INVISIBLE else View.VISIBLE
        sheet.modeGlobalSelected.visibility = if (isGlobal) View.VISIBLE else View.INVISIBLE
        sheet.modeRuleRow.setOnClickListener {
            requests.trySend(Request.PatchModeRule)
            dialog.dismiss()
        }
        sheet.modeGlobalRow.setOnClickListener {
            requests.trySend(Request.PatchModeGlobal)
            dialog.dismiss()
        }

        dialog.setContentView(sheet.root)
        dialog.show()
    }

    /** Held so [rebuildMainPagerForTabs] can detach/reattach without piling up. */
    private var pagerPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var pagerInitialised: Boolean = false

    /** One-time setup. Wires the OnPageChangeCallback exactly once. */
    private fun setupMainPager() {
        if (pagerInitialised) return
        pagerInitialised = true

        attachPagerAdapter()

        val cb = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager2.SCROLL_STATE_IDLE) return
                val count = activeTabs.size
                when (binding.mainPager.currentItem) {
                    0 -> binding.mainPager.setCurrentItem(count, false)
                    count + 1 -> binding.mainPager.setCurrentItem(1, false)
                }
            }

            override fun onPageSelected(position: Int) {
                mainTabForPagerPosition(position)?.let(::renderMainTab)
            }
        }
        pagerPageChangeCallback = cb
        binding.mainPager.registerOnPageChangeCallback(cb)

        // Sync bottom-nav visibility with the active tab set.
        MainTab.values().forEach { tab ->
            navForMainTab(tab).visibility = if (tab in activeTabs) View.VISIBLE else View.GONE
        }
        wireBottomNavFocus()
        binding.mainPager.setCurrentItem(1, false)
        renderMainTab(activeTabs.firstOrNull() ?: MainTab.Home)
        binding.mainPager.post {
            tweakViewPagerHorizontalSwipeTolerance(binding.mainPager)
            requestInitialHomeFocus()
        }
    }

    /**
     * Give the home its initial D-pad focus on launch, so on TV the user immediately sees where
     * they are (focusedByDefault is unreliable because the home page is re-parented into the
     * ViewPager holder at runtime). Targets the primary action of the visible state.
     */
    private fun requestInitialHomeFocus() {
        val target = if (binding.mainHomeActiveContent.visibility == View.VISIBLE) {
            binding.mainPowerCard
        } else {
            binding.mainEmptyAddButton
        }
        target.requestFocus()
    }

    /**
     * Rebuild only the adapter + nav visibility when [activeTabs] actually changes.
     * Preserves: registered OnPageChangeCallback, current logical tab when possible,
     * the touch-slop tweak (we don't re-run it).
     */
    private fun rebuildMainPagerForTabs() {
        // Snapshot the tab the user is currently on so we can stay there
        // (or fall back to Home if it's no longer in the set).
        val currentLogical = mainTabForPagerPosition(binding.mainPager.currentItem)
        attachPagerAdapter()

        MainTab.values().forEach { tab ->
            navForMainTab(tab).visibility = if (tab in activeTabs) View.VISIBLE else View.GONE
        }
        wireBottomNavFocus()
        val targetTab = currentLogical?.takeIf { it in activeTabs }
            ?: activeTabs.firstOrNull() ?: MainTab.Home
        val targetItem = activeTabs.indexOf(targetTab).coerceAtLeast(0) + 1
        binding.mainPager.setCurrentItem(targetItem, false)
        renderMainTab(targetTab)
    }

    private fun attachPagerAdapter() {
        val pages = activeTabs.map { pageForMainTab(it) }
        // Detach every page (including ones we won't show) so the new adapter
        // can attach the ones we want without parent-collision exceptions.
        MainTab.values().forEach { tab ->
            val page = pageForMainTab(tab)
            (page.parent as? ViewGroup)?.removeView(page)
        }
        pages.forEach { it.visibility = View.VISIBLE }
        binding.mainPager.adapter = StaticPageAdapter(pages)
        binding.mainPager.offscreenPageLimit = pages.lastIndex.coerceAtLeast(1)
    }

    /**
     * Compute the desired tab set from the active brand and rebuild the pager
     * if it changed. No-op when the layout is the same.
     *
     * The Operator tab is **explicit opt-in** via X-Brand-Show-Operator-Tab —
     * just having a brand name / logo / accent applies the visual brand but
     * does NOT add a tab. Hide-Routing only takes effect when the operator
     * also opted into the tab, otherwise it'd hide Routing without anything
     * replacing it.
     */
    /**
     * Bottom-nav set for the active brand. `X-Brand-Hide-Routing` removes the Routing tab on
     * its own (three tabs); `X-Brand-Show-Operator-Tab` adds Operator, and with both set
     * Operator takes Routing's slot. The original brand commit (3e8fb36a) honoured Hide-Routing
     * alone; the same-day palette refactor accidentally gated it behind the Operator tab.
     */
    private fun reconcileTabsForBrand() {
        val brand = brandHolder.manifest
        val showTab = brand.showOperatorTab == true
        val hideRouting = brand.hideRouting == true
        val desired = when {
            !brandHolder.isActive -> DEFAULT_TABS
            showTab && hideRouting -> listOf(
                MainTab.Home, MainTab.Profiles, MainTab.Operator, MainTab.Settings,
            )
            showTab -> listOf(
                MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Operator, MainTab.Settings,
            )
            hideRouting -> listOf(MainTab.Home, MainTab.Profiles, MainTab.Settings)
            else -> DEFAULT_TABS
        }
        if (desired == activeTabs) return
        activeTabs = desired
        if (pagerInitialised) rebuildMainPagerForTabs()
    }

    /**
     * Operator POLICY (works WITHOUT X-Branding-Enabled): when X-Brand-Hide-Global-Mode is set, hide
     * the Home Rule/Global mode toggle entirely and pin the app to Rule. Read straight off the
     * manifest — deliberately NOT gated by [brandHolder]'s isActive — so an operator can restrict
     * Global mode without opting into the full visual brand. Every other X-Brand-* field still
     * requires branding to be explicitly enabled.
     */
    private fun reconcileGlobalModeForBrand() {
        val hideGlobal = brandHolder.manifest.hideGlobalMode == true
        // Hide only the Global segment (not the whole Mode row) so "Mode: Rule" still reads clearly
        // and the card outline stays intact. The toggle group re-rounds the lone Rule button.
        binding.mainModeGlobal.visibility = if (hideGlobal) View.GONE else View.VISIBLE
        if (hideGlobal && currentModeSegment == TunnelState.Mode.Global) {
            // Operator forbids Global — flip back to Rule, mirroring a user tap on the Rule segment.
            requests.trySend(Request.PatchModeRule)
        }
    }

    /**
     * Nested home content (announcement + profiles) grows vertically; on narrow or very wide
     * aspect ratios, small diagonal motion while scrolling can be absorbed as a horizontal
     * page swipe. Raising RecyclerView touch slop reduces accidental tab changes.
     */
    private var touchSlopTweakApplied: Boolean = false
    private fun tweakViewPagerHorizontalSwipeTolerance(viewPager: ViewPager2) {
        if (touchSlopTweakApplied) return
        runCatching {
            val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return
            val field = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            field.isAccessible = true
            val slop = field.getInt(recyclerView)
            field.setInt(recyclerView, slop * 3)
            touchSlopTweakApplied = true
        }.onFailure {
            Log.w("ViewPager2 touch slop tweak skipped: ${it.message}")
        }
    }

    private fun mainTabForPagerPosition(position: Int): MainTab? {
        val size = activeTabs.size
        val logical = when (position) {
            0 -> size - 1
            size + 1 -> 0
            else -> position - 1
        }
        return activeTabs.getOrNull(logical)
    }

    /**
     * Toggle the in-header update indicator. MainActivity polls
     * [AppUpdateChecker.isUpdateAvailable] from onResume and from background
     * opportunistic checks, then calls this to reflect the result.
     */
    fun setUpdateBadgeVisible(visible: Boolean) {
        binding.mainHeaderUpdateBadge.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Switch to the Profiles tab (the in-app profile manager) instead of opening a separate screen. */
    fun openProfilesTab() = selectMainTab(MainTab.Profiles)

    /** The logical tab currently shown — captured by MainActivity before a soft recreate (D5). */
    val currentTab: MainTab?
        get() = mainTabForPagerPosition(binding.mainPager.currentItem)

    /**
     * Non-animated tab restore after a soft recreate. Reuses the pager-position math of
     * [selectMainTab] but skips the smooth scroll + scroll-to-top behavior — this runs on a
     * freshly inflated design before it is even attached. No-op if [tab] isn't in the current
     * tab set (e.g. the Operator tab vanished with the brand that owned it).
     */
    fun selectTab(tab: MainTab) {
        val logicalIndex = activeTabs.indexOf(tab)
        if (logicalIndex < 0) return
        binding.mainPager.setCurrentItem(logicalIndex + 1, false)
        renderMainTab(tab)
    }

    private fun selectMainTab(tab: MainTab) {
        val logicalIndex = activeTabs.indexOf(tab)
        if (logicalIndex < 0) return
        val targetItem = logicalIndex + 1
        if (binding.mainPager.currentItem == targetItem) {
            val page = pageForMainTab(tab)
            if (page is androidx.core.widget.NestedScrollView) {
                page.smoothScrollTo(0, 0)
            } else if (tab == MainTab.Profiles) {
                binding.profilesTabList.smoothScrollToPosition(0)
            }
            return
        }
        binding.mainPager.setCurrentItem(targetItem, true)
    }

    private fun renderMainTab(tab: MainTab) {
        if (tab == MainTab.Settings) {
            binding.mainSettingsLanguageValue.setText(when (uiStore.appLanguage) {
                AppLanguage.System -> R.string.app_language_system
                AppLanguage.English -> R.string.app_language_en
                AppLanguage.Russian -> R.string.app_language_ru
                AppLanguage.Chinese -> R.string.app_language_zh
            })
        }
        if (tab == MainTab.Routing) requests.trySend(Request.RefreshRouting)
        MainTab.values().forEach {
            navForMainTab(it).isSelected = it == tab
            // The ViewPager keeps off-screen tab pages attached + VISIBLE (offscreenPageLimit =
            // all), so without this their content stays focusable and the D-pad escapes into the
            // hidden tab ("ghost" focus on TV). Only the current tab's page accepts focus.
            (pageForMainTab(it) as? ViewGroup)?.descendantFocusability =
                if (it == tab) ViewGroup.FOCUS_AFTER_DESCENDANTS
                else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    fun patchRoutingSummary(profile: String?, state: com.github.kr328.clash.service.model.RuleState?, error: Boolean = false) {
        val loading = profile != null && state == null && !error
        binding.mainRoutingLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.mainRoutingCounts.visibility = if (loading) View.GONE else View.VISIBLE
        binding.mainRoutingCounts.isClickable = error
        binding.mainRoutingCounts.isFocusable = error
        binding.mainRoutingCounts.setOnClickListener { requests.trySend(Request.RefreshRouting) }
        binding.mainRoutingProfile.text = profile ?: context.getString(R.string.routing_no_subscription)
        binding.mainRoutingAdd.isEnabled = profile != null && state != null
        binding.mainRoutingCounts.text = when {
            error -> context.getString(R.string.routing_load_error)
            profile == null -> context.getString(R.string.routing_no_subscription_hint)
            state == null -> context.getString(R.string.routing_open_to_load)
            else -> context.getString(R.string.routing_counts_fmt,
                state.rules.count { it.enabled && !it.deleted && it.source == com.github.kr328.clash.service.model.RuleSource.MANUAL },
                state.rules.count { it.enabled && !it.deleted && it.source == com.github.kr328.clash.service.model.RuleSource.PROVIDER },
                state.providers.count { it.enabled })
        }
    }

    private fun pageForMainTab(tab: MainTab) = when (tab) {
        MainTab.Home -> binding.mainHomePage
        MainTab.Profiles -> binding.mainProfilesPage
        MainTab.Routing -> binding.mainRoutingPage
        MainTab.Operator -> binding.mainOperatorPage
        MainTab.Settings -> binding.mainSettingsPage
    }

    private fun navForMainTab(tab: MainTab): View = when (tab) {
        MainTab.Home -> binding.mainNavHome
        MainTab.Profiles -> binding.mainNavProfiles
        MainTab.Routing -> binding.mainNavRouting
        MainTab.Operator -> binding.mainNavOperator
        MainTab.Settings -> binding.mainNavSettings
    }

    /**
     * Trap D-pad focus inside the bottom nav: each visible item points LEFT/RIGHT only to its
     * neighbours (the ends point to themselves), so pressing past the first/last item stays put
     * instead of escaping into the current tab's content ("focus into the void" on TV). DOWN also
     * stays — there's nothing below the nav. UP is left to the default search so it reaches content.
     */
    private fun wireBottomNavFocus() {
        val visible = activeTabs.map { navForMainTab(it) }
        visible.forEachIndexed { i, view ->
            view.nextFocusLeftId = visible[(i - 1).coerceAtLeast(0)].id
            view.nextFocusRightId = visible[(i + 1).coerceAtMost(visible.lastIndex)].id
            view.nextFocusDownId = view.id
        }
    }

    init {
        binding.self = this
        binding.tunnelStarting = false
        binding.hasProfiles = false
        applyHomeBackgroundStyle()
        setupMainPager()

        // Surface the remote-control quick entry on the TV home (deep settings nav with a D-pad
        // is painful); on phones it lives in the "+" sheet instead. Shown in both the empty and
        // active home states so a fresh TV can enable it to receive its first subscription.
        if (context.isTelevision()) {
            binding.mainCompanionRow.visibility = View.VISIBLE
            binding.mainCompanionEmptyButton.visibility = View.VISIBLE
        }

        binding.profileList.also {
            it.applyLinearAdapter(context, profileAdapter)
            (it.layoutManager as? LinearLayoutManager)?.stackFromEnd = false
            it.setHasFixedSize(true)
            // Change-animations were running on every traffic tick across all visible cards
            // (~2–8s cadence) and added measurable GPU/CPU load. Add/remove animations are kept,
            // but per-item rebind animations are disabled.
            it.itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                changeDuration = 0
                moveDuration = 0
            }
        }

        binding.profilesTabList.also {
            it.applyLinearAdapter(context, tabProfileAdapter)
            it.itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                changeDuration = 0
                moveDuration = 0
            }
        }
        tabItemTouchHelper.attachToRecyclerView(binding.profilesTabList)
        binding.profilesTabSort.setOnClickListener { showProfilesTabSort(it) }
        binding.profilesTabRefresh.setOnChildScrollUpCallback { _, _ -> binding.profilesTabList.canScrollVertically(-1) }
        binding.profilesTabRefresh.setColorSchemeColors(context.resolveThemedColor(MaterialR.attr.colorPrimary))
        binding.profilesTabRefresh.setOnRefreshListener { requestAllSubscriptionUpdates() }

        val card = binding.mainPowerCard
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                powerIconAnimator?.end()
                powerIconAnimator = null
            }
        })
        card.setOnClickListener {
            request(Request.ToggleStatus)
        }
        card.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.animate().cancel()
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    // Spring back with a soft overshoot — the connect tap feels physical.
                    v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(OvershootInterpolator(2.4f))
                        .setDuration(340L).start()
                }
            }
            false
        }

        binding.tunnelStarting = false
        applyPowerVisuals()

        binding.mainNavHome.setOnClickListener { selectMainTab(MainTab.Home) }
        binding.mainNavProfiles.setOnClickListener { selectMainTab(MainTab.Profiles) }
        binding.mainNavRouting.setOnClickListener { selectMainTab(MainTab.Routing) }
        binding.mainNavOperator.setOnClickListener { selectMainTab(MainTab.Operator) }
        binding.mainNavSettings.setOnClickListener { selectMainTab(MainTab.Settings) }
        binding.mainHeaderUpdateBadge.setOnClickListener { onUpdateBadgeTap?.invoke() }
        // Redesign 1.0: Mode is an in-place segmented control (Rule/Global) instead of a row+sheet.
        binding.mainModeSegment.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressModeSegment) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.main_mode_rule -> requests.trySend(Request.PatchModeRule)
                R.id.main_mode_global -> requests.trySend(Request.PatchModeGlobal)
            }
        }
        binding.mainActiveProfileUpdate.setOnClickListener {
            activeProfileForQuickActions?.let { profile ->
                requestSubscriptionUpdate(profile)
            }
        }
        binding.mainActiveProfileSupport.setOnClickListener {
            val target = resolveSupportUrl() ?: return@setOnClickListener
            activeAnnouncementOnOpenUrl?.invoke(target) ?: activeAnnouncementOnSupport?.invoke()
        }
    }

    private fun applyHomeBackgroundStyle() {
        when (uiStore.homeBackgroundStyle) {
            HomeBackgroundStyle.MaterialYou -> binding.root.setBackgroundColor(
                context.resolveThemedColor(com.google.android.material.R.attr.colorSurface),
            )
            HomeBackgroundStyle.Preview -> binding.root.setBackgroundResource(
                context.resolveThemedResourceId(R.attr.mainDashboardBackground),
            )
            HomeBackgroundStyle.Sloth -> binding.root.setBackgroundResource(
                context.resolveThemedResourceId(R.attr.mainDashboardBackground),
            )
        }
    }

    fun request(request: Request) {
        when (request) {
            Request.PatchModeDirect, Request.PatchModeGlobal, Request.PatchModeRule,
            Request.CycleTheme -> Unit
            else -> if (!clickGuard.accept(request)) return
        }
        if (request == Request.OpenAbout && onOpenAbout != null) {
            onOpenAbout?.invoke()
            return
        }
        requests.trySend(request)
    }
}
