package com.github.kr328.clash.design

import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import com.github.kr328.clash.design.util.isTelevision
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DefaultItemAnimator
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.adapter.dp
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.MainDesign.PingAllRequest

/*
 * Profile-list behaviour of [MainDesign]: RecyclerView setup for the Home and Profiles-tab lists,
 * Profiles-tab sorting, card expand toggles and opening the proxy picker sheet.
 */

internal fun MainDesign.createHomeProfileAdapter(): ProfileAdapter = ProfileAdapter(
    { profile -> profileActivateRequests.trySend(profile) },
    { profile, anchor -> profileMenuRequests.trySend(profile to anchor) },
    { profile -> toggleProfileExpand(profile) },
    { profile, group, proxyName ->
        patchHomeProxyRequests.trySend(Triple(profile, group, proxyName))
    },
    { profile, group, proxyNames, testUrl ->
        profilePingAllRequests.trySend(PingAllRequest(profile, group, proxyNames, testUrl))
    },
    { profile -> profileForceUpdateRequests.trySend(profile) },
    { profile, group, proxy -> profileProxyYamlRequests.trySend(Triple(profile, group, proxy)) },
    { profile, group -> profileVisibleGroupChanged.trySend(profile to group) },
    { profile, group, proxy -> proxyPingNodeRequests.trySend(Triple(profile, group, proxy)) },
    expandOnProfileClick = true,
    readProxyGroupLayout = { uiStore.proxyGroupLayout },
    writeProxyGroupLayout = { uiStore.proxyGroupLayout = it },
    onPickerOpened = { profilePickerOpened.trySend(it) },
)

internal fun MainDesign.createTabProfileAdapter(): ProfileAdapter = ProfileAdapter(
    { profile -> profileActivateRequests.trySend(profile) },
    { profile, anchor -> profileMenuRequests.trySend(profile to anchor) },
    { profile -> toggleTabProfileExpand(profile) },
    { profile, group, proxyName ->
        patchHomeProxyRequests.trySend(Triple(profile, group, proxyName))
    },
    { profile, group, proxyNames, testUrl ->
        profilePingAllRequests.trySend(PingAllRequest(profile, group, proxyNames, testUrl))
    },
    { profile ->
        if (profile.imported && profile.type != Profile.Type.File) {
            profileForceUpdateRequests.trySend(profile)
        }
    },
    { profile, group, proxy -> profileProxyYamlRequests.trySend(Triple(profile, group, proxy)) },
    { profile, group -> profileVisibleGroupChanged.trySend(profile to group) },
    { profile, group, proxy -> proxyPingNodeRequests.trySend(Triple(profile, group, proxy)) },
    expandOnProfileClick = false,
    showServerChooserInCard = true,
    showActivateButton = false,
    readProxyGroupLayout = { uiStore.proxyGroupLayout },
    writeProxyGroupLayout = { uiStore.proxyGroupLayout = it },
)

internal fun MainDesign.createTabItemTouchHelper(): androidx.recyclerview.widget.ItemTouchHelper =
    androidx.recyclerview.widget.ItemTouchHelper(
        object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled(): Boolean =
                uiStore.profileSortMode == com.github.kr328.clash.design.model.ProfileSortMode.Manual

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = tabProfileAdapter.moveProfile(vh.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                profileReorderRequests.trySend(tabProfileAdapter.profiles.toList())
            }
        },
    )

internal fun MainDesign.sortTabProfiles(profiles: List<Profile>): List<Profile> =
    com.github.kr328.clash.design.util.ProfileOrdering.sortForDisplay(
        profiles = profiles,
        mode = uiStore.profileSortMode,
        active = Profile::active,
        name = Profile::name,
        updatedAt = Profile::updatedAt,
    )

private fun MainDesign.tabSortModeTitle(mode: com.github.kr328.clash.design.model.ProfileSortMode): String =
    context.getString(
        when (mode) {
            com.github.kr328.clash.design.model.ProfileSortMode.Manual -> R.string.profiles_sort_manual
            com.github.kr328.clash.design.model.ProfileSortMode.ActiveFirst -> R.string.profiles_sort_active_first
            com.github.kr328.clash.design.model.ProfileSortMode.Name -> R.string.profiles_sort_name
            com.github.kr328.clash.design.model.ProfileSortMode.LastUpdated -> R.string.profiles_sort_last_updated
        },
    )

