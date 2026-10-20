package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.common.branding.BrandManifest
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.BottomSheetProxyGroupsBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.model.ServerPingMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.ProxyOrdering
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

internal fun ProfileAdapter.openProxySheet(context: Context, profile: Profile) {
    if (!profile.imported || proxySheetDialog?.isShowing == true) return

    val sheet = BottomSheetProxyGroupsBinding.inflate(context.layoutInflater)
    val currentPingMode = UiStore(context).serverPingMode
    if (currentPingMode != serverPingMode) {
        standalonePingDelays.clear()
        lastPingAllAt.clear()
        serverPingMode = currentPingMode
    }
    sheet.proxySheetPingMethod.setText(
        if (serverPingMode == ServerPingMode.ToServer) R.string.server_ping_direct else R.string.server_ping_through
    )
    val dialog = AppBottomSheetDialog(context)
    proxySheetDialog = dialog
    var dismissCleanup: () -> Unit = {}
    dialog.setOnDismissListener {
        proxySheetDialog = null
        dismissCleanup()
    }
    val groupNames = effectiveGroupsForProfile(profile)

    // Subscription pill
    sheet.proxySheetSubName.text = profile.name
    bindSubscriptionStatus(sheet, profile, context)
    bindSubscriptionExpiry(sheet, profile, context)

    if (groupNames.isEmpty()) {
        sheet.proxySheetGroupSegments.visibility = View.GONE
        sheet.proxySheetTestedDot.visibility = View.GONE
        sheet.proxySheetTestedText.visibility = View.GONE
        sheet.proxySheetPingSlot.visibility = View.GONE
        sheet.proxySheetEmpty.text = context.getString(R.string.proxy_nodes_empty_connect_vpn)
        sheet.proxySheetEmpty.visibility = View.VISIBLE
    } else {
        val picked = resolvePreferredGroupFromList(profile, groupNames)
        // Open on the operator's primary group: it's the one Home and the notification show,
        // so "Change node" lands where the visible node lives.
        var idx = primaryGroupIn(profile, groupNames)?.let(groupNames::indexOf)?.takeIf { it >= 0 }
            ?: selectedGroupIndex[profile.uuid]
            ?: groupNames.indexOf(picked).takeIf { i -> i >= 0 }
            ?: 0
        if (idx >= groupNames.size) idx = 0
        selectedGroupIndex[profile.uuid] = idx
        var dropdown = isDropdownLayout()
        val expandedGroups = sheetExpandedGroups.getOrPut(profile.uuid) { linkedSetOf(groupNames[idx]) }
        expandedGroups.retainAll(groupNames.toSet())
        var query = ""
        val uiStore = UiStore(context)
        val manualOrders = groupNames.associateWith { uiStore.proxyOrderFor(profile.uuid, it) }.toMutableMap()
        var sort = if (manualOrders.values.any { it.isNotEmpty() }) ProxyPickerSort.Manual else ProxyPickerSort.Config
        var filter: ProxyPickerFilter = ProxyPickerFilter.CurrentGroup
        var selectedScrolledGroupIndex: Int? = null
        // Per-sheet row cache: buildProxyPickerRows walks every group of the profile,
        // which can be hundreds of nodes for big subscriptions. Invalidated whenever
        // we know live state may have changed (URL-test, ping cycle, group switch).
        var cachedRows: List<ProxyPickerRow>? = null
        var cachedRowsGroupIndex: Int = -1
        var pendingSearch: Runnable? = null

        fun rowsForCurrentGroup(selectedIndex: Int): List<ProxyPickerRow> {
            val cached = cachedRows
            if (cached != null && cachedRowsGroupIndex == selectedIndex) return cached
            val fresh = buildProxyPickerRows(profile, groupNames, selectedIndex)
            cachedRows = fresh
            cachedRowsGroupIndex = selectedIndex
            return fresh
        }

        fun invalidateRowCache() {
            cachedRows = null
            cachedRowsGroupIndex = -1
        }

        fun sortLabel(s: ProxyPickerSort): String = context.getString(
            when (s) {
                ProxyPickerSort.Config -> R.string.profile_proxy_sort_config
                ProxyPickerSort.Manual -> R.string.profile_proxy_sort_manual
                ProxyPickerSort.Delay -> R.string.profile_proxy_sort_delay
                ProxyPickerSort.Name -> R.string.profile_proxy_sort_name
            },
        )

        fun filterLabel(f: ProxyPickerFilter): String = when (f) {
            ProxyPickerFilter.All -> context.getString(R.string.profile_proxy_filter_all)
            ProxyPickerFilter.CurrentGroup -> context.getString(R.string.profile_proxy_filter_current)
            ProxyPickerFilter.Selected -> context.getString(R.string.profile_proxy_filter_selected)
            ProxyPickerFilter.Available -> context.getString(R.string.profile_proxy_filter_available)
            is ProxyPickerFilter.Provider ->
                context.getString(R.string.profile_proxy_filter_provider, f.name)
        }

        /**
         * Sort and filter are icon-only buttons, so the current selection can no longer be read
         * off a label. Carry it two ways instead: tint the icon with colorPrimary when the
         * choice is not the default, and put the label in the tooltip / content description so
         * it stays available to a long-press and to TalkBack.
         */
        fun updateControlLabels() {
            fun bind(button: MaterialButton, label: String, active: Boolean) {
                val tint = MaterialColors.getColor(
                    button,
                    if (active) {
                        com.google.android.material.R.attr.colorPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    },
                )
                button.iconTint = ColorStateList.valueOf(tint)
                button.contentDescription = label
                TooltipCompat.setTooltipText(button, label)
            }
            bind(
                sheet.proxySheetSortButton,
                sortLabel(sort),
                active = sort != ProxyPickerSort.Config,
            )
            bind(
                sheet.proxySheetFilterButton,
                filterLabel(filter),
                active = filter != ProxyPickerFilter.CurrentGroup,
            )
        }

        // The node list is a RecyclerView (virtualized — see O-07). render() references
        // nodeAdapter, and the adapter's selection callback references render(): break that
        // cycle via renderFn, assigned right after render() is defined below.
        var renderFn: (Int) -> Unit = {}
        val nodeAdapter = ProxyNodeAdapter(this, profile, onSelectionChanged = {
            // Tapping a node sets a pending selection; the cached rows still carry the OLD
            // `selected` flags, so drop the cache before re-render or submitList would diff
            // against identical data and the check-mark would never move to the new node.
            invalidateRowCache()
            renderFn(selectedGroupIndex[profile.uuid] ?: 0)
        }, onNodeActions = { anchor, row ->
            val names = ProxyOrdering.resolve(
                manualOrders[row.groupName].orEmpty(),
                rowsForCurrentGroup(selectedGroupIndex[profile.uuid] ?: 0)
                    .filter { it.groupName == row.groupName }.map { it.proxy.name },
            )
            val position = names.indexOf(row.proxy.name)
            android.widget.PopupMenu(context, anchor).apply {
                menu.add(0, 0, 0, R.string.profile_proxy_move_up).isEnabled = position > 0
                menu.add(0, 1, 1, R.string.profile_proxy_move_down).isEnabled = position >= 0 && position < names.lastIndex
                menu.add(0, 2, 2, R.string.profile_proxy_details)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 2) {
                        onProxyYamlDetail(profile, row.groupName, row.proxy.name)
                    } else {
                        val moved = ProxyOrdering.move(names, row.proxy.name, if (item.itemId == 0) -1 else 1)
                        manualOrders[row.groupName] = moved
                        uiStore.setProxyOrderFor(profile.uuid, row.groupName, moved)
                        sort = ProxyPickerSort.Manual
                        updateControlLabels()
                        renderFn(selectedGroupIndex[profile.uuid] ?: 0)
                    }
                    true
                }
            }.show()
        })
        sheet.proxySheetNodesList.layoutManager = LinearLayoutManager(context)
        sheet.proxySheetNodesList.adapter = nodeAdapter
        // Rebinds during live ping / selection are content-only; suppress the change animation
        // so delay capsules update without a flicker.
        sheet.proxySheetNodesList.itemAnimator = null
        ContextCompat.getDrawable(context, R.drawable.divider_node_hairline)?.let { d ->
            sheet.proxySheetNodesList.addItemDecoration(NodeDividerDecoration(d))
        }

        fun isGroupPinging(groupName: String): Boolean =
            states.pingingUuid == profile.uuid && pingingGroupByUuid[profile.uuid] == groupName

        /**
         * Dropdown layout: every group is a collapsible header with its nodes underneath when
         * expanded. Search / sort / filter apply inside each group; while they narrow the list,
         * groups without a match are dropped and the rest open so the matches are visible.
         */
        fun dropdownItems(selectedIndex: Int): List<PickerItem> {
            val allRows = rowsForCurrentGroup(selectedIndex)
            val narrowing = query.isNotBlank() ||
                (filter != ProxyPickerFilter.All && filter != ProxyPickerFilter.CurrentGroup)
            return buildList {
                groupNames.forEachIndexed { index, groupName ->
                    val groupRows = allRows.filter { it.groupIndex == index }
                    val shown = applyProxyPickerControls(
                        rows = groupRows,
                        query = query,
                        sort = sort,
                        filter = filter,
                        currentGroupIndex = index,
                        manualOrders = manualOrders,
                    )
                    if (narrowing && shown.isEmpty()) return@forEachIndexed
                    val expanded = narrowing || groupName in expandedGroups
                    add(
                        PickerItem.Header(
                            groupIndex = index,
                            groupName = groupName,
                            summary = groupSelectionSummary(profile, groupName),
                            count = groupRows.size,
                            expanded = expanded,
                            pinging = isGroupPinging(groupName),
                        ),
                    )
                    if (expanded) shown.forEach { add(PickerItem.Node(it)) }
                }
            }
        }

        fun render(selectedIndex: Int) {
            selectedGroupIndex[profile.uuid] = selectedIndex
            sheet.proxySheetGroupScroll.visibility = if (dropdown) View.GONE else View.VISIBLE
            if (!dropdown) {
                renderGroupSegmentsInto(
                    sheet.proxySheetGroupSegments,
                    profile,
                    groupNames,
                    selectedIndex,
                ) { index, group ->
                    render(index)
                    reportVisibleGroup(profile, group, force = true)
                }
            }
            val items = if (dropdown) {
                nodeAdapter.showGroupInSubtitle = false
                dropdownItems(selectedIndex)
            } else {
                val rows = applyProxyPickerControls(
                    rows = rowsForCurrentGroup(selectedIndex),
                    query = query,
                    sort = sort,
                    filter = filter,
                    currentGroupIndex = selectedIndex,
                    manualOrders = manualOrders,
                )
                nodeAdapter.showGroupInSubtitle = rows.map { it.groupName }.distinct().size > 1
                rows.map { PickerItem.Node(it) }
            }
            nodeAdapter.submitList(items)
            if (items.isEmpty()) {
                val groupHasNodes = rowsForCurrentGroup(selectedIndex).isNotEmpty()
                val hint = proxyPickerEmptyHint(profile, groupNames.getOrNull(selectedIndex), groupHasNodes)
                // Loading shows the skeleton rows; every other empty case says why in words.
                val loading = hint == R.string.proxy_nodes_loading
                sheet.proxySheetSkeleton.visibility = if (loading) View.VISIBLE else View.GONE
                sheet.proxySheetEmpty.text = context.getString(hint)
                sheet.proxySheetEmpty.visibility = if (loading) View.GONE else View.VISIBLE
            } else {
                sheet.proxySheetSkeleton.visibility = View.GONE
                sheet.proxySheetEmpty.visibility = View.GONE
            }
            bindTestedAgo(sheet, profile, context)
            if (selectedScrolledGroupIndex != selectedIndex) {
                selectedScrolledGroupIndex = selectedIndex
                scrollProxyPickerToSelected(sheet, items, selectedIndex)
            }
        }
        renderFn = ::render

        fun visibleRows(): List<ProxyPickerRow> {
            val currentIndex = (selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex)
            return applyProxyPickerControls(
                rows = rowsForCurrentGroup(currentIndex),
                query = query,
                sort = sort,
                filter = filter,
                currentGroupIndex = currentIndex,
                manualOrders = manualOrders,
            )
        }

        var wasPinging = states.pingingUuid == profile.uuid
        val refreshRunnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val pingingNow = states.pingingUuid == profile.uuid
                val currentIndex = (selectedGroupIndex[profile.uuid] ?: 0)
                    .coerceIn(0, groupNames.lastIndex)

                when {
                    pingingNow -> {
                        // Live ping: patch delays on the CURRENT row order (no re-sort /
                        // re-filter, so the structure stays frozen and the user can keep
                        // scrolling and tapping). DiffUtil rebinds only the capsules whose
                        // delay actually changed — no full re-inflate.
                        val patched = nodeAdapter.currentList.map { item ->
                            when (item) {
                                is PickerItem.Node -> PickerItem.Node(
                                    item.row.copy(delayMs = resolveProxyDelay(profile.uuid, item.row.proxy)),
                                )
                                is PickerItem.Header -> item.copy(pinging = isGroupPinging(item.groupName))
                            }
                        }
                        nodeAdapter.submitList(patched)
                    }
                    wasPinging -> {
                        // Ping just finished — one full render so Delay
                        // sort and the Available filter pick up the fresh
                        // results and the list re-orders exactly once.
                        invalidateRowCache()
                        render(currentIndex)
                    }
                    // else — steady idle: nothing rebuilds. The picker has
                    // nothing that changes on its own between ping runs;
                    // only the lightweight "tested ago" / status text below
                    // ticks.
                }
                wasPinging = pingingNow

                bindSubscriptionStatus(sheet, profile, context)
                bindTestedAgo(sheet, profile, context)
                sheet.proxySheetPingProgress.visibility = if (pingingNow) View.VISIBLE else View.GONE
                sheet.proxySheetPingButton.visibility = if (pingingNow) View.INVISIBLE else View.VISIBLE
                // 260ms while pinging keeps the live delay capsules fresh.
                // Idle interval is 2500ms — only the "tested ago" label
                // ticks, and the sheet may stay open for a while.
                if (pingingNow) {
                    sheet.root.postDelayed(this, 260L)
                } else {
                    sheet.root.postDelayed(this, 2500L)
                }
            }
        }

        val pinging = states.pingingUuid == profile.uuid
        sheet.proxySheetPingProgress.visibility = if (pinging) View.VISIBLE else View.GONE
        sheet.proxySheetPingButton.visibility = if (pinging) View.INVISIBLE else View.VISIBLE
        // Resolves what a ping would cover right now: the rows the user can actually see in
        // the current group, falling back to the group's own members when the list has not
        // been laid out yet. Shared by the tap and the long press so both measure the same set.
        fun pingTargets(): Pair<String, List<String>>? {
            val currentIndex = selectedGroupIndex[profile.uuid] ?: 0
            val groupName = groupNames.getOrNull(currentIndex) ?: return null
            val names = visibleRows()
                .filter { it.groupIndex == currentIndex }
                .map { it.proxy.name }
                .ifEmpty {
                    proxyGroupForRow(profile, groupName)
                        ?.proxies
                        ?.filterNot { shouldHideProxyOption(groupName, it) }
                        ?.map { it.name }
                        .orEmpty()
                }
            if (names.isEmpty()) return null
            return groupName to names
        }

        fun startPing(groupName: String, names: List<String>, testUrl: String) {
            lastPingAllAt[profile.uuid] = System.currentTimeMillis()
            pingingGroupByUuid[profile.uuid] = groupName
            onPingAll(profile, groupName, names, testUrl)
            sheet.root.post(refreshRunnable)
        }

        sheet.proxySheetPingButton.setOnClickListener {
            val (groupName, names) = pingTargets() ?: return@setOnClickListener
            startPing(groupName, names, "")
        }
        // Long press picks a different target for one run. Deliberately not a setting: the
        // default check URL is what the subscription tuned its own auto-switching around, so
        // making a custom host permanent here would quietly change what "fastest" means.
        //
        // Targets are resolved on confirm, not here. Resolving up front meant that whenever the
        // current group had no rows to measure the gesture returned silently, which from the
        // outside is indistinguishable from a long press that does not work at all.
        sheet.proxySheetPingButton.setOnLongClickListener {
            if (serverPingMode == ServerPingMode.ToServer) {
                Toast.makeText(context, R.string.server_ping_url_requires_proxy, Toast.LENGTH_LONG).show()
                return@setOnLongClickListener true
            }
            showLatencyTargetDialog(
                sheet.root.context,
                lastLatencyTarget,
                { lastLatencyTarget = it },
            ) { url ->
                val targets = pingTargets()
                if (targets == null) {
                    Toast.makeText(
                        sheet.root.context,
                        R.string.latency_target_nothing_to_test,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@showLatencyTargetDialog
                }
                startPing(targets.first, targets.second, url)
            }
            true
        }

        sheet.proxySheetSearch.addTextChangedListener { editable ->
            val newQuery = editable?.toString().orEmpty()
            sheet.proxySheetSearchClear.visibility =
                if (newQuery.isNotEmpty()) View.VISIBLE else View.GONE
            pendingSearch?.let(sheet.proxySheetSearch::removeCallbacks)
            val runnable = Runnable {
                query = newQuery
                render((selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
            }
            pendingSearch = runnable
            sheet.proxySheetSearch.postDelayed(runnable, 200L)
        }
        sheet.proxySheetSearchClear.setOnClickListener {
            sheet.proxySheetSearch.setText("")
        }
        // Sort dropdown includes the saved display order; it never changes the engine config.
        sheet.proxySheetSortButton.setOnClickListener { anchor ->
            android.widget.PopupMenu(context, anchor).apply {
                val options = listOf(
                    ProxyPickerSort.Config,
                    ProxyPickerSort.Manual,
                    ProxyPickerSort.Delay,
                    ProxyPickerSort.Name,
                )
                options.forEachIndexed { i, opt -> menu.add(0, i, i, sortLabel(opt)) }
                setOnMenuItemClickListener { item ->
                    sort = options.getOrElse(item.itemId) { ProxyPickerSort.Config }
                    updateControlLabels()
                    render((selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
                    true
                }
            }.show()
        }
        // Filter dropdown — four fixed options plus one entry per provider
        // present in the current group. The provider list is rebuilt at
        // click time so it always reflects the group currently shown.
        sheet.proxySheetFilterButton.setOnClickListener { anchor ->
            val currentIndex = (selectedGroupIndex[profile.uuid] ?: 0)
                .coerceIn(0, groupNames.lastIndex)
            val providers = rowsForCurrentGroup(currentIndex)
                .mapNotNull { it.provider }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            android.widget.PopupMenu(context, anchor).apply {
                val fixed = listOf(
                    ProxyPickerFilter.All,
                    ProxyPickerFilter.CurrentGroup,
                    ProxyPickerFilter.Selected,
                    ProxyPickerFilter.Available,
                )
                fixed.forEachIndexed { i, opt -> menu.add(0, i, i, filterLabel(opt)) }
                // Provider entries use ids offset by 100 to never collide
                // with the fixed-option ids above.
                providers.forEachIndexed { i, p ->
                    menu.add(
                        0, 100 + i, 100 + i,
                        context.getString(R.string.profile_proxy_filter_provider, p),
                    )
                }
                setOnMenuItemClickListener { item ->
                    filter = if (item.itemId >= 100) {
                        providers.getOrNull(item.itemId - 100)
                            ?.let(ProxyPickerFilter::Provider)
                            ?: ProxyPickerFilter.All
                    } else {
                        fixed.getOrElse(item.itemId) { ProxyPickerFilter.All }
                    }
                    updateControlLabels()
                    render((selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
                    true
                }
            }.show()
        }

        nodeAdapter.onHeaderToggle = { header ->
            if (!expandedGroups.remove(header.groupName)) {
                expandedGroups.add(header.groupName)
                reportVisibleGroup(profile, header.groupName, force = true)
            }
            render(header.groupIndex)
        }
        nodeAdapter.onHeaderPing = { header ->
            val names = rowsForCurrentGroup(header.groupIndex)
                .filter { it.groupIndex == header.groupIndex }
                .map { it.proxy.name }
            if (names.isNotEmpty()) startPing(header.groupName, names, "")
        }

        fun bindLayoutButton() {
            val button = sheet.proxySheetLayoutButton
            button.setIconResource(if (dropdown) R.drawable.ic_view_tabs else R.drawable.ic_view_dropdown)
            val label = context.getString(
                if (dropdown) R.string.proxy_layout_show_tabs else R.string.proxy_layout_show_dropdown,
            )
            button.contentDescription = label
            TooltipCompat.setTooltipText(button, label)
        }
        sheet.proxySheetLayoutButton.setOnClickListener {
            dropdown = !dropdown
            writeProxyGroupLayout(
                if (dropdown) BrandManifest.PROXY_GROUP_LAYOUT_DROPDOWN else BrandManifest.PROXY_GROUP_LAYOUT_TABS,
            )
            bindLayoutButton()
            selectedScrolledGroupIndex = null
            render((selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
        }

        bindLayoutButton()
        updateControlLabels()
        render(idx)
        reportVisibleGroup(profile, groupNames[idx])

        sheet.root.post(refreshRunnable)
        sheetDelayPatcher = { proxyName ->
            if (dialog.isShowing) {
                val list = nodeAdapter.currentList
                val idx = list.indexOfFirst { it is PickerItem.Node && it.row.proxy.name == proxyName }
                if (idx >= 0) {
                    // Resolve against the LIVE engine detail, not the row's own Proxy snapshot:
                    // rows are built once per render, so their embedded delay is stale by now.
                    val row = (list[idx] as PickerItem.Node).row
                    val fresh = proxyDetails[row.groupName]?.proxies?.firstOrNull { it.name == proxyName }
                        ?: row.proxy
                    val patched = list.toMutableList().also { rows ->
                        rows[idx] = PickerItem.Node(
                            row.copy(proxy = fresh, delayMs = resolveProxyDelay(profile.uuid, fresh)),
                        )
                    }
                    // notifyItemChanged after submitList: DiffUtil skips a row whose delay did
                    // not change, but its "…" text still has to be replaced by the value.
                    nodeAdapter.submitList(patched) { nodeAdapter.notifyItemChanged(idx) }
                }
            }
        }
        // Live detail for a just-opened group lands after the first render; repaint so headers
        // pick up the group's choice and new rows appear. Frozen while a ping runs (the ping
        // tick owns the list then and re-renders once when it ends).
        sheetDetailsRefresher = {
            if (dialog.isShowing && states.pingingUuid != profile.uuid) {
                invalidateRowCache()
                render((selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
            }
        }
        dismissCleanup = {
            sheetDetailsRefresher = null
            sheetDelayPatcher = null
            sheet.root.removeCallbacks(refreshRunnable)
            pendingSearch?.let(sheet.proxySheetSearch::removeCallbacks)
            pendingSearch = null
        }
    }

    dialog.setContentView(sheet.root)
    dialog.show()
    // Open fully expanded, single-state (no collapsed peek). A previous peek override
    // (skipCollapsed=false + peekHeight=0.55h) caused two real bugs: (1) the sheet flashed
    // at peek then animated to full on the next frame ("comes out in two stages"), and
    // (2) on a short list, dragging to the end settled into the collapsed state and
    // dismissed instead of just stopping. The base AppBottomSheetDialog already forces
    // skipCollapsed=true + STATE_EXPANDED on show, so scrolling to the list end simply
    // stops and only a deliberate drag-down from the top dismisses.
    dialog.behavior.skipCollapsed = true
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
}
