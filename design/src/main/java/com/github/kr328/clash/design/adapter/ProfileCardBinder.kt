package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.elapsedIntervalString
import com.github.kr328.clash.design.util.ParsedFlag
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.design.databinding.AdapterProfileBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.color.MaterialColors
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Owns the profile card's binding helpers for [ProfileAdapter]: active-state chrome, flag / emoji
 * icon, traffic and expiry rows, the selection summary line and the inline group accordion of
 * the Profiles tab.
 */
internal class ProfileCardBinder(private val adapter: ProfileAdapter) {
    private val profileEmojiPool = listOf(
        "🐱", "🐶", "🦊", "🐼", "🐻", "🐨", "🐯", "🦁",
        "🐸", "🐵", "🐙", "🦄", "🐧", "🐺", "🐹", "🐰",
    )

    internal fun formatSelectionSummaryForHome(groupName: String): String = displayGroupName(groupName)

    internal fun formatSelectionSummaryForProfiles(
        context: Context,
        profile: Profile,
        groupName: String,
    ): String {
        val groupDisplay = displayGroupName(groupName)
        val selectedProxy = adapter.selection.resolvedSelectedProxyName(profile, groupName)
            ?.let(::displayGroupName)
        return if (selectedProxy.isNullOrBlank()) {
            context.getString(R.string.main_selected_group_fmt, groupDisplay)
        } else {
            context.getString(R.string.main_selected_route_fmt, groupDisplay, selectedProxy)
        }
    }

