package com.github.kr328.clash.main

import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.SubscriptionFetchException
import java.io.IOException
import com.github.kr328.clash.common.util.RemoteServiceUnavailableException

internal suspend fun MainDesign.showSubscriptionUpdateFailure(profile: Profile, error: Throwable) {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val code = causes.firstNotNullOfOrNull { SubscriptionFetchException.codeOf(it.message) }
    val reason = when (code) {
        "E-41" -> R.string.subscription_update_expired
        "E-40" -> R.string.subscription_update_device_limit
        "E-20" -> R.string.subscription_update_network_error
        "E-21" -> R.string.subscription_update_refused
        "E-30" -> R.string.subscription_update_key_error
        "E-10", "E-11" -> R.string.subscription_update_invalid_response
        else -> when {
            causes.any { it is RemoteServiceUnavailableException } -> R.string.remote_service_unavailable
            causes.any { it is IOException && it !is java.io.FileNotFoundException } -> R.string.subscription_update_network_error
            else -> R.string.subscription_update_error
        }
    }
    showToast(context.getString(R.string.toast_profile_updated_failed, profile.name, context.getString(reason)), ToastDuration.Long) {
        duration = 8000
        setAction(R.string.subscription_retry) { requestSubscriptionUpdate(profile) }
    }
}
