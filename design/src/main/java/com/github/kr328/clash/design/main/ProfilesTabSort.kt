package com.github.kr328.clash.design.main

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.model.Profile

internal fun sortProfilesForTab(profiles: List<Profile>, uiStore: UiStore): List<Profile> =
    com.github.kr328.clash.design.util.ProfileOrdering.sortForDisplay(
        profiles = profiles,
        mode = uiStore.profileSortMode,
        active = Profile::active,
        name = Profile::name,
        updatedAt = Profile::updatedAt,
    )

private fun tabSortModeTitle(context: Context, mode: com.github.kr328.clash.design.model.ProfileSortMode): String =
    context.getString(
        when (mode) {
            com.github.kr328.clash.design.model.ProfileSortMode.Manual -> R.string.profiles_sort_manual
            com.github.kr328.clash.design.model.ProfileSortMode.ActiveFirst -> R.string.profiles_sort_active_first
            com.github.kr328.clash.design.model.ProfileSortMode.Name -> R.string.profiles_sort_name
            com.github.kr328.clash.design.model.ProfileSortMode.LastUpdated -> R.string.profiles_sort_last_updated
        },
    )

internal fun showProfilesTabSortMenu(
    context: Context,
    anchor: View,
    uiStore: UiStore,
    adapter: ProfileAdapter,
    allProfiles: () -> List<Profile>,
    onUpdateAll: () -> Unit,
) {
    val popup = android.widget.PopupMenu(context, anchor)
    popup.menu.add(0, 1000, 0, R.string.subscriptions_update_all).isEnabled =
        !adapter.states.allUpdating &&
            allProfiles().any { it.imported && it.type != Profile.Type.File }
    val modes = com.github.kr328.clash.design.model.ProfileSortMode.values()
    modes.forEachIndexed { i, m -> popup.menu.add(1, i, i + 1, tabSortModeTitle(context, m)).isCheckable = true }
    val current = uiStore.profileSortMode.ordinal
    popup.menu.findItem(current).isChecked = true
    popup.setOnMenuItemClickListener { item ->
        if (item.itemId == 1000) {
            onUpdateAll()
            return@setOnMenuItemClickListener true
        }
        uiStore.profileSortMode = modes.getOrElse(item.itemId) {
            com.github.kr328.clash.design.model.ProfileSortMode.Manual
        }
        adapter.profiles = sortProfilesForTab(allProfiles(), uiStore)
        adapter.notifyDataSetChanged()
        true
    }
    popup.show()
}

/** Long-press drag reorder of the Profiles tab list; the order is committed when the drag ends. */
internal fun profilesTabTouchHelper(
    uiStore: UiStore,
    adapter: ProfileAdapter,
    onReordered: (List<Profile>) -> Unit,
): androidx.recyclerview.widget.ItemTouchHelper =
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
            ): Boolean = adapter.moveProfile(vh.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                onReordered(adapter.profiles.toList())
            }
        },
    )
