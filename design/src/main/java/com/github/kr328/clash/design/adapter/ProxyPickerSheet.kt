package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.BottomSheetProxyGroupsBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.common.branding.BrandManifest
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.color.MaterialColors

private enum class ProxyPickerSort {
    Config,
    Delay,
    Name,
}

private sealed class ProxyPickerFilter {
    object All : ProxyPickerFilter()
    object CurrentGroup : ProxyPickerFilter()
    object Selected : ProxyPickerFilter()
    object Available : ProxyPickerFilter()
    data class Provider(val name: String) : ProxyPickerFilter()
}

/** One row of the picker list: a collapsible group header (dropdown layout only) or a node. */
private sealed class PickerItem {
    data class Header(
        val groupIndex: Int,
        val groupName: String,
        val summary: String?,
        val count: Int,
        val expanded: Boolean,
        val pinging: Boolean,
    ) : PickerItem()

    data class Node(val row: ProxyPickerRow) : PickerItem()
}

internal data class ProxyPickerRow(
    val groupName: String,
    val groupIndex: Int,
    val proxy: Proxy,
    val configIndex: Int,
    val delayMs: Int,
    val selected: Boolean,
    val provider: String?,
) {
    val available: Boolean
        get() = delayMs >= 0
}

/**
 * Owns the node picker bottom sheet opened from a profile: group tabs / dropdown headers, the
 * virtualized node list with sticky headers, search / sort / filter, ping controls and the
 * subscription pill. Reads and updates [ProfileAdapter]'s state through [adapter].
 */