private fun MainDesign.showProfilesTabSort(anchor: View) {
    val popup = android.widget.PopupMenu(context, anchor)
    val modes = com.github.kr328.clash.design.model.ProfileSortMode.values()
    modes.forEachIndexed { i, m -> popup.menu.add(0, i, i, tabSortModeTitle(m)).isCheckable = true }
    val current = uiStore.profileSortMode.ordinal
    for (i in 0 until popup.menu.size()) popup.menu.getItem(i).isChecked = i == current
    popup.setOnMenuItemClickListener { item ->
        uiStore.profileSortMode = modes.getOrElse(item.itemId) {
            com.github.kr328.clash.design.model.ProfileSortMode.Manual
        }
        tabProfileAdapter.profiles = sortTabProfiles(tabProfilesAll)
        tabProfileAdapter.notifyDataSetChanged()
        true
    }
    popup.show()
}

private fun MainDesign.toggleProfileExpand(profile: Profile) {
    if (!profile.imported) {
        return
    }
    val hadGroups = profileAdapter.hasProxyGroupsFor(profile)
    val needsRefresh = expandedProfileUuids.add(profile.uuid)
    profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
    if (needsRefresh || !hadGroups) {
        profileExpandChanged.trySend(Unit)
    }
    openProxySheetWhenReady(profile)
}

/**
 * Profiles-tab card expand: toggles the inline proxy-group panel (no bottom sheet, unlike Home).
 * A reload is requested so an expanding card gets its proxy groups loaded.
 */
private fun MainDesign.toggleTabProfileExpand(profile: Profile) {
    if (!profile.imported || !profile.active) {
        return
    }
    if (!tabExpandedProfileUuids.add(profile.uuid)) {
        tabExpandedProfileUuids.remove(profile.uuid)
    }
    tabProfileAdapter.setExpandedUuids(tabExpandedProfileUuids.toSet())
    profileExpandChanged.trySend(Unit)
}

/**
 * Node-row entry to the proxy picker (redesign 1.0). Unlike a bare [openProxySheetWhenReady],
 * this first ensures the profile's proxy groups are loaded (the same load `toggleProfileExpand`
 * triggers) so the sheet isn't empty; then opens it. Does not toggle the card's expand state.
 */
internal fun MainDesign.openNodePicker(profile: Profile) {
    if (!profile.imported) return
    if (!profileAdapter.hasProxyGroupsFor(profile)) {
        expandedProfileUuids.add(profile.uuid)
        profileAdapter.setExpandedUuids(expandedProfileUuids.toSet())
        profileExpandChanged.trySend(Unit)
    }
    openProxySheetWhenReady(profile)
}

private fun MainDesign.openProxySheetWhenReady(profile: Profile, attempt: Int = 0) {
    // Was 180ms x 15 ≈ 2.7s of recursive postDelayed wakeups while waiting for
    // proxy groups to load. 400ms x 6 ≈ 2.4s but with ~2.5x fewer wakeups.
    if (profileAdapter.hasProxyGroupsFor(profile) || attempt >= 6) {
        profileAdapter.showProxySheet(context, profile)
        return
    }
    binding.profileList.postDelayed({
        openProxySheetWhenReady(profile, attempt + 1)
    }, 400L)
}

internal fun MainDesign.setupProfileLists() {
    binding.profileList.also {
        it.applyLinearAdapter(context, profileAdapter)
        (it.layoutManager as? LinearLayoutManager)?.stackFromEnd = false
        it.setHasFixedSize(true)
        // Change-animations were running on every traffic tick across all visible cards
        // (~2–8s cadence) and added measurable GPU/CPU load. Add/remove animations are kept,
        // but per-item rebind animations are disabled.
        it.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            changeDuration = 0
            moveDuration = 0
        }
    }

    binding.profilesTabList.also {
        it.applyLinearAdapter(context, tabProfileAdapter)
        // TV: the list is ~900dp wide there, so subscription cards sit two per row.
        if (context.isTelevision()) {
            it.layoutManager = GridLayoutManager(context, 2)
            // The cards' outer edges line up with "Add subscription" / "Update" above (20dp from
            // the page edge, 8dp apart): 16dp list padding + 4dp card margins on each side.
            tabProfileAdapter.cardMarginHorizontal = context.dp(4)
            it.setPaddingRelative(context.dp(16), it.paddingTop, context.dp(16), it.paddingBottom)
        }
        it.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            changeDuration = 0
            moveDuration = 0
        }
    }
    tabItemTouchHelper.attachToRecyclerView(binding.profilesTabList)
    binding.profilesTabSort.setOnClickListener { showProfilesTabSort(it) }
    binding.profilesTabUpdate.setOnClickListener { profileUpdateAllRequests.trySend(Unit) }
}
