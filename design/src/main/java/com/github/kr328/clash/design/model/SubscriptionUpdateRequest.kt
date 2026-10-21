package com.github.kr328.clash.design.model

import java.util.UUID

enum class SubscriptionUpdateRequestResult { Started, AlreadyRunning, Unsupported, Unavailable }

fun beginSubscriptionUpdateRequest(
    state: ProfilePageState,
    uuid: UUID,
    eligible: Boolean,
    dispatch: () -> Boolean,
): SubscriptionUpdateRequestResult {
    if (!eligible) return SubscriptionUpdateRequestResult.Unsupported
    if (!state.beginUpdate(uuid)) return SubscriptionUpdateRequestResult.AlreadyRunning
    if (dispatch()) return SubscriptionUpdateRequestResult.Started
    state.finishUpdate(uuid, false)
    return SubscriptionUpdateRequestResult.Unavailable
}
