package com.github.kr328.clash.design

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.adapter.AppAdapter
import com.github.kr328.clash.design.adapter.dp
import com.github.kr328.clash.design.component.AccessControlMenu
import com.github.kr328.clash.design.databinding.DesignAccessControlBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.AccessControlMode
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.*

class AccessControlDesign(
    context: Context,
    uiStore: UiStore,
    private val selected: MutableSet<String>,
) : Design<AccessControlDesign.Request>(context) {
    private enum class NamespaceFilter {
        All,
        Ru,
        Cn,
        Com,
        Other,
    }

    enum class Request {
        ReloadApps,
        SelectAll,
        SelectNone,
        SelectInvert,
        Import,
        Export,
        ChangeMode,
        ApplyPreset,
    }

    var pendingMode: AccessControlMode? = null
        private set

    /** Latest live filter text from the inline search box. */
    var inlineFilter: String = ""
        private set

    private val binding = DesignAccessControlBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = AppAdapter(context, selected)

    private val menu: AccessControlMenu by lazy {
        AccessControlMenu(context, binding.menuView, uiStore, requests)
    }

    val apps: List<AppInfo>
        get() = adapter.apps

    /** All apps currently loaded by the host (pre-filter). Used for accurate counters. */
    private var allApps: List<AppInfo> = emptyList()

    private var namespaceFilter: NamespaceFilter = NamespaceFilter.All

    override val root: View
        get() = binding.root

    private val tv = context.isTelevision()

    suspend fun patchApps(apps: List<AppInfo>) {
        allApps = apps
        appsLoaded = true
        val filtered = applyFilterLocked(apps, inlineFilter)
        adapter.swapDataSet(adapter::apps, filtered, false)
        refreshCounter()
    }

    private var appsLoaded = false

    suspend fun rebindAll() {
        withContext(Dispatchers.Main) {
            adapter.rebindAll()
            refreshCounter()
        }
    }

    fun setMode(mode: AccessControlMode) {
        applyModeButtonsState(mode)
    }

    private fun refreshCounter() {
        val total = allApps.size
        val sel = allApps.count { it.packageName in selected }
        binding.counterView.text = context.getString(R.string.app_routing_counter, sel, total)
        // TV: a filter (search, namespace, hidden system apps) that leaves no app showed a bare
        // canvas under the bar; say so instead.
        if (tv) {
            binding.emptyView.visibility =
                if (appsLoaded && adapter.apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun matchesNamespace(app: AppInfo): Boolean = when (namespaceFilter) {
        NamespaceFilter.All -> true
        NamespaceFilter.Ru -> app.packageName.startsWith("ru.")
        NamespaceFilter.Cn -> app.packageName.startsWith("cn.")
        NamespaceFilter.Com -> app.packageName.startsWith("com.")
        NamespaceFilter.Other ->
            !app.packageName.startsWith("ru.") &&
                !app.packageName.startsWith("cn.") &&
                !app.packageName.startsWith("com.")
    }

    private fun applyFilterLocked(source: List<AppInfo>, keyword: String): List<AppInfo> =
        source.filter { app ->
            matchesNamespace(app) &&
                (keyword.isBlank() ||
                    app.label.contains(keyword, ignoreCase = true) ||
                    app.packageName.contains(keyword, ignoreCase = true))
        }

    private fun launchApplyFilteredList() {
        val filtered = applyFilterLocked(allApps, inlineFilter)
        launch {
            adapter.patchDataSet(adapter::apps, filtered, false, AppInfo::packageName)
            refreshCounter()
        }
    }

    /**
     * TV: the bar was a square window-coloured slab cut to the capped column, floating over the
     * page canvas. It becomes a rounded card inset like the Rules hub's floating save bar, with
     * room above it so the overflow button's focus ring stays clear of the top edge (the list
     * starts below it, [placeTvList]).
     */
    private fun applyTvActivityBar() {
        val bar = binding.activityBarLayout
        val margin = context.dp(16)
        bar.background = GradientDrawable().apply {
            cornerRadius = context.dp(24).toFloat()
            setColor(MaterialColors.getColor(bar, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(margin, context.dp(12), margin, 0)
            bar.layoutParams = it
        }
        // The bar's own top padding is bound to the window insets; pad its content instead.
        (bar.getChildAt(0) as? ViewGroup)?.let {
            it.setPaddingRelative(it.paddingStart, context.dp(8), it.paddingEnd, context.dp(12))
        }
    }

    /**
     * TV: the list starts under the bar card instead of scrolling beneath it (rows showed through
     * the margins around the card), takes the card's width, and keeps the overscan-safe room
     * under the focused row at the bottom.
     */
    private fun placeTvList(barBottom: Int) {
        val recycler = binding.mainList.recyclerList
        val lp = recycler.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val top = barBottom + context.dp(8)
        val side = context.dp(16)
        if (lp.topMargin != top || lp.marginStart != side || lp.marginEnd != side) {
            lp.topMargin = top
            lp.marginStart = side
            lp.marginEnd = side
            recycler.layoutParams = lp
        }
        val bottom = context.resources.getDimensionPixelSize(R.dimen.tv_focus_edge_margin)
        if (recycler.paddingTop != 0 || recycler.paddingBottom != bottom) {
            recycler.updatePadding(top = 0, bottom = bottom)
        }
    }

    private fun applyModeButtonsState(mode: AccessControlMode) {
        val id = when (mode) {
            AccessControlMode.AcceptAll -> R.id.mode_all_button
            AccessControlMode.AcceptSelected -> R.id.mode_allow_button
            AccessControlMode.DenySelected -> R.id.mode_deny_button
        }
        if (binding.modeGroup.checkedButtonId != id) {
            binding.modeGroup.check(id)
        }
    }

    fun requestModeAcceptAll() = requestModeChange(AccessControlMode.AcceptAll)
    fun requestModeAcceptSelected() = requestModeChange(AccessControlMode.AcceptSelected)
    fun requestModeDenySelected() = requestModeChange(AccessControlMode.DenySelected)

    private fun requestModeChange(mode: AccessControlMode) {
        pendingMode = mode
        requests.trySend(Request.ChangeMode)
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)
        binding.screenTitle.text = (context as? android.app.Activity)?.title?.toString().orEmpty()

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }

        // Расширенный bar (toolbar + поиск + переключатель режима) выше дефолтного
        // toolbar_height из common_recycler_list, поэтому верхние карточки списка
        // оказываются под баром. Подгоняем верхний padding RecyclerView под реальную
        // высоту бара после каждой раскладки.
        if (tv) applyTvActivityBar()
        binding.activityBarLayout.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val recycler = binding.mainList.recyclerList
            if (tv) {
                placeTvList(v.bottom)
                return@addOnLayoutChangeListener
            }
            if (recycler.paddingTop != v.height) {
                recycler.updatePadding(top = v.height)
            }
        }

        binding.menuView.setOnClickListener {
            menu.show()
        }

        binding.searchInput.addTextChangedListener {
            inlineFilter = it?.toString()?.trim().orEmpty()
            launchApplyFilteredList()
        }

        val nsModes = NamespaceFilter.values().toList()
        val nsLabels = listOf(
            context.getString(R.string.filter),
            context.getString(R.string.app_routing_filter_ru),
            context.getString(R.string.app_routing_filter_cn),
            context.getString(R.string.app_routing_filter_com),
            context.getString(R.string.app_routing_filter_other),
        )
        binding.namespaceFilterDropdown.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, nsLabels),
        )
        binding.namespaceFilterDropdown.setText(nsLabels[nsModes.indexOf(namespaceFilter)], false)
        binding.namespaceFilterDropdown.setOnItemClickListener { _, _, position, _ ->
            namespaceFilter = nsModes.getOrElse(position) { NamespaceFilter.All }
            launchApplyFilteredList()
        }

        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.mode_all_button -> AccessControlMode.AcceptAll
                R.id.mode_allow_button -> AccessControlMode.AcceptSelected
                R.id.mode_deny_button -> AccessControlMode.DenySelected
                else -> return@addOnButtonCheckedListener
            }
            if (pendingMode == mode) return@addOnButtonCheckedListener
            pendingMode = mode
            requests.trySend(Request.ChangeMode)
        }
    }
}