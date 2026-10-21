package com.github.kr328.clash.design.main

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.MainDesign.MainTab
import com.github.kr328.clash.design.MainDesign.Request
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.branding.BrandHolder
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.model.AppLanguage
import com.github.kr328.clash.design.motion.transformExpressivePage
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.R as MaterialR

/**
 * The Home / Profiles / Routing / Operator / Settings pager and its bottom nav. The page set follows
 * the active brand; the pager is circular (a boundary placeholder on each side), so a logical tab
 * and a pager position differ by one.
 */
internal class MainPager(
    private val binding: DesignMainBinding,
    private val uiStore: UiStore,
    private val sendRequest: (Request) -> Unit,
    private val onTabRendered: () -> Unit,
    private val onNavigationHeightChanged: (Int) -> Unit,
) {
    /**
     * The set of tabs currently visible in bottom nav + ViewPager, in display order.
     * Default mode is 4 tabs (no Operator). Operator appears between Routing and
     * Settings when the operator brand is active; when the brand additionally sets
     * Hide-Routing=true, Operator replaces Routing in place (still 4 tabs).
     */
    private var activeTabs: List<MainTab> = DEFAULT_TABS

    /** Held so [rebuildMainPagerForTabs] can detach/reattach without piling up. */
    private var pagerPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var pagerInitialised: Boolean = false
    private var renderedTab: MainTab? = null

    /** One-time setup. Wires the OnPageChangeCallback exactly once. */
    fun setup() {
        if (pagerInitialised) return
        pagerInitialised = true
        binding.mainBottomNavCard.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.height > 0) onNavigationHeightChanged(view.height)
        }

        attachPagerAdapter()
        binding.mainPager.setPageTransformer(::transformExpressivePage)

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
    fun reconcileTabs(brandHolder: BrandHolder) {
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

    fun selectMainTab(tab: MainTab) {
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
        if (tab == MainTab.Routing) sendRequest(Request.RefreshRouting)
        val animate = renderedTab != null && renderedTab != tab
        binding.mainBottomNavItems.selectItem(navForMainTab(tab), animate)
        MainTab.values().forEach {
            navForMainTab(it).isSelected = it == tab
            // The ViewPager keeps off-screen tab pages attached + VISIBLE (offscreenPageLimit =
            // all), so without this their content stays focusable and the D-pad escapes into the
            // hidden tab ("ghost" focus on TV). Only the current tab's page accepts focus.
            (pageForMainTab(it) as? ViewGroup)?.descendantFocusability =
                if (it == tab) ViewGroup.FOCUS_AFTER_DESCENDANTS
                else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        renderedTab = tab
        onTabRendered()
        sendRequest(Request.MainTabChanged)
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

    private companion object {
        private val DEFAULT_TABS = listOf(MainTab.Home, MainTab.Profiles, MainTab.Routing, MainTab.Settings)
    }
}

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
