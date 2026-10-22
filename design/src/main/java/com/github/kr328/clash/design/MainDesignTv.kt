package com.github.kr328.clash.design

import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import com.github.kr328.clash.design.MainDesign.MainTab
import com.github.kr328.clash.design.adapter.dp
import java.lang.ref.WeakReference

/*
 * TV-only arrangement of the Home page for [MainDesign]. A 1080p TV is ~960x540dp, so the phone
 * column (power orb above the card) pushed the Profile and Node rows under the bottom nav. On TV
 * the orb and the card sit side by side instead, and everything fits without scrolling. Phones
 * never call this.
 */

internal fun MainDesign.applyTvHomeLayout() {
    val content = binding.mainHomeActiveContent
    if (content.childCount < 2) return
    val power = binding.powerStatusGroup
    val card = content.getChildAt(1).takeIf { it !== power } ?: return

    // The announcement card lives in the same column on the phone; on TV it stays a full-width
    // strip under the two columns, so it moves up one level, right after them.
    val announcement = binding.mainAnnouncementCard
    val outer = content.parent as? ViewGroup
    if (announcement.parent === content && outer != null) {
        content.removeView(announcement)
        outer.addView(announcement, outer.indexOfChild(content) + 1)
    }

    content.orientation = LinearLayout.HORIZONTAL
    content.gravity = Gravity.CENTER_VERTICAL
    // Centre the two columns in the space below the top bar.
    binding.mainHomePage.isFillViewport = true
    (content.layoutParams as? LinearLayout.LayoutParams)?.let {
        content.layoutParams = LinearLayout.LayoutParams(it.width, 0, 1f)
    }

    power.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f).apply {
        gravity = Gravity.CENTER_VERTICAL
    }
    power.gravity = Gravity.CENTER

    (card.layoutParams as? LinearLayout.LayoutParams)?.let { old ->
        card.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = context.dp(8)
            marginEnd = old.marginEnd
            topMargin = 0
        }
    }

    // D-pad: LEFT from any card row (or the Rule half of the mode toggle) goes back to the orb.
    listOf(
        binding.mainCompanionRow,
        binding.mainRoutingRow,
        binding.mainActiveProfileRow,
        binding.mainActiveNodeRow,
        binding.mainModeRule,
    ).forEach { it.nextFocusLeftId = binding.mainPowerCard.id }
    // DOWN from the orb and from the card's last row: the announcement strip under the columns when
    // there is one, otherwise stay put (the default search slid from the orb into the card's last
    // row, and nothing else sits below on TV now that the nav is a rail on the left).
    // (The page's NestedScrollView resolves DOWN inside itself, so nextFocusDownId to a view outside
    // it is ignored; the key is taken here before the scroll view sees it.)
    // UP from the orb goes to the mode toggle (the nearest-by-geometry search picked the card's
    // top row instead).
    binding.mainPowerCard.setOnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> focusAnnouncementOrStay()
            KeyEvent.KEYCODE_DPAD_UP -> binding.mainModeRule.isShown && binding.mainModeRule.requestFocus()
            else -> false
        }
    }
    binding.mainActiveNodeRow.setOnKeyListener { _, keyCode, event ->
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN &&
            focusAnnouncementOrStay()
    }
    // RIGHT from the card's right edge stays put instead of jumping up to the "+" button. The
    // profile row's own action buttons sit inside it, so they are linked explicitly both ways.
    listOf(binding.mainCompanionRow, binding.mainRoutingRow, binding.mainActiveNodeRow, binding.mainModeGlobal)
        .forEach { it.nextFocusRightId = it.id }
    binding.mainActiveProfileRow.nextFocusRightId = binding.mainActiveProfileUpdate.id
    binding.mainActiveProfileUpdate.nextFocusLeftId = binding.mainActiveProfileRow.id
    binding.mainActiveProfileSupport.nextFocusLeftId = binding.mainActiveProfileUpdate.id
    binding.mainActiveProfileSupport.nextFocusRightId = binding.mainActiveProfileSupport.id
    // The support button comes and goes with the brand; RIGHT from Update follows it.
    binding.mainActiveProfileActions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val support = binding.mainActiveProfileSupport
        binding.mainActiveProfileUpdate.nextFocusRightId =
            if (support.visibility == View.VISIBLE) support.id else binding.mainActiveProfileUpdate.id
    }
}

