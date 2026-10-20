package com.github.kr328.clash.design.adapter

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.util.ProxyOrdering

internal enum class ProxyPickerSort {
    Config,
    Manual,
    Delay,
    Name,
}

internal sealed class ProxyPickerFilter {
    object All : ProxyPickerFilter()
    object CurrentGroup : ProxyPickerFilter()
    object Selected : ProxyPickerFilter()
    object Available : ProxyPickerFilter()
    data class Provider(val name: String) : ProxyPickerFilter()
}

/** One row of the picker list: a collapsible group header (dropdown layout only) or a node. */
internal sealed class PickerItem {
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

/** Shown in the latency capsule while a single-node test is in flight. */
internal const val NODE_PING_PENDING_TEXT = "…"

internal const val PICKER_VIEW_NODE = 0
internal const val PICKER_VIEW_HEADER = 1

internal val proxyNodeDiff = object : DiffUtil.ItemCallback<ProxyPickerRow>() {
    override fun areItemsTheSame(oldItem: ProxyPickerRow, newItem: ProxyPickerRow): Boolean =
        oldItem.groupName == newItem.groupName && oldItem.proxy.name == newItem.proxy.name

    // ProxyPickerRow is a data class — equals covers delay/selected/proxy, so DiffUtil rebinds
    // only the rows whose delay or selection actually changed (e.g. live-ping capsule updates),
    // which is what refreshProxyDelaysInPlace used to hand-roll.
    override fun areContentsTheSame(oldItem: ProxyPickerRow, newItem: ProxyPickerRow): Boolean =
        oldItem == newItem
}

internal val pickerItemDiff = object : DiffUtil.ItemCallback<PickerItem>() {
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

/** Hairline between two adjacent node rows only — never around the dropdown layout's group headers. */
internal class NodeDividerDecoration(private val divider: Drawable) : RecyclerView.ItemDecoration() {
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

internal class ProxyNodeViewHolder(view: View) : RecyclerView.ViewHolder(view)

internal fun applyProxyPickerControls(
    rows: List<ProxyPickerRow>,
    query: String,
    sort: ProxyPickerSort,
    filter: ProxyPickerFilter,
    currentGroupIndex: Int,
    manualOrders: Map<String, List<String>>,
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
        ProxyPickerSort.Manual -> {
            val ranks = rows.groupBy { it.groupName }.mapValues { (group, members) ->
                ProxyOrdering.resolve(manualOrders[group].orEmpty(), members.map { it.proxy.name })
                    .withIndex().associate { it.value to it.index }
            }
            filtered.sortedWith(compareBy<ProxyPickerRow> { it.groupIndex }
                .thenBy { ranks[it.groupName]?.get(it.proxy.name) ?: it.configIndex })
        }
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
