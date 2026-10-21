package com.github.kr328.clash.design.adapter

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.layoutInflater

/** A real header view keeps its collapse/ping controls and accessibility while pinned. */
internal class StickyProxyGroupHeader(private val list: RecyclerView, private val adapter: ProxyNodeAdapter) {
    private val host = list.parent as FrameLayout
    private val header = list.context.layoutInflater.inflate(R.layout.item_proxy_group_header, host, false)
    private var displayed: PickerItem.Header? = null
    private val refresh = Runnable { update() }
    private val scroll = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = update()
    }
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = schedule()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = schedule()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = schedule()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = schedule()
    }

    init {
        header.visibility = View.GONE
        header.elevation = 4 * list.resources.displayMetrics.density
        host.addView(header, FrameLayout.LayoutParams(1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedule() }
        list.addOnScrollListener(scroll)
        adapter.registerAdapterDataObserver(observer)
    }

    private fun schedule() {
        list.removeCallbacks(refresh)
        list.postOnAnimation(refresh)
    }

    private fun update() {
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        val position = manager.findFirstVisibleItemPosition()
        val items = adapter.currentList
        var active: PickerItem.Header? = null
        for (i in minOf(position, items.lastIndex) downTo 0) {
            val candidate = items[i]
            if (candidate is PickerItem.Header) { active = candidate; break }
        }
        val first = manager.findViewByPosition(position)
        val pinned = active
        if (pinned == null || !pinned.expanded || first == null ||
            (items.getOrNull(position) is PickerItem.Header && first.top >= list.paddingTop)
        ) {
            header.visibility = View.GONE
            displayed = null
            return
        }
        if (pinned != displayed) {
            bindPickerGroupHeader(header, pinned, adapter.onHeaderToggle, adapter.onHeaderPing)
            displayed = pinned
        }
        val width = list.width - list.paddingLeft - list.paddingRight
        val params = header.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.leftMargin != list.left + list.paddingLeft ||
            params.topMargin != list.top + list.paddingTop
        ) {
            params.width = width
            params.leftMargin = list.left + list.paddingLeft
            params.topMargin = list.top + list.paddingTop
            header.layoutParams = params
        }
        var offset = 0
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val childPosition = list.getChildAdapterPosition(child)
            if (childPosition > position && items.getOrNull(childPosition) is PickerItem.Header) {
                offset = minOf(0, child.top - list.paddingTop - header.height)
                break
            }
        }
        header.translationY = offset.toFloat()
        header.visibility = View.VISIBLE
    }

    fun dispose() {
        list.removeCallbacks(refresh)
        list.removeOnScrollListener(scroll)
        adapter.unregisterAdapterDataObserver(observer)
        host.removeView(header)
    }
}