/**
 * Profiles header on TV: "Add subscription" and "Update" split the row evenly, so each sits over
 * one column of the two-up card grid (the phone's 1.4 : 1 left a wide pill beside a short one).
 */
internal fun MainDesign.applyTvProfilesHeader() {
    listOf(binding.profilesTabAdd, binding.profilesTabUpdate).forEach { button ->
        (button.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = 1f
            button.layoutParams = it
        }
    }
}

/**
 * Nothing sits below the tab pages on TV (the nav is a rail on the left), so DOWN on a page's last
 * row stays put instead of the default search sliding sideways into the rail. The focused row
 * still keeps the page's bottom padding clear of the screen edge (NestedScrollView alone parked
 * the last row flush against it, cut off by the overscan).
 */
internal fun MainDesign.applyTvPageFocusMargins() {
    val bottomSpace = context.resources.getDimensionPixelSize(R.dimen.main_bottom_nav_reserved_space)
    listOf(binding.mainRoutingPage, binding.mainOperatorPage, binding.mainSettingsPage).forEach { page ->
        page.bottomFocusInset = bottomSpace
        page.focusDownExit = { page.findFocus() }
    }
}

private fun MainDesign.focusAnnouncementOrStay(): Boolean {
    val announcement = binding.mainAnnouncementCard
    if (announcement.isShown) announcement.requestFocus()
    return true
}

/**
 * TV: the bottom nav becomes a compact vertical rail on the left, vertically centred, and the tab
 * pages move right of it. A 1080p TV is only ~540dp tall, so a bar along the bottom cost the pages
 * a fifth of the screen and every page had to keep its focused row clear of it. The items, their
 * IDs and click wiring stay the same; only their arrangement and D-pad links change
 * ([wireRailFocus]). Phones never call this.
 */
internal fun MainDesign.applyTvNavigationRail() {
    val res = context.resources
    val railWidth = res.getDimensionPixelSize(R.dimen.tv_nav_rail_width)
    val card = binding.mainBottomNavCard
    card.layoutParams = CoordinatorLayout.LayoutParams(railWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        // The bottomNavInset adapter keeps its bottom margin; the same on top keeps the rail centred.
        topMargin = res.getDimensionPixelSize(R.dimen.main_bottom_nav_bottom_margin)
        bottomMargin = topMargin
    }
    val column = card.getChildAt(0) as? LinearLayout ?: return
    column.orientation = LinearLayout.VERTICAL
    column.layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    column.setPadding(context.dp(6), context.dp(8), context.dp(6), context.dp(8))
    val itemHeight = res.getDimensionPixelSize(R.dimen.tv_nav_rail_item_height)
    for (i in 0 until column.childCount) {
        column.getChildAt(i).layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight).apply {
                if (i > 0) topMargin = context.dp(6)
            }
    }

    (binding.mainPager.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.marginStart = railWidth + res.getDimensionPixelSize(R.dimen.tv_nav_rail_gap)
        binding.mainPager.layoutParams = it
    }
    // Each page painted its own canvas, which now starts right of the rail and left a hard seam
    // against the root's; the root's single canvas shows through instead.
    MainTab.values().forEach { pageForMainTab(it).background = null }

    binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
        val tab = currentTab ?: return@addOnGlobalFocusChangeListener
        if (newFocus != null && !isRailItem(newFocus) && isInside(newFocus, pageForMainTab(tab))) {
            tvContentFocus[tab] = WeakReference(newFocus)
        }
        updateRailFocusability(tab)
    }
}

/**
 * Rail D-pad: UP/DOWN walk the visible items (the ends stay put), LEFT stays put, RIGHT goes back
 * into the current tab's content, to the control it last had.
 */
internal fun MainDesign.wireRailFocus(visible: List<View>) {
    visible.forEachIndexed { i, view ->
        view.nextFocusUpId = visible[(i - 1).coerceAtLeast(0)].id
        view.nextFocusDownId = visible[(i + 1).coerceAtMost(visible.lastIndex)].id
        view.nextFocusLeftId = view.id
        view.nextFocusRightId = view.id
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) focusCurrentTabContent()
            true
        }
    }
}

