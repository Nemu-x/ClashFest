package com.github.kr328.clash.design

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.R as MaterialR
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.MainDesign.MainTab

/*
 * Main-screen pager and bottom navigation for [MainDesign]: the circular [ViewPager2] page
 * adapter, tab <-> pager-position mapping, tab selection/rendering and D-pad focus wiring.
 */

internal class StaticPageAdapter(
    private val pages: List<View>,
    /**
     * TV: where LEFT goes once nothing in the page is left of the focused control (the nav rail).
     * The pager's RecyclerView otherwise answers it by trying to scroll to the neighbouring page,
     * whose content is blocked, and keeps focus where it is.
     */
    private val leftExit: (() -> View?)? = null,
) : RecyclerView.Adapter<StaticPageAdapter.Holder>() {
    class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    private fun isBoundaryPage(position: Int): Boolean {
        val count = pages.size
        return position == 0 || position == count + 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val exit = leftExit
        val container = object : FrameLayout(parent.context) {
            override fun focusSearch(focused: View?, direction: Int): View? {
                if (exit == null || direction != View.FOCUS_LEFT) return super.focusSearch(focused, direction)
                return android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction)
                    ?: exit()
                    ?: super.focusSearch(focused, direction)
            }
        }.apply {
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

/** One-time setup. Wires the OnPageChangeCallback exactly once. */
internal fun MainDesign.setupMainPager() {
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
private fun MainDesign.requestInitialHomeFocus() {
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
internal fun MainDesign.rebuildMainPagerForTabs(currentLogical: MainTab?) {
    // Stay on the tab the user was on (the caller reads it before [activeTabs] changes), or fall
    // back to Home if it's no longer in the set.
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

private fun MainDesign.attachPagerAdapter() {
    val pages = activeTabs.map { pageForMainTab(it) }
    // Detach every page (including ones we won't show) so the new adapter
    // can attach the ones we want without parent-collision exceptions.
    MainTab.values().forEach { tab ->
        val page = pageForMainTab(tab)
        (page.parent as? ViewGroup)?.removeView(page)
    }
    pages.forEach { it.visibility = View.VISIBLE }
    val leftExit: (() -> View?)? = if (context.isTelevision()) {
        { currentTab?.let { navForMainTab(it) } }
    } else {
        null
    }
    binding.mainPager.adapter = StaticPageAdapter(pages, leftExit)
    binding.mainPager.offscreenPageLimit = pages.lastIndex.coerceAtLeast(1)
}

/**
 * Nested home content (announcement + profiles) grows vertically; on narrow or very wide
 * aspect ratios, small diagonal motion while scrolling can be absorbed as a horizontal
 * page swipe. Raising RecyclerView touch slop reduces accidental tab changes.
 */
private fun MainDesign.tweakViewPagerHorizontalSwipeTolerance(viewPager: ViewPager2) {
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

internal fun MainDesign.mainTabForPagerPosition(position: Int): MainTab? {
    val size = activeTabs.size
    val logical = when (position) {
        0 -> size - 1
        size + 1 -> 0
        else -> position - 1
    }
    return activeTabs.getOrNull(logical)
}

internal fun MainDesign.selectMainTab(tab: MainTab) {
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

internal fun MainDesign.renderMainTab(tab: MainTab) {
    val tv = context.isTelevision()
    MainTab.values().forEach {
        navForMainTab(it).isSelected = it == tab
        // The ViewPager keeps off-screen tab pages attached + VISIBLE (offscreenPageLimit =
        // all), so without this their content stays focusable and the D-pad escapes into the
        // hidden tab ("ghost" focus on TV). Only the current tab's page accepts focus.
        val page = pageForMainTab(it)
        (page as? ViewGroup)?.descendantFocusability =
            if (it == tab) ViewGroup.FOCUS_AFTER_DESCENDANTS
            else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // The page itself too: a scroll-view page is focusable, so RIGHT from the edge of the
        // current tab landed on the neighbouring (off-screen) page and focus vanished.
        if (tv && page is androidx.core.widget.NestedScrollView) page.isFocusable = it == tab
    }
    if (tv) {
        updateRailFocusability(tab)
        binding.root.post { keepFocusOnCurrentTab(tab) }
    }
    updateBreathAnimator(clashRunningState, tunnelStartingState)
}

/**
 * A tab opened from page content (Home's "Active profile" row) left focus in the page that is now
 * hidden and blocked, or on the pager itself: no ring, and the D-pad went nowhere. Unless focus is
 * already on the nav or inside the new page, hand it to the new page's first control (or its tab).
 */
private fun MainDesign.keepFocusOnCurrentTab(tab: MainTab) {
    val root = binding.root
    if (root.isInTouchMode || !root.hasWindowFocus()) return
    val page = pageForMainTab(tab)
    val navs = MainTab.values().map { navForMainTab(it) }
    val focused = root.findFocus()
    if (focused != null && focused !== page && focused !is androidx.recyclerview.widget.RecyclerView) {
        var v: View? = focused
        while (v != null) {
            if (v === page || v in navs) return
            v = v.parent as? View
        }
    }
    // The same place RIGHT from the rail goes (the tab's last control, Home's power orb), not the
    // page's first control in reading order: on Home that was the header's "+" whenever the pager
    // was rebuilt under the focus (a brand adding or dropping the Operator tab).
    if (!focusCurrentTabContent()) navForMainTab(tab).requestFocus()
}

internal fun MainDesign.pageForMainTab(tab: MainTab) = when (tab) {
    MainTab.Home -> binding.mainHomePage
    MainTab.Profiles -> binding.mainProfilesPage
    MainTab.Routing -> binding.mainRoutingPage
    MainTab.Operator -> binding.mainOperatorPage
    MainTab.Settings -> binding.mainSettingsPage
}

internal fun MainDesign.navForMainTab(tab: MainTab): View = when (tab) {
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
private fun MainDesign.wireBottomNavFocus() {
    val visible = activeTabs.map { navForMainTab(it) }
    if (context.isTelevision()) {
        wireRailFocus(visible)
        return
    }
    visible.forEachIndexed { i, view ->
        view.nextFocusLeftId = visible[(i - 1).coerceAtLeast(0)].id
        view.nextFocusRightId = visible[(i + 1).coerceAtMost(visible.lastIndex)].id
        view.nextFocusDownId = view.id
    }
}

/** Focus [tab]'s nav item, even while the TV rail is only offering the current tab's item. */
internal fun MainDesign.focusNavItem(tab: MainTab): Boolean {
    val nav = navForMainTab(tab)
    nav.isFocusable = true
    return nav.requestFocus()
}
