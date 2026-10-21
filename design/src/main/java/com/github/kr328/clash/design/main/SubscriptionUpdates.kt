package com.github.kr328.clash.design.main

import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ProfilePageState
import com.github.kr328.clash.design.model.SubscriptionUpdateRequestResult
import com.github.kr328.clash.design.model.beginSubscriptionUpdateRequest
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import java.util.UUID

internal class SubscriptionUpdates(
    private val state: ProfilePageState,
    private val dispatch: (Profile) -> Boolean,
    private val dispatchAll: () -> Boolean,
    private val render: (UUID) -> Unit,
    private val bulkRefreshing: (Boolean) -> Unit,
    private val feedback: (Int, ToastDuration) -> Unit,
) {
    fun request(profile: Profile) {
        val result = beginSubscriptionUpdateRequest(state, profile.uuid,
            profile.imported && profile.type == Profile.Type.Url) { dispatch(profile) }
        render(profile.uuid)
        feedback(when (result) {
            SubscriptionUpdateRequestResult.Started -> R.string.subscription_updating
            SubscriptionUpdateRequestResult.AlreadyRunning -> R.string.subscription_update_already_running
            SubscriptionUpdateRequestResult.Unsupported -> R.string.subscription_update_unavailable
            SubscriptionUpdateRequestResult.Unavailable -> R.string.remote_service_unavailable
        }, ToastDuration.Short)
    }

    fun begin(uuid: UUID): Boolean {
        if (!state.beginUpdate(uuid)) return false
        render(uuid)
        return true
    }

    fun finish(uuid: UUID, success: Boolean) { state.finishUpdate(uuid, success); render(uuid) }
    fun cancel(uuid: UUID) { state.cancelUpdate(uuid); render(uuid) }

    fun observed(uuid: UUID, success: Boolean): Boolean {
        val announce = state.recordObservedUpdate(uuid, success)
        render(uuid)
        return announce
    }

    fun requestAll() {
        if (state.allUpdating) {
            feedback(R.string.subscription_update_already_running, ToastDuration.Short)
            return
        }
        state.allUpdating = true
        bulkRefreshing(true)
        if (dispatchAll()) feedback(R.string.profiles_updating, ToastDuration.Short)
        else {
            finishAll()
            feedback(R.string.remote_service_unavailable, ToastDuration.Long)
        }
    }

    fun finishAll() { state.allUpdating = false; bulkRefreshing(false) }
}
