package com.github.kr328.clash.companion.controller

import com.github.kr328.clash.companion.protocol.Changed
import com.github.kr328.clash.companion.protocol.ControlDecodeException
import com.github.kr328.clash.companion.protocol.Events
import com.github.kr328.clash.companion.protocol.LogLine
import com.github.kr328.clash.companion.protocol.Setting
import com.github.kr328.clash.companion.protocol.Status
import com.github.kr328.clash.companion.protocol.Traffic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A setting's value as the controller sees it (PROTOCOL.md §8.18): a JSON boolean or a string. */
sealed class SettingValue {
    data class Bool(val value: Boolean) : SettingValue()
    data class Str(val value: String) : SettingValue()

    fun toJson(): JsonPrimitive = when (this) {
        is Bool -> JsonPrimitive(value)
        is Str -> JsonPrimitive(value)
    }

    companion object {
        /** Null for anything that is neither a JSON boolean nor a JSON string. */
        fun of(primitive: JsonPrimitive): SettingValue? = when {
            primitive.isString -> Str(primitive.content)
            primitive.booleanOrNull != null -> Bool(primitive.booleanOrNull!!)
            else -> null
        }
    }
}

/** One agent setting with a typed [value]; `options` is set for enums only. */
data class RemoteSetting(
    val key: String,
    val type: String,
    val value: SettingValue,
    val options: List<String>,
    val label: String?,
) {
    companion object {
        /**
         * Typed view of [setting], or null when this controller cannot show it: an unknown `type`
         * (ignored like any unknown resource) or a value that does not match its type.
         */
        fun from(setting: Setting): RemoteSetting? {
            val value = SettingValue.of(setting.value) ?: return null
            val fits = when (setting.type) {
                Setting.BOOL -> value is SettingValue.Bool
                Setting.ENUM -> value is SettingValue.Str
                else -> false
            }
            if (!fits) return null
            return RemoteSetting(setting.key, setting.type, value, setting.options, setting.label)
        }
    }
}

/** A decoded `GET /v1/events` event (PROTOCOL.md §9.2). Unknown types decode to nothing. */
sealed class CompanionEvent {
    data class StatusChanged(val status: Status) : CompanionEvent()
    data class ResourceChanged(val resource: String) : CompanionEvent()
    data class TrafficTick(val traffic: Traffic) : CompanionEvent()
    data class Log(val line: LogLine) : CompanionEvent()

    companion object {
        /** Null for unknown event types and for data this controller cannot decode. */
        fun decode(frame: SseFrame): CompanionEvent? {
            val data = try {
                Json.parseToJsonElement(frame.data)
            } catch (_: Exception) {
                return null
            }
            return try {
                when (frame.type) {
                    Events.STATUS -> StatusChanged(Status.fromJson(data))
                    Events.CHANGED -> ResourceChanged(Changed.fromJson(data).resource)
                    Events.TRAFFIC -> TrafficTick(Traffic.fromJson(data))
                    Events.LOG -> Log(LogLine.fromJson(data as? JsonObject ?: return null))
                    else -> null
                }
            } catch (_: ControlDecodeException) {
                null
            }
        }
    }
}
