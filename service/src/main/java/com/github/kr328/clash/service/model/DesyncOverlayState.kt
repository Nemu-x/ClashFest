package com.github.kr328.clash.service.model

import com.github.kr328.clash.service.util.DesyncOverlay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the desync-overlay screen of a subscription profile shows. */
@Serializable
data class DesyncOverlayState(
    /** The user's stored choice; null when never set. */
    val overlay: DesyncOverlay? = null,
    /** The operator's `X-Desync` policy: `allow`, `on` or `deny`. */
    val operator: String = "allow",
    /** True when the overlay applies on the next compose. */
    val active: Boolean = false,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String): DesyncOverlayState? =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()

        fun encodeOverlay(overlay: DesyncOverlay): String = json.encodeToString(DesyncOverlay.serializer(), overlay)

        fun decodeOverlay(raw: String): DesyncOverlay? =
            runCatching { json.decodeFromString(DesyncOverlay.serializer(), raw) }.getOrNull()
    }
}
