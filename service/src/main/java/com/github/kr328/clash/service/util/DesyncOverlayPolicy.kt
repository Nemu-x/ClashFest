package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.model.DesyncProfileSettings
import com.github.kr328.clash.service.store.ServiceStore
import java.util.UUID

/**
 * Whether desync on top of the subscription applies to a profile, and the runner that adds it.
 *
 * The user turns it on per profile ([UserLayer.desync]); the operator can steer it with the
 * `X-Desync` subscription header:
 *  - `allow` (or no header): applies only when the user turned it on;
 *  - `on`: applies with the default services until the user turns it off;
 *  - `deny`: never applies, whatever was stored before (the settings screen hides it).
 *
 * Like [ConfigScriptPolicy], the decision is made at compose time, not only in the UI, so a
 * `deny` that arrives after the user enabled it takes effect on the next compose.
 */
object DesyncOverlayPolicy {
    enum class Operator { Allow, On, Deny }

    fun operatorOf(header: String?): Operator = when (header?.trim()?.lowercase()) {
        "on" -> Operator.On
        "deny" -> Operator.Deny
        else -> Operator.Allow
    }

    fun operatorFor(context: Context, uuid: UUID): Operator =
        operatorOf(ServiceStore(context).subscriptionDesyncPolicyFor(uuid))

    /** The settings to compose with, or null when the overlay does not apply. */
    fun effective(overlay: DesyncOverlay?, operator: Operator): DesyncProfileSettings? = when (operator) {
        Operator.Deny -> null
        Operator.On -> if (overlay == null) DesyncProfileSettings() else overlay.settings.takeIf { overlay.enabled }
        Operator.Allow -> overlay?.takeIf { it.enabled }?.settings
    }

    /** True when the overlay applies to [uuid] with [overlay] stored. */
    fun isActive(context: Context, uuid: UUID, overlay: DesyncOverlay?): Boolean =
        effective(overlay, operatorFor(context, uuid)) != null

    /**
     * The runner for [uuid]: it adds the overlay when [effective] says so. A failure (the
     * subscription cannot carry it, or the engine rejects the result) composes without it: the
     * subscription must keep working.
     */
    fun runnerFor(context: Context, uuid: UUID): DesyncOverlayRunner {
        val operator = operatorFor(context, uuid)
        return runner(operator) { yaml, settings -> Clash.composeDesyncOverlay(yaml, settings.toRequest()) }
    }

    internal fun runner(
        operator: Operator,
        compose: (String, DesyncProfileSettings) -> com.github.kr328.clash.core.model.DesyncGeneratedConfig,
    ): DesyncOverlayRunner = DesyncOverlayRunner { yaml, overlay ->
        val settings = effective(overlay, operator) ?: return@DesyncOverlayRunner null
        val result = compose(yaml, settings)
        result.yaml?.takeIf { it.isNotBlank() } ?: run {
            Log.w("Desync overlay not applied: ${result.error ?: "no result"}")
            null
        }
    }
}
