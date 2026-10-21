package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendConnectionsChanged
import java.util.UUID

suspend fun Context.closeConnectionsAfterUserProxySwitchIfEnabled(
    showFeedback: suspend (String, ToastDuration) -> Unit,
) {
    if (ServiceStore(this).keepConnectionsOnOldProxy) return

    runCatching {
        withClash { closeAllConnections() }
    }.onSuccess { closed ->
        if (closed > 0) {
            showFeedback(
                getString(R.string.connections_closed_many, closed),
                ToastDuration.Short,
            )
        }
        sendConnectionsChanged()
    }.onFailure {
        showFeedback(
            getString(R.string.proxy_switch_close_connections_failed),
            ToastDuration.Long,
        )
    }
}

/**
 * The one way a user's node pick reaches a running engine, shared by the Home node picker and the
 * companion gateway: [com.github.kr328.clash.service.ClashManager.patchSelector] (persists the
 * selection and keeps GLOBAL in step), remember the group as the picker's last one and the choice
 * for the profile, close old connections when the user asked for that, and announce
 * [ChangeBus.Change.Proxies].
 *
 * @param remote true when a paired controller asked for it (see [ChangeBus]).
 * @return false when the engine refused the selection (nothing is persisted then).
 */
suspend fun Context.applyUserProxySelection(
    profile: UUID,
    group: String,
    name: String,
    remote: Boolean,
    showFeedback: suspend (String, ToastDuration) -> Unit,
): Boolean {
    val patched = withClash { patchSelector(group, name) }
    if (!patched) return false

    UiStore(this).proxyLastGroup = group
    withProfile { rememberProxySelection(profile, group, name) }
    closeConnectionsAfterUserProxySwitchIfEnabled(showFeedback)
    ChangeBus.emit(ChangeBus.Change.Proxies, remote)
    return true
}
