package com.github.kr328.clash.design

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import com.github.kr328.clash.design.adapter.dp

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
    // Centre the two columns in the space between the top bar and the bottom nav.
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
    // DOWN from the orb goes to the bottom nav (the default search slid into the card's last row).
    // (The page's NestedScrollView resolves DOWN inside itself, so nextFocusDownId to a view outside
    // it is ignored; the key is taken here before the scroll view sees it.)
    binding.mainPowerCard.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            binding.mainNavHome.requestFocus()
        } else {
            false
        }
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
 * The tab pages scroll under the floating bottom nav (their bottom padding only reserves room at
 * the end of the content), so a D-pad move could park the focused row behind the nav. On TV the
 * pages keep the focused control above the nav instead.
 */
internal fun MainDesign.applyTvPageFocusMargins() {
    val navSpace = context.resources.getDimensionPixelSize(R.dimen.main_bottom_nav_reserved_space)
    listOf(binding.mainRoutingPage, binding.mainOperatorPage, binding.mainSettingsPage)
        .forEach { it.bottomFocusInset = navSpace + context.dp(12) }
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
