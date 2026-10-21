package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.BottomSheetProxyGroupsBinding
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.FlagParser
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.proxyTransportLabel
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.color.MaterialColors

internal fun ProfileAdapter.bindSubscriptionStatus(
    sheet: BottomSheetProxyGroupsBinding,
    profile: Profile,
    context: Context,
) {
    val connected = clashRunning && useEngineFor(profile)
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

internal fun ProfileAdapter.bindTestedAgo(
    sheet: BottomSheetProxyGroupsBinding,
    profile: Profile,
    context: Context,
) {
    val triggered = lastPingAllAt[profile.uuid]
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

internal fun ProfileAdapter.renderGroupSegmentsInto(
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
        val count = (proxyGroupForRow(profile, groupName)?.proxies?.size ?: 0).toString()
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

internal fun scrollProxyPickerToSelected(
    sheet: BottomSheetProxyGroupsBinding,
    items: List<PickerItem>,
    groupIndex: Int,
) {
    val dropdown = items.any { it is PickerItem.Header }
    val index = items.indexOfFirst {
        it is PickerItem.Node && it.row.selected && (!dropdown || it.row.groupIndex == groupIndex)
    }
    if (index < 0) return
    sheet.proxySheetNodesList.post {
        (sheet.proxySheetNodesList.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(index, 0)
    }
}

internal fun bindPickerGroupHeader(
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
    com.github.kr328.clash.design.motion.animateDisclosure(view.findViewById(R.id.group_block_chevron), item.groupName, if (item.expanded) 0f else -90f)
    view.findViewById<View>(R.id.group_block_ping_progress).visibility =
        if (item.pinging) View.VISIBLE else View.GONE
    view.findViewById<View>(R.id.group_block_ping).apply {
        visibility = if (item.pinging) View.INVISIBLE else View.VISIBLE
        setOnClickListener { onPing(item) }
    }
    view.findViewById<View>(R.id.group_block_header).setOnClickListener { onToggle(item) }
}

/**
 * Binds one proxy-node row view from a [ProxyPickerRow]. Shared by [ProxyNodeAdapter], whose
 * RecyclerView recycles views so only the ~10 visible rows are ever inflated — a 200-node group
 * no longer inflates 200 views synchronously and freezes the UI on open (O-07).
 */
internal fun ProfileAdapter.bindProxyNodeRow(
    row: View,
    profile: Profile,
    pickerRow: ProxyPickerRow,
    showGroupInSubtitle: Boolean,
    onSelectionChanged: () -> Unit,
    onNodeActions: (View, ProxyPickerRow) -> Unit,
) {
    val context = row.context
    val p = pickerRow.proxy
    val groupName = pickerRow.groupName

    val title = p.title.ifBlank { p.name }
    val flag = FlagParser.parse(title)
    val cleanTitle = flag?.let {
        title.removePrefix(it.emoji)
            .trimStart(' ', '|', '-', '_', '.', ':')
            .ifBlank { title }
    } ?: title
    row.findViewById<TextView>(R.id.proxy_title).apply {
        text = cleanTitle
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, cleanTitle)
    }
    val flagCard = row.findViewById<View>(R.id.proxy_flag_card)
    val flagImage = row.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.proxy_flag_image)
    val flagText = row.findViewById<TextView>(R.id.proxy_flag)
    if (flag != null) {
        flagCard.visibility = View.VISIBLE
        val sizePx = context.dp(28)
        val bitmap = FlagDrawableLoader.loadBitmap(context, flag.code, sizePx)
        if (bitmap != null) {
            flagImage.setImageBitmap(bitmap)
            flagImage.visibility = View.VISIBLE
            flagText.visibility = View.GONE
        } else {
            flagImage.visibility = View.GONE
            flagText.text = flag.emoji
            flagText.visibility = View.VISIBLE
        }
    } else {
        flagCard.visibility = View.GONE
    }

    val typeBadge = row.findViewById<TextView>(R.id.proxy_type_badge)
    val transportBadge = row.findViewById<TextView>(R.id.proxy_transport_badge)
    val realityBadge = row.findViewById<TextView>(R.id.proxy_reality_badge)
    val typeName = p.type.name

    // Leaf node → protocol chips (VLESS / GRPC / REALITY). Nested auto-group →
    // a single group-type chip (URL-TEST / FALLBACK / …) so groups read distinctly
    // from nodes. Both resolve offline (overlay: type from transport/groups preview).
    val groupTypeLabel = groupTypeLabel(p.type)
    if (p.type.group) {
        if (groupTypeLabel != null) {
            applyProtoChip(typeBadge, groupTypeLabel, ContextCompat.getColor(context, groupTypeColor(p.type)))
        } else {
            typeBadge.visibility = View.GONE
        }
        transportBadge.visibility = View.GONE
        realityBadge.visibility = View.GONE
    } else {
        val showBadge = typeName != "Unknown"
        if (showBadge) {
            applyProtoChip(typeBadge, typeName.uppercase(), ContextCompat.getColor(context, protocolFamilyColor(typeName)))
        } else {
            typeBadge.visibility = View.GONE
        }

        val transportInfo = if (showBadge) transportInfoByProfile[profile.uuid]?.get(p.name) else null
        val transportLabel = proxyTransportLabel(p.type, transportInfo)
        if (transportLabel != null) {
            applyProtoChip(transportBadge, transportLabel, ContextCompat.getColor(context, R.color.proto_transport))
        } else {
            transportBadge.visibility = View.GONE
        }
        if (transportInfo?.reality == true) {
            applyProtoChip(realityBadge, "REALITY", ContextCompat.getColor(context, R.color.proto_reality))
        } else {
            realityBadge.visibility = View.GONE
        }
    }

    val rawSubtitle = p.subtitle
        .takeIf { it.isNotBlank() && !it.equals(typeName, ignoreCase = true) }
    val subtitle = buildList {
        rawSubtitle?.let(::add)
        if (showGroupInSubtitle) add(displayGroupName(groupName))
    }.joinToString(" · ").takeIf { it.isNotBlank() }
    row.findViewById<TextView>(R.id.proxy_subtitle).apply {
        visibility = if (subtitle == null) View.GONE else View.VISIBLE
        text = subtitle.orEmpty()
    }
    val delayMs = pickerRow.delayMs

    val capsule = row.findViewById<View>(R.id.latency_capsule)
    val dot = row.findViewById<View>(R.id.latency_dot)
    val delayView = row.findViewById<TextView>(R.id.proxy_delay)
    bindDelayCapsule(capsule, dot, delayView, p.name, delayMs)
    // Tap the capsule → measure just this node. The capsule sits inside the clickable row, so
    // it consumes the touch and the row's select/detail handlers do not fire.
    capsule.setOnClickListener {
        if (!markNodePingPending(p.name)) return@setOnClickListener
        delayView.text = NODE_PING_PENDING_TEXT
        onPingNode(profile, groupName, p.name)
    }

    val selected = pickerRow.selected
    row.isSelected = selected
    row.findViewById<View>(R.id.selected_bar).visibility =
        if (selected) View.VISIBLE else View.INVISIBLE
    row.findViewById<View>(R.id.selected_check).visibility =
        if (selected) View.VISIBLE else View.INVISIBLE
    val mainHit = row.findViewById<View>(R.id.proxy_row_main_hit)
    val tryPickNode: () -> Boolean = {
        val canPickLive = clashRunning && useEngineFor(profile)
        val canPickOffline = profile.imported && profile.uuid == activeProfileUuid
        if (canPickLive || canPickOffline) {
            setPendingProxySelection(profile.uuid, groupName, p.name)
            // Re-submit the list so the newly-selected and previously-selected rows rebind
            // (the RecyclerView is data-driven; DiffUtil touches just those two rows).
            onSelectionChanged()
            onProxyNodeSelected(profile, groupName, p.name)
            true
        } else {
            false
        }
    }
    mainHit.setOnClickListener {
        if (!tryPickNode()) {
            onProxyYamlDetail(profile, groupName, p.name)
        }
    }
    mainHit.setOnLongClickListener {
        onNodeActions(it, pickerRow)
        true
    }
}

/** Latency capsule text + style; a node with a single-node test in flight shows "…". */
internal fun ProfileAdapter.bindDelayCapsule(capsule: View, dot: View, delayView: TextView, proxyName: String, delayMs: Int) {
    applyDelayStyle(capsule, dot, delayView, delayMs, capsule.context)
    delayView.text = if (isNodePingPending(proxyName)) NODE_PING_PENDING_TEXT else formatDelay(delayMs, delayView.context)
    // The capsule is a button, so TalkBack reads its description instead of the child text;
    // lead with the value so the latency is not lost behind the action label.
    capsule.contentDescription = "${delayView.text}. ${capsule.context.getString(R.string.proxy_ping_node)}"
}

internal class ProxyNodeAdapter(
    private val owner: ProfileAdapter,
    private val profile: Profile,
    private val onSelectionChanged: () -> Unit,
    private val onNodeActions: (View, ProxyPickerRow) -> Unit,
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
                owner.bindProxyNodeRow(holder.itemView, profile, item.row, showGroupInSubtitle, onSelectionChanged, onNodeActions)
        }
    }
}