/**
 * While the D-pad is in the content, only the current tab's rail item takes focus, so LEFT from the
 * content's left edge always lands on the tab the user is on (the nearest-by-geometry search would
 * pick whichever item happened to be level with the focused row). Inside the rail every visible
 * item is focusable again so UP/DOWN can move between them.
 */
internal fun MainDesign.updateRailFocusability(current: MainTab) {
    val focused = binding.root.findFocus()
    val inRail = focused != null && isRailItem(focused)
    activeTabs.forEach { tab ->
        val nav = navForMainTab(tab)
        if (nav !== focused) nav.isFocusable = inRail || tab == current
    }
}

private fun MainDesign.focusCurrentTabContent(): Boolean {
    val tab = currentTab ?: return false
    val page = pageForMainTab(tab)
    tvContentFocus[tab]?.get()
        ?.takeIf { it.isShown && it.isFocusable && isInside(it, page) }
        ?.let { if (it.requestFocus()) return true }
    if (tab == MainTab.Home) {
        val target = if (binding.mainHomeActiveContent.visibility == View.VISIBLE) {
            binding.mainPowerCard
        } else {
            binding.mainEmptyAddButton
        }
        if (target.requestFocus()) return true
    }
    val group = page as? ViewGroup ?: return false
    return FocusFinder.getInstance().findNextFocus(group, null, View.FOCUS_DOWN)?.requestFocus() == true
}

private fun MainDesign.isRailItem(view: View): Boolean =
    MainTab.values().any { navForMainTab(it) === view }

private fun isInside(view: View, ancestor: View): Boolean {
    var v: View? = view
    while (v != null) {
        if (v === ancestor) return true
        v = v.parent as? View
    }
    return false
}

/**
 * TV empty Home (no subscription yet): the welcome sits in the middle of the screen, with
 * "Add subscription" and "Remote control" as two large buttons side by side (the remote is how a
 * fresh TV usually gets its first subscription). Focus starts on "Add subscription"
 * (requestInitialHomeFocus).
 */
internal fun MainDesign.applyTvEmptyState() {
    binding.mainHomePage.isFillViewport = true
    (binding.profileSection.layoutParams as? LinearLayout.LayoutParams)?.let {
        binding.profileSection.layoutParams = LinearLayout.LayoutParams(it.width, 0, 1f).apply {
            topMargin = it.topMargin
        }
    }
    val state = binding.mainHomeEmptyState
    state.layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
    )

    val add = binding.mainEmptyAddButton
    val remote = binding.mainCompanionEmptyButton
    val index = state.indexOfChild(add)
    if (index < 0 || remote.parent !== state) return
    state.removeView(add)
    state.removeView(remote)
    val res = context.resources
    val width = res.getDimensionPixelSize(R.dimen.tv_empty_button_width)
    val height = res.getDimensionPixelSize(R.dimen.tv_empty_button_height)
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
    }
    row.addView(add, LinearLayout.LayoutParams(width, height))
    row.addView(remote, LinearLayout.LayoutParams(width, height).apply { marginStart = context.dp(20) })
    state.addView(
        row,
        index,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(36)
        },
    )
    listOf(add, remote).forEach {
        it.minWidth = 0
        it.cornerRadius = height / 2
        it.iconSize = context.dp(24)
        it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }
    remote.setIconResource(R.drawable.ic_baseline_tv)
    remote.nextFocusRightId = remote.id
}

/**
 * Lands the D-pad on the power orb once the Home shows its active state. The first focus request
 * at startup runs before the profiles load (the empty state is still up), so on TV it is repeated
 * here when the active content appears and nothing else holds focus yet.
 */
internal fun MainDesign.focusPowerOrbIfIdle() {
    if (binding.mainHomeActiveContent.visibility != View.VISIBLE) return
    val focused = binding.root.findFocus()
    // A container (the pager's list, the page itself) holding focus is not a place the user chose.
    val idle = focused == null || focused === binding.mainEmptyAddButton || focused is ViewGroup &&
        (focused is RecyclerView || focused === binding.mainHomePage || focused === binding.mainPager)
    if (!idle) return
    binding.mainPowerCard.post { binding.mainPowerCard.requestFocus() }
}
