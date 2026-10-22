package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import com.github.kr328.clash.design.dialog.SheetDragToDismiss
import com.github.kr328.clash.design.model.RemoteProxyGroup
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/**
 * The node picker for a TV under remote control. Same sheet, rows, chips, latency capsules and
 * sticky dropdown headers as the phone's own picker ([ProxyPickerSheet]), but fed from the TV's
 * `GET /v1/proxies` instead of the local profile: tap a node to select it on the TV, tap a capsule
 * or a header's ping icon to measure on the TV. Sort / filter / layout controls are local-profile
 * features and stay hidden here.
 */
internal class RemoteProxyPickerSheet(
    private val context: Context,
    private val tvName: String,
    private val canSelect: Boolean,
    private val canPing: Boolean,
    private val onSelect: (group: String, node: String) -> Unit,
    private val onPingGroup: (group: String) -> Unit,
    private val onPingNode: (group: String, node: String) -> Unit,
) {
    private sealed class Item {
        data class Header(
            val name: String,
            val now: String,
            val count: Int,
            val expanded: Boolean,
            val pinging: Boolean,
        ) : Item()

        data class Node(
            val group: String,
            val name: String,
            val type: Proxy.Type,
            val delayMs: Int,
            val selected: Boolean,
            val selectable: Boolean,
            val pending: Boolean,
        ) : Item()
    }

    private val sheet = BottomSheetProxyGroupsBinding.inflate(context.layoutInflater)
    private val dialog = AppBottomSheetDialog(context)
    private val nodeAdapter = NodeAdapter()
    private var groups: List<RemoteProxyGroup> = emptyList()
    private val expanded = linkedSetOf<String>()
    private val pingingGroups = mutableSetOf<String>()
    private val pendingNodes = mutableSetOf<Pair<String, String>>()
    private var query = ""
    private var lastPingAt: Long? = null
    private var scrolledOnce = false

    val isShowing: Boolean
        get() = dialog.isShowing

    var onDismiss: () -> Unit = {}

    fun show(initial: List<RemoteProxyGroup>, focusNode: String?, live: Boolean) {
        sheet.proxySheetSubName.text = context.getString(R.string.remote_tv_title, tvName)
        sheet.proxySheetSubExpiry.visibility = View.GONE
        sheet.proxySheetSortButton.visibility = View.GONE
        sheet.proxySheetFilterButton.visibility = View.GONE
        sheet.proxySheetLayoutButton.visibility = View.GONE
        sheet.proxySheetGroupScroll.visibility = View.GONE
        sheet.proxySheetPingSlot.visibility = if (canPing) View.VISIBLE else View.GONE
        setLive(live)

        sheet.proxySheetNodesList.layoutManager = LinearLayoutManager(context)
        StickyHeader().also {
            sheet.proxySheetNodesList.addItemDecoration(it)
            sheet.proxySheetNodesList.addOnItemTouchListener(it)
        }
        (sheet.proxySheetNodesList.parent as? View)?.clipToOutline = true
        sheet.proxySheetNodesList.adapter = nodeAdapter
        sheet.proxySheetNodesList.itemAnimator = null
        ContextCompat.getDrawable(context, R.drawable.divider_node_hairline)?.let { d ->
            sheet.proxySheetNodesList.addItemDecoration(Divider(d))
        }

        groups = initial
        // Open on the group carrying the node the TV shows, like Home opens on its primary group.
        val focus = initial.firstOrNull { it.now.isNotEmpty() && it.now == focusNode }
            ?: initial.firstOrNull { it.selectable }
            ?: initial.firstOrNull()
        focus?.let { expanded += it.name }

        sheet.proxySheetSearch.addTextChangedListener { editable ->
            query = editable?.toString().orEmpty()
            sheet.proxySheetSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            render()
        }
        sheet.proxySheetSearchClear.setOnClickListener { sheet.proxySheetSearch.setText("") }
        sheet.proxySheetPingButton.setOnClickListener {
            val target = expanded.firstOrNull { name -> groups.any { it.name == name } }
                ?: groups.firstOrNull()?.name
                ?: return@setOnClickListener
            startGroupPing(target)
        }

        render()
        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        SheetDragToDismiss.attach(dialog, sheet.proxySheetNodesList)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = (context.resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION).toInt()
            }
        }
    }

    fun dismiss() = dialog.dismiss()

    /** Fresh groups from the TV (refetch after a `proxies` change or a finished ping). */
    fun update(fresh: List<RemoteProxyGroup>) {
        groups = fresh
        expanded.retainAll(fresh.map { it.name }.toSet())
        render()
    }

    /** A group ping finished: its header stops spinning and the capsules show the fresh delays. */
    fun groupPingFinished(group: String) {
        pingingGroups.remove(group)
        render()
    }

    /** A single-node ping finished: its capsule shows the fresh delay instead of "…". */
    fun nodePingFinished(group: String, node: String) {
        pendingNodes.remove(group to node)
        render()
    }

    fun setLive(live: Boolean) {
        sheet.proxySheetSubStatus.text = context.getString(
            if (live) R.string.remote_link_live else R.string.remote_link_reconnecting,
        )
        val tint = MaterialColors.getColor(
            sheet.root,
            if (live) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        sheet.proxySheetSubStatus.setTextColor(tint)
        sheet.proxySheetSubStatusDot.backgroundTintList = ColorStateList.valueOf(tint)
    }

    private fun startGroupPing(group: String) {
        if (!canPing || group in pingingGroups) return
        pingingGroups += group
        lastPingAt = System.currentTimeMillis()
        render()
        onPingGroup(group)
    }

    private fun items(): List<Item> {
        val q = query.trim()
        return buildList {
            for (group in groups) {
                val nodes = group.nodes.filter { n ->
                    q.isEmpty() || n.name.contains(q, ignoreCase = true) || group.name.contains(q, ignoreCase = true)
                }
                if (q.isNotEmpty() && nodes.isEmpty()) continue
                val open = q.isNotEmpty() || group.name in expanded
                add(Item.Header(group.name, group.now, group.nodes.size, open, group.name in pingingGroups))
                if (!open) continue
                nodes.forEach { n ->
                    add(
                        Item.Node(
                            group = group.name,
                            name = n.name,
                            type = typeOf(n.type),
                            delayMs = if (n.delay > 0) n.delay else -1,
                            selected = n.name.isNotEmpty() && n.name == group.now,
                            selectable = canSelect && group.selectable,
                            pending = (group.name to n.name) in pendingNodes,
                        ),
                    )
                }
            }
        }
    }

    private fun render() {
        val list = items()
        nodeAdapter.submitList(list)
        if (list.isEmpty()) {
            sheet.proxySheetEmpty.text = context.getString(
                if (groups.isEmpty()) R.string.remote_nodes_empty else R.string.profile_proxy_empty_filtered,
            )
            sheet.proxySheetEmpty.visibility = View.VISIBLE
        } else {
            sheet.proxySheetEmpty.visibility = View.GONE
        }
        val pinging = pingingGroups.isNotEmpty()
        sheet.proxySheetPingProgress.visibility = if (pinging) View.VISIBLE else View.GONE
        sheet.proxySheetPingButton.visibility = if (pinging) View.INVISIBLE else View.VISIBLE
        bindTested()
        if (!scrolledOnce) {
            val index = list.indexOfFirst { it is Item.Header && it.expanded }
            if (index >= 0) {
                scrolledOnce = true
                sheet.proxySheetNodesList.post {
                    (sheet.proxySheetNodesList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
                }
            }
        }
    }

    private fun bindTested() {
        val at = lastPingAt
        if (at == null) {
            sheet.proxySheetTestedDot.visibility = View.GONE
            sheet.proxySheetTestedText.text = context.getString(R.string.proxy_sheet_tested_never)
            return
        }
        sheet.proxySheetTestedDot.visibility = View.VISIBLE
        val elapsedSec = ((System.currentTimeMillis() - at) / 1000L).coerceAtLeast(0L)
        sheet.proxySheetTestedText.text = when {
            elapsedSec < 3 -> context.getString(R.string.proxy_sheet_tested_now)
            elapsedSec < 60 -> context.getString(R.string.proxy_sheet_tested_seconds_ago, elapsedSec.toInt())
            else -> context.getString(R.string.proxy_sheet_tested_minutes_ago, (elapsedSec / 60).toInt())
        }
    }

    private fun toggle(name: String) {
        if (!expanded.remove(name)) expanded += name
        render()
    }

    private fun bindHeader(view: View, item: Item.Header, interactive: Boolean) {
        view.findViewById<TextView>(R.id.group_block_name).text = displayGroupName(item.name)
        view.findViewById<TextView>(R.id.group_block_summary).apply {
            text = item.now
            visibility = if (item.now.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.group_block_count).text = item.count.toString()
        view.findViewById<View>(R.id.group_block_chevron).rotation = if (item.expanded) 0f else -90f
        view.findViewById<View>(R.id.group_block_ping_progress).visibility =
            if (item.pinging) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.group_block_ping).apply {
            visibility = when {
                !canPing -> View.GONE
                item.pinging -> View.INVISIBLE
                else -> View.VISIBLE
            }
            if (interactive) setOnClickListener { startGroupPing(item.name) }
        }
        if (interactive) view.findViewById<View>(R.id.group_block_header).setOnClickListener { toggle(item.name) }
    }

    private fun bindNode(row: View, item: Item.Node) {
        bindNodeRowAppearance(
            row = row,
            title = item.name,
            type = item.type,
            subtitle = "",
            groupName = item.group,
            showGroupInSubtitle = false,
        ) { null }
        val capsule = row.findViewById<View>(R.id.latency_capsule)
        val dot = row.findViewById<View>(R.id.latency_dot)
        val delayView = row.findViewById<TextView>(R.id.proxy_delay)
        applyDelayStyle(capsule, dot, delayView, item.delayMs, row.context)
        delayView.text = if (item.pending) PENDING_TEXT else formatDelay(item.delayMs)
        if (canPing) {
            capsule.setOnClickListener {
                val key = item.group to item.name
                if (!pendingNodes.add(key)) return@setOnClickListener
                delayView.text = PENDING_TEXT
                onPingNode(item.group, item.name)
            }
        } else {
            capsule.setOnClickListener(null)
            capsule.isClickable = false
        }
        row.isSelected = item.selected
        row.findViewById<View>(R.id.selected_bar).visibility = if (item.selected) View.VISIBLE else View.INVISIBLE
        row.findViewById<View>(R.id.selected_check).showSelectedCheck(item.selected)
        val hit = row.findViewById<View>(R.id.proxy_row_main_hit)
        hit.setOnLongClickListener(null)
        if (item.selectable) {
            hit.setOnClickListener {
                if (item.selected) return@setOnClickListener
                // Optimistic: move the check now; the TV's answer (and its `proxies` event) confirms.
                groups = groups.map { g -> if (g.name == item.group) g.copy(now = item.name) else g }
                render()
                onSelect(item.group, item.name)
            }
        } else {
            hit.setOnClickListener(null)
            hit.isClickable = false
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    private inner class NodeAdapter : ListAdapter<Item, Holder>(diff) {
        override fun getItemViewType(position: Int): Int = if (getItem(position) is Item.Header) VIEW_HEADER else VIEW_NODE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = if (viewType == VIEW_HEADER) R.layout.item_proxy_group_header else R.layout.adapter_home_proxy_node
            return Holder(parent.context.layoutInflater.inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val item = getItem(position)) {
                is Item.Header -> bindHeader(holder.itemView, item, interactive = true)
                is Item.Node -> bindNode(holder.itemView, item)
            }
        }
    }

    private class Divider(private val divider: Drawable) : RecyclerView.ItemDecoration() {
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter ?: return
            for (i in 0 until parent.childCount - 1) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                val next = parent.getChildAdapterPosition(parent.getChildAt(i + 1))
                if (position == RecyclerView.NO_POSITION || next != position + 1) continue
                if (adapter.getItemViewType(position) != VIEW_NODE || adapter.getItemViewType(next) != VIEW_NODE) continue
                val top = child.bottom + child.translationY.toInt()
                divider.setBounds(parent.paddingLeft, top, parent.width - parent.paddingRight, top + divider.intrinsicHeight)
                divider.draw(c)
            }
        }
    }

    /** Pins the expanded group's header while its nodes scroll by; a tap on it collapses the group. */
    private inner class StickyHeader : RecyclerView.ItemDecoration(), RecyclerView.OnItemTouchListener {
        private var headerView: View? = null
        private var pinned: Item.Header? = null
        private var pinnedBottom = 0
        private var downX = 0f
        private var downY = 0f
        private var downOnPinned = false

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            pinned = null
            val first = parent.getChildAt(0) ?: return
            val position = parent.getChildAdapterPosition(first)
            if (position == RecyclerView.NO_POSITION) return
            val list = nodeAdapter.currentList
            val header = (position downTo 0).firstNotNullOfOrNull { list.getOrNull(it) as? Item.Header } ?: return
            if (!header.expanded) return
            if (list[position] is Item.Header && first.top >= 0) return

            val view = headerView ?: parent.context.layoutInflater
                .inflate(R.layout.item_proxy_group_header, parent, false)
                .also { headerView = it }
            bindHeader(view, header, interactive = false)
            val width = parent.width - parent.paddingLeft - parent.paddingRight
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            var top = 0
            for (i in 1 until parent.childCount) {
                val p = parent.getChildAdapterPosition(parent.getChildAt(i))
                if (p != RecyclerView.NO_POSITION && list.getOrNull(p) is Item.Header) {
                    top = minOf(0, parent.getChildAt(i).top - view.measuredHeight)
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

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    downOnPinned = pinned != null && e.y < pinnedBottom
                }
                MotionEvent.ACTION_UP -> {
                    val slop = ViewConfiguration.get(rv.context).scaledTouchSlop
                    val tap = abs(e.x - downX) < slop && abs(e.y - downY) < slop
                    val header = pinned
                    if (downOnPinned && tap && header != null && e.y < pinnedBottom) {
                        downOnPinned = false
                        toggle(header.name)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    private companion object {
        const val VIEW_NODE = 0
        const val VIEW_HEADER = 1
        const val PENDING_TEXT = "…"
        const val SHEET_HEIGHT_FRACTION = 0.88f

        val diff = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = when {
                oldItem is Item.Header && newItem is Item.Header -> oldItem.name == newItem.name
                oldItem is Item.Node && newItem is Item.Node -> oldItem.group == newItem.group && oldItem.name == newItem.name
                else -> false
            }

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem == newItem
        }

        /** The agent passes core type names through (§8.7); unknown ones render without a chip. */
        fun typeOf(name: String): Proxy.Type =
            Proxy.Type.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Proxy.Type.Unknown
    }
}