internal class ProxyPickerSheet(private val adapter: ProfileAdapter) {
    /**
     * Asks for a one-shot latency target and hands back a URL ready for URLTest.
     *
     * The last entry is remembered only to prefill the field — the measurement itself is never
     * sticky, so closing the dialog leaves the next tap measuring the default target as before.
     */
    private fun showLatencyTargetDialog(context: Context, onConfirm: (String) -> Unit) {
        val view = context.layoutInflater.inflate(R.layout.dialog_latency_target, null, false)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.latency_target_input_layout)
        val input = view.findViewById<TextInputEditText>(R.id.latency_target_input)
        input.setText(adapter.lastLatencyTarget)
        input.setSelection(input.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton(R.string.latency_target_run, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Validate without dismissing: setPositiveButton's own listener always closes, which would
        // throw the typed text away on a typo.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = adapter.normalizeLatencyTarget(input.text?.toString().orEmpty())
                if (url == null) {
                    inputLayout.error = context.getString(R.string.latency_target_invalid)
                    return@setOnClickListener
                }
                adapter.lastLatencyTarget = input.text?.toString()?.trim().orEmpty()
                dialog.dismiss()
                onConfirm(url)
            }
        }
        dialog.show()
    }

    fun showProxySheet(context: Context, profile: Profile) {
        if (!profile.imported) return

        val sheet = BottomSheetProxyGroupsBinding.inflate(context.layoutInflater)
        val dialog = AppBottomSheetDialog(context)
        val groupNames = adapter.selection.effectiveGroupsForProfile(profile)

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
            val picked = adapter.selection.resolvePreferredGroupFromList(profile, groupNames)
            // Open on the operator's primary group: it's the one Home and the notification show,
            // so "Change node" lands where the visible node lives.
            var idx = adapter.selection.primaryGroupIn(profile, groupNames)?.let(groupNames::indexOf)?.takeIf { it >= 0 }
                ?: adapter.selectedGroupIndex[profile.uuid]
                ?: groupNames.indexOf(picked).takeIf { i -> i >= 0 }
                ?: 0
            if (idx >= groupNames.size) idx = 0
            adapter.selectedGroupIndex[profile.uuid] = idx
            var dropdown = adapter.isDropdownLayout()
            val expandedGroups = adapter.sheetExpandedGroups.getOrPut(profile.uuid) { linkedSetOf() }
            expandedGroups.retainAll(groupNames.toSet())
            // Opening the sheet (from Home's Node row or the notification) always shows the group the
            // visible node lives in; render() then puts its header at the top.
            expandedGroups.add(groupNames[idx])
            var query = ""
            var sort = ProxyPickerSort.Config
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
            val nodeAdapter = ProxyNodeAdapter(profile) {
                // Tapping a node sets a pending selection; the cached rows still carry the OLD
                // `selected` flags, so drop the cache before re-render or submitList would diff
                // against identical data and the check-mark would never move to the new node.
                invalidateRowCache()
                renderFn(adapter.selectedGroupIndex[profile.uuid] ?: 0)
            }
            sheet.proxySheetNodesList.layoutManager = LinearLayoutManager(context)
            StickyGroupHeaderDecoration(nodeAdapter).also {
                sheet.proxySheetNodesList.addItemDecoration(it)
                sheet.proxySheetNodesList.addOnItemTouchListener(it)
            }
            // Rows (and the selected row's outline) must not paint over the well's rounded corners.
            (sheet.proxySheetNodesList.parent as? View)?.clipToOutline = true
            sheet.proxySheetNodesList.adapter = nodeAdapter
            // Rebinds during live ping / selection are content-only; suppress the change animation
            // so delay capsules update without a flicker.
            sheet.proxySheetNodesList.itemAnimator = null
            ContextCompat.getDrawable(context, R.drawable.divider_node_hairline)?.let { d ->
                sheet.proxySheetNodesList.addItemDecoration(NodeDividerDecoration(d))
            }

            fun isGroupPinging(groupName: String): Boolean =
                adapter.states.pingingUuid == profile.uuid && adapter.pingingGroupByUuid[profile.uuid] == groupName

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
                        )
                        if (narrowing && shown.isEmpty()) return@forEachIndexed
                        val expanded = narrowing || groupName in expandedGroups
                        add(
                            PickerItem.Header(
                                groupIndex = index,
                                groupName = groupName,
                                summary = adapter.selection.groupSelectionSummary(profile, groupName),
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
                adapter.selectedGroupIndex[profile.uuid] = selectedIndex
                sheet.proxySheetGroupScroll.visibility = if (dropdown) View.GONE else View.VISIBLE
                if (!dropdown) {
                    renderGroupSegmentsInto(
                        sheet.proxySheetGroupSegments,
                        profile,
                        groupNames,
                        selectedIndex,
                    ) { index, group ->
                        render(index)
                        adapter.reportVisibleGroup(profile, group, force = true)
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
                    )
                    nodeAdapter.showGroupInSubtitle = rows.map { it.groupName }.distinct().size > 1
                    rows.map { PickerItem.Node(it) }
                }
                nodeAdapter.submitList(items)
                if (items.isEmpty()) {
                    val groupHasNodes = rowsForCurrentGroup(selectedIndex).isNotEmpty()
                    sheet.proxySheetEmpty.text =
                        context.getString(proxyPickerEmptyHint(profile, groupNames.getOrNull(selectedIndex), groupHasNodes))
                    sheet.proxySheetEmpty.visibility = View.VISIBLE
                } else {
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
                val currentIndex = (adapter.selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex)
                return applyProxyPickerControls(
                    rows = rowsForCurrentGroup(currentIndex),
                    query = query,
                    sort = sort,
                    filter = filter,
                    currentGroupIndex = currentIndex,
                )
            }

            var wasPinging = adapter.states.pingingUuid == profile.uuid
            val refreshRunnable = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    val pingingNow = adapter.states.pingingUuid == profile.uuid
                    val currentIndex = (adapter.selectedGroupIndex[profile.uuid] ?: 0)
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
                                        item.row.copy(delayMs = adapter.selection.resolveProxyDelay(profile.uuid, item.row.proxy)),
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

            val pinging = adapter.states.pingingUuid == profile.uuid
            sheet.proxySheetPingProgress.visibility = if (pinging) View.VISIBLE else View.GONE
            sheet.proxySheetPingButton.visibility = if (pinging) View.INVISIBLE else View.VISIBLE
            // Resolves what a ping would cover right now: the rows the user can actually see in
            // the current group, falling back to the group's own members when the list has not
            // been laid out yet. Shared by the tap and the long press so both measure the same set.
            fun pingTargets(): Pair<String, List<String>>? {
                val currentIndex = adapter.selectedGroupIndex[profile.uuid] ?: 0
                val groupName = groupNames.getOrNull(currentIndex) ?: return null
                val names = visibleRows()
                    .filter { it.groupIndex == currentIndex }
                    .map { it.proxy.name }
                    .ifEmpty {
                        adapter.selection.proxyGroupForRow(profile, groupName)
                            ?.proxies
                            ?.filterNot { shouldHideProxyOption(groupName, it) }
                            ?.map { it.name }
                            .orEmpty()
                    }
                if (names.isEmpty()) return null
                return groupName to names
            }

            fun startPing(groupName: String, names: List<String>, testUrl: String) {
                adapter.lastPingAllAt[profile.uuid] = System.currentTimeMillis()
                adapter.pingingGroupByUuid[profile.uuid] = groupName
                adapter.onPingAll(profile, groupName, names, testUrl)
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
                showLatencyTargetDialog(sheet.root.context) { url ->
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
                    render((adapter.selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
                }
                pendingSearch = runnable
                sheet.proxySheetSearch.postDelayed(runnable, 200L)
            }
            sheet.proxySheetSearchClear.setOnClickListener {
                sheet.proxySheetSearch.setText("")
            }
            // Sort dropdown — three fixed options.
            sheet.proxySheetSortButton.setOnClickListener { anchor ->
                android.widget.PopupMenu(context, anchor).apply {
                    val options = listOf(
                        ProxyPickerSort.Config,
                        ProxyPickerSort.Delay,
                        ProxyPickerSort.Name,
                    )
                    options.forEachIndexed { i, opt -> menu.add(0, i, i, sortLabel(opt)) }
                    setOnMenuItemClickListener { item ->
                        sort = options.getOrElse(item.itemId) { ProxyPickerSort.Config }
                        updateControlLabels()
                        render((adapter.selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
                        true
                    }
                }.show()
            }
            // Filter dropdown — four fixed options plus one entry per provider
            // present in the current group. The provider list is rebuilt at
            // click time so it always reflects the group currently shown.
            sheet.proxySheetFilterButton.setOnClickListener { anchor ->
                val currentIndex = (adapter.selectedGroupIndex[profile.uuid] ?: 0)
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
                        render((adapter.selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
                        true
                    }
                }.show()
            }

            nodeAdapter.onHeaderToggle = { header ->
                if (!expandedGroups.remove(header.groupName)) {
                    expandedGroups.add(header.groupName)
                    adapter.reportVisibleGroup(profile, header.groupName, force = true)
                }
                // A tap expands in place: the tapped header stays where it is. Marking the group as
                // already scrolled keeps render() from jumping to its selected node (the old
                // first-tap jump).
                selectedScrolledGroupIndex = header.groupIndex
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
                adapter.writeProxyGroupLayout(
                    if (dropdown) BrandManifest.PROXY_GROUP_LAYOUT_DROPDOWN else BrandManifest.PROXY_GROUP_LAYOUT_TABS,
                )
                bindLayoutButton()
                selectedScrolledGroupIndex = null
                render((adapter.selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
            }

            bindLayoutButton()
            updateControlLabels()
            adapter.onPickerOpened(profile)
            render(idx)
            adapter.reportVisibleGroup(profile, groupNames[idx])

            sheet.root.post(refreshRunnable)
            adapter.sheetDelayPatcher = { proxyName ->
                if (dialog.isShowing) {
                    val list = nodeAdapter.currentList
                    val idx = list.indexOfFirst { it is PickerItem.Node && it.row.proxy.name == proxyName }
                    if (idx >= 0) {
                        // Resolve against the LIVE engine detail, not the row's own Proxy snapshot:
                        // rows are built once per render, so their embedded delay is stale by now.
                        val row = (list[idx] as PickerItem.Node).row
                        val fresh = adapter.proxyDetails[row.groupName]?.proxies?.firstOrNull { it.name == proxyName }
                            ?: row.proxy
                        val patched = list.toMutableList().also { rows ->
                            rows[idx] = PickerItem.Node(
                                row.copy(proxy = fresh, delayMs = adapter.selection.resolveProxyDelay(profile.uuid, fresh)),
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
            adapter.sheetDetailsRefresher = {
                if (dialog.isShowing && adapter.states.pingingUuid != profile.uuid) {
                    invalidateRowCache()
                    render((adapter.selectedGroupIndex[profile.uuid] ?: 0).coerceIn(0, groupNames.lastIndex))
                }
            }
            dialog.setOnDismissListener {
                adapter.sheetDetailsRefresher = null
                adapter.sheetDelayPatcher = null
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
        // Material's own drag hid the sheet on the slightest pull of a list at its top (skipCollapsed
        // makes shouldHide() always true); our gesture scrolls the list first and needs a real pull.
        com.github.kr328.clash.design.dialog.SheetDragToDismiss.attach(dialog, sheet.proxySheetNodesList)
        // Fixed height: a content-sized sheet grew every time a group expanded, so the tapped header
        // flew up with the sheet's top edge. The list (weight 1) absorbs the difference instead.
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = (context.resources.displayMetrics.heightPixels * PICKER_SHEET_HEIGHT_FRACTION).toInt()
            }
        }
    }

    private fun bindSubscriptionStatus(
        sheet: BottomSheetProxyGroupsBinding,
        profile: Profile,
        context: Context,
    ) {
        val connected = adapter.clashRunning && adapter.selection.useEngineFor(profile)
        sheet.proxySheetSubStatus.text = context.getString(
            if (connected) R.string.proxy_sheet_status_connected
            else R.string.proxy_sheet_status_disconnected
        )
        val tint = if (connected) {
            MaterialColors.getColor(sheet.root, com.google.android.material.R.attr.colorPrimary)
        } else {
            MaterialColors.getColor(sheet.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        sheet.proxySheetSubStatus.setTextColor(tint)
        sheet.proxySheetSubStatusDot.backgroundTintList = ColorStateList.valueOf(tint)
    }

    private fun bindSubscriptionExpiry(
        sheet: BottomSheetProxyGroupsBinding,
        profile: Profile,
        context: Context,
    ) {
        val text = formatExpiryLeft(profile.expire, context)
        if (text == null) {
            sheet.proxySheetSubExpiry.visibility = View.GONE
        } else {
            sheet.proxySheetSubExpiry.visibility = View.VISIBLE
            sheet.proxySheetSubExpiry.text = text
        }
    }

    private fun formatExpiryLeft(expireMs: Long, context: Context): String? {
        if (expireMs <= 0L) return null
        val now = System.currentTimeMillis()
        val diff = expireMs - now
        if (diff <= 0L) return context.getString(R.string.proxy_sheet_expiry_expired)
        val totalHours = diff / 3_600_000L
        val days = totalHours / 24L
        val hours = totalHours % 24L
        return if (days > 0) {
            context.getString(R.string.proxy_sheet_expiry_days_hours, days.toInt(), hours.toInt())
        } else {
            context.getString(R.string.proxy_sheet_expiry_hours, hours.toInt().coerceAtLeast(1))
        }
    }

    private fun bindTestedAgo(
        sheet: BottomSheetProxyGroupsBinding,
        profile: Profile,
        context: Context,
    ) {
        val triggered = adapter.lastPingAllAt[profile.uuid]
        if (triggered == null) {
            sheet.proxySheetTestedDot.visibility = View.GONE
            sheet.proxySheetTestedText.text = context.getString(R.string.proxy_sheet_tested_never)
            return
        }
        sheet.proxySheetTestedDot.visibility = View.VISIBLE
        val elapsedSec = ((System.currentTimeMillis() - triggered) / 1000L).coerceAtLeast(0L)
        sheet.proxySheetTestedText.text = when {
            elapsedSec < 3 -> context.getString(R.string.proxy_sheet_tested_now)
            elapsedSec < 60 -> context.getString(R.string.proxy_sheet_tested_seconds_ago, elapsedSec.toInt())
            else -> context.getString(R.string.proxy_sheet_tested_minutes_ago, (elapsedSec / 60).toInt())
        }
    }

    private fun renderGroupSegmentsInto(
        container: ViewGroup,
        profile: Profile,
        groupNames: List<String>,
        selectedIndex: Int,
        onSelected: (Int, String) -> Unit,
    ) {
        val context = container.context
        val inflater = context.layoutInflater
        // Re-inflate only when the group set actually changes; otherwise update in place so
        // the per-second refresh tick doesn't tear down + rebuild N segments (felt janky with
        // many groups and broke scroll inertia).
        val structureTag = groupNames.joinToString("|")
        val needsRebuild = container.tag != structureTag || container.childCount != groupNames.size
        if (needsRebuild) {
            container.removeAllViews()
            for (groupName in groupNames) {
                container.addView(inflater.inflate(R.layout.item_proxy_group_segment, container, false))
            }
            container.tag = structureTag
        }
        groupNames.forEachIndexed { index, groupName ->
            val segment = container.getChildAt(index) ?: return@forEachIndexed
            val nameView = segment.findViewById<TextView>(R.id.segment_name)
            val countView = segment.findViewById<TextView>(R.id.segment_count)
            val displayName = displayGroupName(groupName)
            if (nameView.text?.toString() != displayName) nameView.text = displayName
            val count = (adapter.selection.proxyGroupForRow(profile, groupName)?.proxies?.size ?: 0).toString()
            if (countView.text?.toString() != count) countView.text = count
            segment.isSelected = index == selectedIndex
            segment.setOnClickListener {
                if (segment.isSelected) return@setOnClickListener
                for (i in 0 until container.childCount) {
                    container.getChildAt(i).isSelected = i == index
                }
                onSelected(index, groupName)
            }
        }
        // Keep the active segment visible in the scroll area, but only when selection
        // actually changes (or on first render). Without this guard the per-second refresh
        // tick yanks scroll back to the selected pill mid-drag, making it impossible to
        // browse far-away groups.
        val scroller = container.parent as? android.widget.HorizontalScrollView ?: return
        val lastIdxTagKey = scroller.id.takeIf { it != View.NO_ID } ?: return
        val lastIdx = scroller.getTag(lastIdxTagKey) as? Int
        if (lastIdx == selectedIndex) return
        scroller.setTag(lastIdxTagKey, selectedIndex)
        val target = container.getChildAt(selectedIndex) ?: return
        scroller.post {
            val viewLeft = target.left
            val viewRight = target.right
            val scrollX = scroller.scrollX
            val visibleRight = scrollX + scroller.width
            when {
                viewLeft < scrollX -> scroller.smoothScrollTo(maxOf(0, viewLeft - 24), 0)
                viewRight > visibleRight ->
                    scroller.smoothScrollTo(viewRight - scroller.width + 24, 0)
            }
        }
    }

    private val proxyNodeDiff = object : DiffUtil.ItemCallback<ProxyPickerRow>() {
        override fun areItemsTheSame(oldItem: ProxyPickerRow, newItem: ProxyPickerRow): Boolean =
            oldItem.groupName == newItem.groupName && oldItem.proxy.name == newItem.proxy.name

        // ProxyPickerRow is a data class — equals covers delay/selected/proxy, so DiffUtil rebinds
        // only the rows whose delay or selection actually changed (e.g. live-ping capsule updates),
        // which is what refreshProxyDelaysInPlace used to hand-roll.
        override fun areContentsTheSame(oldItem: ProxyPickerRow, newItem: ProxyPickerRow): Boolean =
            oldItem == newItem
    }

    private val pickerItemDiff = object : DiffUtil.ItemCallback<PickerItem>() {
        override fun areItemsTheSame(oldItem: PickerItem, newItem: PickerItem): Boolean = when {
            oldItem is PickerItem.Header && newItem is PickerItem.Header ->
                oldItem.groupName == newItem.groupName
            oldItem is PickerItem.Node && newItem is PickerItem.Node ->
                proxyNodeDiff.areItemsTheSame(oldItem.row, newItem.row)
            else -> false
        }

        override fun areContentsTheSame(oldItem: PickerItem, newItem: PickerItem): Boolean =
            oldItem == newItem
    }

    private inner class ProxyNodeAdapter(
        private val profile: Profile,
        private val onSelectionChanged: () -> Unit,
    ) : ListAdapter<PickerItem, ProxyNodeViewHolder>(pickerItemDiff) {
        /** Recomputed by the caller before each submit; true when the rows span more than one group. */
        var showGroupInSubtitle: Boolean = false
        var onHeaderToggle: (PickerItem.Header) -> Unit = {}
        var onHeaderPing: (PickerItem.Header) -> Unit = {}

        override fun getItemViewType(position: Int): Int =
            if (getItem(position) is PickerItem.Header) PICKER_VIEW_HEADER else PICKER_VIEW_NODE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyNodeViewHolder {
            val layout = if (viewType == PICKER_VIEW_HEADER) {
                R.layout.item_proxy_group_header
            } else {
                R.layout.adapter_home_proxy_node
            }
            return ProxyNodeViewHolder(parent.context.layoutInflater.inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: ProxyNodeViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is PickerItem.Header -> bindPickerGroupHeader(holder.itemView, item, onHeaderToggle, onHeaderPing)
                is PickerItem.Node ->
                    adapter.nodeRows.bindProxyNodeRow(holder.itemView, profile, item.row, showGroupInSubtitle, onSelectionChanged)
            }
        }
    }

    private fun bindPickerGroupHeader(
        view: View,
        item: PickerItem.Header,
        onToggle: (PickerItem.Header) -> Unit,
        onPing: (PickerItem.Header) -> Unit,
    ) {
        view.findViewById<TextView>(R.id.group_block_name).text = displayGroupName(item.groupName)
        view.findViewById<TextView>(R.id.group_block_summary).apply {
            text = item.summary.orEmpty()
            visibility = if (item.summary.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.group_block_count).text = item.count.toString()
        view.findViewById<View>(R.id.group_block_chevron).rotation = if (item.expanded) 0f else -90f
        view.findViewById<View>(R.id.group_block_ping_progress).visibility =
            if (item.pinging) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.group_block_ping).apply {
            visibility = if (item.pinging) View.INVISIBLE else View.VISIBLE
            setOnClickListener { onPing(item) }
        }
        view.findViewById<View>(R.id.group_block_header).setOnClickListener { onToggle(item) }
    }

    /** Hairline between two adjacent node rows only — never around the dropdown layout's group headers. */
    private class NodeDividerDecoration(private val divider: Drawable) : RecyclerView.ItemDecoration() {
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter ?: return
            for (i in 0 until parent.childCount - 1) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                val nextPosition = parent.getChildAdapterPosition(parent.getChildAt(i + 1))
                if (position == RecyclerView.NO_POSITION || nextPosition != position + 1) continue
                if (adapter.getItemViewType(position) != PICKER_VIEW_NODE ||
                    adapter.getItemViewType(nextPosition) != PICKER_VIEW_NODE
                ) {
                    continue
                }
                val top = child.bottom + child.translationY.toInt()
                divider.setBounds(parent.paddingLeft, top, parent.width - parent.paddingRight, top + divider.intrinsicHeight)
                divider.draw(c)
            }
        }
    }

    /**
     * Dropdown layout: while the user scrolls through an expanded group, its header stays pinned at
     * the top of the list (the next group's header pushes it up). A tap on the pinned header
     * collapses the group, the same as a tap on the header row itself.
     */
    private inner class StickyGroupHeaderDecoration(
        private val adapter: ProxyNodeAdapter,
    ) : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {
        private var headerView: View? = null
        private var pinned: PickerItem.Header? = null
        private var pinnedBottom = 0
        private var downX = 0f
        private var downY = 0f
        private var downOnPinned = false

        private fun headerAbove(position: Int): PickerItem.Header? {
            val list = adapter.currentList
            for (i in position downTo 0) {
                val item = list.getOrNull(i)
                if (item is PickerItem.Header) return item
            }
            return null
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            pinned = null
            val first = parent.getChildAt(0) ?: return
            val position = parent.getChildAdapterPosition(first)
            if (position == RecyclerView.NO_POSITION) return
            val header = headerAbove(position) ?: return
            if (!header.expanded) return
            val list = adapter.currentList
            if (list[position] is PickerItem.Header && first.top >= 0) return

            val view = headerView ?: parent.context.layoutInflater
                .inflate(R.layout.item_proxy_group_header, parent, false)
                .also { headerView = it }
            bindPickerGroupHeader(view, header, {}, {})
            val width = parent.width - parent.paddingLeft - parent.paddingRight
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            var top = 0
            for (i in 1 until parent.childCount) {
                val child = parent.getChildAt(i)
                val p = parent.getChildAdapterPosition(child)
                if (p != RecyclerView.NO_POSITION && list.getOrNull(p) is PickerItem.Header) {
                    top = minOf(0, child.top - view.measuredHeight)
                    break
                }
            }
            c.save()
            c.translate(parent.paddingLeft.toFloat(), top.toFloat())
            view.draw(c)
            c.restore()
            pinned = header
            pinnedBottom = top + view.measuredHeight
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    downOnPinned = pinned != null && e.y < pinnedBottom
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val slop = android.view.ViewConfiguration.get(rv.context).scaledTouchSlop
                    val tap = kotlin.math.abs(e.x - downX) < slop && kotlin.math.abs(e.y - downY) < slop
                    val header = pinned
                    if (downOnPinned && tap && header != null && e.y < pinnedBottom) {
                        downOnPinned = false
                        adapter.onHeaderToggle(header)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    private class ProxyNodeViewHolder(view: View) : RecyclerView.ViewHolder(view)

    /**
     * Empty-state hint for the bottom-sheet node list: distinguishes "this group has no nodes yet"
     * (loading / connect the VPN) from "the current filter matched nothing".
     */
    private fun proxyPickerEmptyHint(profile: Profile, groupName: String?, groupHasNodes: Boolean): Int =
        when {
            groupHasNodes -> R.string.profile_proxy_empty_filtered
            adapter.selection.useEngineFor(profile) && !adapter.selection.hasLiveProxyDetail(profile, groupName.orEmpty()) -> R.string.proxy_nodes_loading
            adapter.selection.useEngineFor(profile) -> R.string.proxy_group_empty_runtime
            else -> R.string.proxy_nodes_empty_connect_vpn
        }

    private fun buildProxyPickerRows(
        profile: Profile,
        groupNames: List<String>,
        currentGroupIndex: Int,
    ): List<ProxyPickerRow> {
        val selectedIndex = currentGroupIndex.coerceIn(0, groupNames.lastIndex)
        return groupNames.flatMapIndexed { groupIndex, groupName ->
            val pg = adapter.selection.proxyGroupForRow(profile, groupName) ?: return@flatMapIndexed emptyList()
            val pendingChoice = adapter.selection.pendingMapValueForGroup(profile.uuid, groupName)
                ?.takeIf { it.isNotBlank() }
            val effectiveNow = pendingChoice ?: pg.now
            pg.proxies.mapIndexedNotNull { proxyIndex, proxy ->
                if (shouldHideProxyOption(groupName, proxy)) return@mapIndexedNotNull null
                ProxyPickerRow(
                    groupName = groupName,
                    groupIndex = groupIndex,
                    proxy = proxy,
                    configIndex = groupIndex * 100_000 + proxyIndex,
                    delayMs = adapter.selection.resolveProxyDelay(profile.uuid, proxy),
                    selected = proxy.name.isNotEmpty() && proxy.name == effectiveNow,
                    provider = providerNameForProxy(proxy.name),
                )
            }
        }.let { rows ->
            val selectedGroupRows = rows.filter { it.groupIndex == selectedIndex }
            rows.filterNot { it.groupIndex == selectedIndex } + selectedGroupRows
        }.sortedBy { it.configIndex }
    }

    private fun applyProxyPickerControls(
        rows: List<ProxyPickerRow>,
        query: String,
        sort: ProxyPickerSort,
        filter: ProxyPickerFilter,
        currentGroupIndex: Int,
    ): List<ProxyPickerRow> {
        val normalizedQuery = query.trim()
        val filtered = rows.asSequence()
            .filter { row ->
                normalizedQuery.isBlank() ||
                    row.proxy.name.contains(normalizedQuery, ignoreCase = true) ||
                    row.proxy.title.contains(normalizedQuery, ignoreCase = true) ||
                    row.groupName.contains(normalizedQuery, ignoreCase = true) ||
                    displayGroupName(row.groupName).contains(normalizedQuery, ignoreCase = true) ||
                    row.provider?.contains(normalizedQuery, ignoreCase = true) == true
            }
            .filter { row ->
                when (filter) {
                    ProxyPickerFilter.All -> true
                    ProxyPickerFilter.CurrentGroup -> row.groupIndex == currentGroupIndex
                    ProxyPickerFilter.Selected -> row.selected
                    ProxyPickerFilter.Available -> row.available
                    is ProxyPickerFilter.Provider -> row.provider == filter.name
                }
            }
            .toList()

        return when (sort) {
            ProxyPickerSort.Config -> filtered.sortedBy { it.configIndex }
            ProxyPickerSort.Delay -> filtered.sortedWith(
                compareBy<ProxyPickerRow> { if (it.delayMs >= 0) 0 else 1 }
                    .thenBy { if (it.delayMs >= 0) it.delayMs else Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { displayGroupName(it.groupName) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.proxy.title.ifBlank { it.proxy.name } },
            )
            ProxyPickerSort.Name -> filtered.sortedWith(
                compareBy<ProxyPickerRow, String>(String.CASE_INSENSITIVE_ORDER) { row ->
                    row.proxy.title.ifBlank { row.proxy.name }
                }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { displayGroupName(it.groupName) }
                    .thenBy { it.configIndex },
            )
        }
    }

    private fun scrollProxyPickerToSelected(
        sheet: BottomSheetProxyGroupsBinding,
        items: List<PickerItem>,
        groupIndex: Int,
    ) {
        val dropdown = items.any { it is PickerItem.Header }
        // Dropdown: the group's header goes to the top and its selected node shows (highlighted)
        // below it, so the user sees which group the server is in. Tabs: the selected node.
        val index = if (dropdown) {
            items.indexOfFirst { it is PickerItem.Header && it.groupIndex == groupIndex }
        } else {
            items.indexOfFirst { it is PickerItem.Node && it.row.selected }
        }
        if (index < 0) return
        sheet.proxySheetNodesList.post {
            (sheet.proxySheetNodesList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
        }
    }
}

/** Node picker sheet height as a share of the screen; fixed so expanding a group never resizes it. */
private const val PICKER_SHEET_HEIGHT_FRACTION = 0.88f

private const val PICKER_VIEW_NODE = 0
private const val PICKER_VIEW_HEADER = 1
