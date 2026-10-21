package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.companion.protocol.ActionInfo
import com.github.kr328.clash.companion.protocol.ConnectionList
import com.github.kr328.clash.companion.protocol.Delay
import com.github.kr328.clash.companion.protocol.ModeState
import com.github.kr328.clash.companion.protocol.ProfileList
import com.github.kr328.clash.companion.protocol.ProviderInfo
import com.github.kr328.clash.companion.protocol.ProxyGroup
import com.github.kr328.clash.companion.protocol.Setting
import com.github.kr328.clash.companion.protocol.Summary
import com.github.kr328.clash.companion.protocol.Traffic
import kotlinx.serialization.json.JsonPrimitive

/**
 * App-only operations the mihomo core cannot perform (PROTOCOL.md §1, §8). The gateway server is
 * decoupled from Android specifics through this interface; the concrete implementation wires them
 * to the existing VPN start/stop, subscription import, status snapshot and rename.
 *
 * The remote buttons (§8.6–§8.19) are called only after the gateway authenticated the request,
 * checked the capability is advertised and validated the body. Each MUST go through the same code
 * path as the equivalent on-device control. They report a missing group/node/profile/… with
 * [NotFound], a value the app refuses with [Refused] and an engine that is not running with
 * [CoreUnavailable]; any other exception is also reported as `core_unavailable` (§5.5).
 */
interface CompanionHooks {
    /** Current tunnel power state: `"on"` or `"off"`. */
    fun powerState(): String

    /**
     * Apply a power action. [action] is one of `on`/`off`/`toggle` (already validated).
     * Returns the resulting state (`"on"`/`"off"`).
     * @throws PowerUnavailable when the VPN cannot be started (e.g. consent not granted on device).
     */
    fun power(action: String): String

    /**
     * Import a shared subscription. Exactly one of [url]/[payload] is non-null (already validated).
     * @throws PayloadUnsupported if an inline [payload] is given but this agent only supports url.
     */
    fun importSubscription(url: String?, payload: String?, name: String)

    /** Persist the new display name and re-announce mDNS (§8.5). */
    fun rename(name: String)

    /** Optional at-a-glance state for `status` (§8.1). Must not throw for missing parts. */
    fun summary(): Summary? = null

    /** §8.7: groups in the order and visibility the app's node picker shows. */
    fun proxyGroups(): List<ProxyGroup> = unsupported()

    /** §8.8: select [node] in [group]; returns the group's node now in use. */
    fun selectProxy(group: String, node: String): String = unsupported()

    /** §8.9: test [node] of [group], or the whole group when [node] is null. */
    fun testDelay(group: String, node: String?, url: String?, timeoutMs: Int): List<Delay> = unsupported()

    /** §8.10. */
    fun mode(): ModeState = unsupported()

    /** §8.10: [mode] is one of rule/global/direct (already validated); returns the applied mode. */
    fun setMode(mode: String): String = unsupported()

    /** §8.11. */
    fun profiles(): ProfileList = unsupported()

    /** §8.11: activate profile [id]; returns the active id. */
    fun selectProfile(id: String): String = unsupported()

    /** §8.12: refresh one remote profile, or every remote profile when [id] is null. */
    fun updateProfile(id: String?): Unit = unsupported()

    /** §8.14. */
    fun providers(): List<ProviderInfo> = unsupported()

    /** §8.14: [kind] is `proxy` or `rule` (already validated). */
    fun updateProvider(kind: String, name: String): Unit = unsupported()

    /** §8.14: health-check proxy provider [name]. */
    fun healthCheckProvider(name: String): Unit = unsupported()

    /** §8.15. */
    fun connections(): ConnectionList = unsupported()

    /** §8.15: close connection [id], or every connection when null; returns how many closed. */
    fun closeConnections(id: String?): Int = unsupported()

    /** §8.16. */
    fun traffic(): Traffic = unsupported()

    /** §8.18: only keys that are safe to change remotely. */
    fun settings(): List<Setting> = unsupported()

    /** §8.18: [value] already matches the setting's type and options; returns the new state. */
    fun setSetting(key: String, value: JsonPrimitive): Setting = unsupported()

    /** §8.19. */
    fun actions(): List<ActionInfo> = unsupported()

    /** §8.19: run listed action [id]. */
    fun runAction(id: String): Unit = unsupported()

    class PowerUnavailable(message: String) : Exception(message)
    class PayloadUnsupported(message: String) : Exception(message)

    /** Unknown group/node/profile/provider/connection/setting/action → `not_found`. */
    class NotFound(message: String) : Exception(message)

    /** A value the app refuses (e.g. a mode it does not offer) → `bad_request`. */
    class Refused(message: String) : Exception(message)

    /** The engine is not running or did not answer → `core_unavailable`. */
    class CoreUnavailable(message: String) : Exception(message)
}

private fun unsupported(): Nothing = throw CompanionHooks.NotFound("not supported by this agent")