    internal fun applyActiveVisuals(holder: ProfileAdapter.Holder, profile: Profile) {
        val chip = holder.binding.activeStatusChip
        val context = chip.context
        holder.binding.profileCard.strokeWidth = context.dp(1)
        if (profile.active) {
            chip.visibility = View.VISIBLE
            chip.text = context.getString(R.string.profile_active_status)
            chip.setBackgroundResource(R.drawable.bg_m3_status_chip)
            chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimaryContainer))
        } else {
            chip.visibility = View.GONE
            chip.text = context.getString(R.string.profile_inactive_status)
            chip.setBackgroundResource(R.drawable.bg_m3_status_chip_neutral)
            chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
    }

    internal fun bindExpiryChip(holder: ProfileAdapter.Holder, profile: Profile, context: Context) {
        val view = holder.binding.expiryChip
        if (profile.expire <= 0L || !profile.imported || profile.pending) {
            view.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        val diff = profile.expire - now
        val expired = diff <= 0L
        val totalHours = if (expired) 0L else diff / 3_600_000L
        val days = totalHours / 24L
        val hours = totalHours % 24L
        val critical = expired || days < 3L
        if (!critical) {
            view.visibility = View.GONE
            return
        }
        view.text = when {
            expired -> context.getString(R.string.proxy_sheet_expiry_expired)
            days > 0 -> context.getString(R.string.proxy_sheet_expiry_days_hours, days.toInt(), hours.toInt())
            else -> context.getString(R.string.proxy_sheet_expiry_hours, hours.toInt().coerceAtLeast(1))
        }
        view.visibility = View.VISIBLE
        view.setBackgroundResource(R.drawable.bg_m3_expiry_chip_warning)
        view.setTextColor(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnErrorContainer),
        )
        // Operator may supply X-Brand-Renew-URL; when set, critical-expiry chip
        // becomes the user's primary "renew this" action. When absent we leave
        // the chip with its layout defaults (non-clickable TextView) untouched.
        val renew = adapter.brandManifest.renewUrl?.takeIf { it.isNotBlank() }
        if (renew != null) {
            view.isClickable = true
            view.isFocusable = true
            view.setOnClickListener { adapter.onOpenBrandUrl?.invoke(renew) }
        }
    }

    /** "Updated 2 hours ago" for subscription (URL) profiles; hidden for files and unsaved drafts. */
    internal fun bindUpdatedSummary(holder: ProfileAdapter.Holder, profile: Profile, context: Context) {
        val view = holder.binding.updatedSummary
        val show = profile.type == Profile.Type.Url && profile.imported && !profile.pending &&
            profile.updatedAt > 0L
        if (!show) {
            view.visibility = View.GONE
            return
        }
        val elapsed = (System.currentTimeMillis() - profile.updatedAt).coerceAtLeast(0L)
        view.text = if (elapsed < TimeUnit.MINUTES.toMillis(1)) {
            context.getString(R.string.profile_updated_just_now)
        } else {
            context.getString(R.string.profile_updated_fmt, elapsed.elapsedIntervalString(context))
        }
        view.visibility = View.VISIBLE
    }

    internal fun bindUsageAndProgress(holder: ProfileAdapter.Holder, profile: Profile) {
        val binding = holder.binding
        val used = profile.upload + profile.download
        val showTraffic =
            profile.imported &&
                !profile.pending &&
                (used > 0L || profile.total >= 2L)
        if (showTraffic) {
            binding.usageSummary.visibility = View.VISIBLE
            binding.usageSummary.text = formatUsageLine(profile)
        } else {
            binding.usageSummary.visibility = View.GONE
        }
        if (profile.imported && !profile.pending && profile.total >= 2L) {
            binding.usageProgress.visibility = View.VISIBLE
            binding.usageProgress.max = 1000
            val total = profile.total.coerceAtLeast(1L)
            val frac = (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            binding.usageProgress.progress = (frac * 1000).toInt()
        } else {
            binding.usageProgress.visibility = View.GONE
        }
    }

    /** Per-profile set of group names currently expanded in the inline accordion (multi-open). */
    private val inlineExpandedGroups = HashMap<UUID, MutableSet<String>>()

    /**
     * Vertical accordion of proxy-group blocks for the Profiles-tab inline panel.
     * Each block header toggles its own node list; several can stay open at once.
     */
    internal fun renderGroupAccordion(
        container: ViewGroup,
        profile: Profile,
        groupNames: List<String>,
    ) {
        val context = container.context
        val inflater = context.layoutInflater

        if (groupNames.isEmpty()) {
            container.tag = null
            container.removeAllViews()
            addEmptyProxyHint(container, context)
            return
        }

        val expandedGroups = inlineExpandedGroups.getOrPut(profile.uuid) {
            // First open: reveal the preferred (active/selected) group so nodes show immediately.
            val preferred = adapter.selection.resolvePreferredGroupFromList(profile, groupNames)
            linkedSetOf(preferred.takeIf { it.isNotBlank() } ?: groupNames.first())
        }
        expandedGroups.retainAll(groupNames.toSet())

        // Rebuild the block list only when the group set changes; otherwise update in place so the
        // per-second refresh tick doesn't tear down every block (matches renderGroupSegmentsInto).
        val structureTag = "acc:" + groupNames.joinToString("|")
        if (container.tag != structureTag || container.childCount != groupNames.size) {
            container.removeAllViews()
            repeat(groupNames.size) {
                container.addView(inflater.inflate(R.layout.item_proxy_group_block, container, false))
            }
            container.tag = structureTag
        }

        groupNames.forEachIndexed { index, _ ->
            val block = container.getChildAt(index) ?: return@forEachIndexed
            bindGroupBlock(block, profile, groupNames, index, expandedGroups)
        }

        // Keep the engine feeding at least the primary group's live detail.
        adapter.reportVisibleGroup(profile, adapter.selection.resolvePreferredGroupFromList(profile, groupNames))
    }

    private fun bindGroupBlock(
        block: View,
        profile: Profile,
        groupNames: List<String>,
        index: Int,
        expandedGroups: MutableSet<String>,
    ) {
        val groupName = groupNames[index]
        val header = block.findViewById<View>(R.id.group_block_header)
        val nameView = block.findViewById<TextView>(R.id.group_block_name)
        val summaryView = block.findViewById<TextView>(R.id.group_block_summary)
        val countView = block.findViewById<TextView>(R.id.group_block_count)
        val chevron = block.findViewById<View>(R.id.group_block_chevron)
        val pingView = block.findViewById<View>(R.id.group_block_ping)
        val pingProgress = block.findViewById<View>(R.id.group_block_ping_progress)
        val nodesList = block.findViewById<ViewGroup>(R.id.group_block_nodes)

        val pg = adapter.selection.proxyGroupForRow(profile, groupName)
        nameView.text = displayGroupName(groupName)

        val pendingChoice = adapter.selection.pendingMapValueForGroup(profile.uuid, groupName)?.takeIf { it.isNotBlank() }
        val selectedName = pendingChoice ?: pg?.now
        val selectedDisplay = selectedName?.takeIf { it.isNotBlank() }?.let { name ->
            pg?.proxies?.firstOrNull { it.name == name }?.let { it.title.ifBlank { it.name } } ?: name
        }
        summaryView.text = selectedDisplay.orEmpty()
        summaryView.visibility = if (selectedDisplay.isNullOrBlank()) View.GONE else View.VISIBLE

        val count = pg?.proxies?.count { !shouldHideProxyOption(groupName, it) } ?: 0
        countView.text = count.toString()

        val isExpanded = groupName in expandedGroups
        chevron.rotation = if (isExpanded) 0f else -90f
        nodesList.visibility = if (isExpanded) View.VISIBLE else View.GONE

        val pinging = adapter.states.pingingUuid == profile.uuid && adapter.pingingGroupByUuid[profile.uuid] == groupName
        pingProgress.visibility = if (pinging) View.VISIBLE else View.GONE
        pingView.visibility = if (pinging) View.INVISIBLE else View.VISIBLE
        pingView.setOnClickListener {
            adapter.pingingGroupByUuid[profile.uuid] = groupName
            val names = adapter.selection.proxyGroupForRow(profile, groupName)?.proxies?.map { it.name }.orEmpty()
            adapter.onPingAll(profile, groupName, names, "")
        }

        header.setOnClickListener {
            if (!expandedGroups.remove(groupName)) {
                expandedGroups.add(groupName)
                if (adapter.selection.useEngineFor(profile)) adapter.reportVisibleGroup(profile, groupName, force = true)
            }
            bindGroupBlock(block, profile, groupNames, index, expandedGroups)
        }

        if (isExpanded) {
            fillProxyRowsInto(nodesList, profile, groupNames, index)
        } else {
            nodesList.removeAllViews()
        }
    }

    private fun fillProxyRowsInto(
        list: ViewGroup,
        profile: Profile,
        groupNames: List<String>,
        groupIndex: Int,
    ) {
        // NB: do NOT clear the list up front — the 3-arg overload below is structure-guarded and
        // must see the previously-inflated rows to update delays in place instead of re-inflating
        // on every live-ping tick (O-07). Clearing happens only on the empty/early-out paths.
        val context = list.context
        val groupName = groupNames.getOrNull(groupIndex) ?: run { list.removeAllViews(); list.tag = null; return }
        val pg = adapter.selection.proxyGroupForRow(profile, groupName) ?: run { list.removeAllViews(); list.tag = null; return }
        val pendingChoice = adapter.selection.pendingMapValueForGroup(profile.uuid, groupName)?.takeIf { it.isNotBlank() }
        val effectiveNow = pendingChoice ?: pg.now
        val rows = pg.proxies
            .mapIndexedNotNull { index, proxy ->
                if (shouldHideProxyOption(groupName, proxy)) return@mapIndexedNotNull null
                ProxyPickerRow(
                    groupName = groupName,
                    groupIndex = groupIndex,
                    proxy = proxy,
                    configIndex = index,
                    delayMs = adapter.selection.resolveProxyDelay(profile.uuid, proxy),
                    selected = proxy.name.isNotEmpty() && proxy.name == effectiveNow,
                    provider = providerNameForProxy(proxy.name),
                )
            }

        if (rows.isEmpty()) {
            val hintRes = when {
                adapter.selection.useEngineFor(profile) && !adapter.selection.hasLiveProxyDetail(profile, groupName) ->
                    R.string.proxy_nodes_loading
                adapter.selection.useEngineFor(profile) ->
                    R.string.proxy_group_empty_runtime
                else ->
                    R.string.proxy_nodes_empty_connect_vpn
            }
            list.removeAllViews()
            list.tag = null
            addEmptyProxyHint(list, context, hintRes)
            return
        }

        fillProxyRowsInto(list, profile, rows)
    }

    /**
     * Inflates [rows] straight into a plain [list] container — the inline group-block accordion in
     * the profile card. The bottom-sheet picker uses [ProxyNodeAdapter] (RecyclerView) instead; this
     * path stays a simple fill because it lives inside the card's own scroll, where a nested
     * RecyclerView could not recycle anyway. Reuses [bindProxyNodeRow] so both paths render rows
     * identically.
     */
    private fun fillProxyRowsInto(
        list: ViewGroup,
        profile: Profile,
        rows: List<ProxyPickerRow>,
    ) {
        val context = list.context
        // Structure tag = the node identity list. During a live URL-test the engine pushes fresh
        // delays ~8×/s and the card re-binds each push; without this guard we re-inflated every node
        // on every tick (200 inflations/tick → ContentCapture assumeLayout flood → the device melts
        // down during a ping, O-07). Same node set → patch the delay capsules on the existing views
        // in place and return; only a changed node set falls through to a full re-inflate.
        val structureTag = "nodes:" + rows.joinToString("|") { it.groupName + "/" + it.proxy.name }
        if (rows.isNotEmpty() && list.tag == structureTag && list.childCount == rows.size) {
            rows.forEachIndexed { i, pickerRow ->
                list.getChildAt(i)?.let { adapter.nodeRows.updateProxyRowDynamic(it, pickerRow) }
            }
            return
        }

        list.removeAllViews()
        list.tag = null
        if (rows.isEmpty()) {
            addEmptyProxyHint(list, context, R.string.profile_proxy_empty_filtered)
            return
        }
        val showGroupInSubtitle = rows.map { it.groupName }.distinct().size > 1
        val inflater = context.layoutInflater
        for (pickerRow in rows) {
            val row = inflater.inflate(R.layout.adapter_home_proxy_node, list, false)
            adapter.nodeRows.bindProxyNodeRow(row, profile, pickerRow, showGroupInSubtitle) { markRowSelected(list, row) }
            list.addView(row)
        }
        list.tag = structureTag
    }

    /** Clear all rows' selected-state ornaments, then mark [target] as selected. */
    private fun markRowSelected(list: ViewGroup, target: View) {
        for (i in 0 until list.childCount) {
            val r = list.getChildAt(i)
            r.isSelected = false
            r.findViewById<View>(R.id.selected_bar)?.visibility = View.INVISIBLE
            r.findViewById<View>(R.id.selected_check)?.visibility = View.GONE
        }
        target.isSelected = true
        target.findViewById<View>(R.id.selected_bar).visibility = View.VISIBLE
        target.findViewById<View>(R.id.selected_check).visibility = View.VISIBLE
    }

    private fun addEmptyProxyHint(
        list: ViewGroup,
        context: Context,
        messageRes: Int = R.string.proxy_nodes_empty_connect_vpn,
    ) {
        val tv = TextView(context).apply {
            text = context.getString(messageRes)
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
            setTextColor(ContextCompat.getColor(context, R.color.delay_timeout))
        }
        list.addView(tv)
    }

    private fun formatUsageLine(p: Profile): String {
        val used = (p.download + p.upload).toBytesString()
        return if (p.total < 2) {
            "$used / ∞"
        } else {
            "$used / ${p.total.toBytesString()}"
        }
    }

    internal fun bindProfileFlag(
        binding: AdapterProfileBinding,
        context: Context,
        flag: ParsedFlag?,
    ) {
        if (flag != null) {
            binding.profileFlagCard.visibility = View.VISIBLE
            binding.profileIcon.visibility = View.GONE
            binding.profileIconEmoji.visibility = View.GONE
            val sizePx = context.dp(40)
            val bitmap = FlagDrawableLoader.loadBitmap(context, flag.code, sizePx)
            if (bitmap != null) {
                binding.profileFlagImage.setImageBitmap(bitmap)
                binding.profileFlagImage.visibility = View.VISIBLE
                binding.profileFlag.visibility = View.GONE
            } else {
                binding.profileFlagImage.visibility = View.GONE
                binding.profileFlag.text = flag.emoji
                binding.profileFlag.visibility = View.VISIBLE
            }
        } else {
            binding.profileFlagCard.visibility = View.GONE
            binding.profileFlagImage.visibility = View.GONE
            binding.profileFlag.visibility = View.GONE
            binding.profileIcon.visibility = View.VISIBLE
            binding.profileIconEmoji.visibility = View.GONE
        }
    }

    internal fun profileEmoji(profile: Profile): String {
        val index = (profile.uuid.hashCode() and Int.MAX_VALUE) % profileEmojiPool.size
        return profileEmojiPool[index]
    }
}
