package com.github.kr328.clash.companion.controller

import com.github.kr328.clash.companion.protocol.CanonicalJson
import com.github.kr328.clash.companion.protocol.Setting
import com.github.kr328.clash.companion.protocol.SettingList
import com.github.kr328.clash.companion.protocol.SettingRequest
import com.github.kr328.clash.companion.protocol.SettingResponse
import com.github.kr328.clash.companion.protocol.Vectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Setting values on the controller side are Bool | Str (PROTOCOL.md §8.18). */
class RemoteSettingTest {
    private fun body(name: String): String =
        Vectors.json("control.json").jsonObject["bodies"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == name }
            .jsonObject["canonical"]!!.jsonPrimitive.content

    @Test
    fun decodesTheSettingsVector() {
        val settings = SettingList.fromJson(Json.parseToJsonElement(body("settings.response")))
            .settings.mapNotNull(RemoteSetting::from)
        assertEquals(
            listOf(
                RemoteSetting("tun", Setting.BOOL, SettingValue.Bool(true), emptyList(), null),
                RemoteSetting(
                    "log_level", Setting.ENUM, SettingValue.Str("info"),
                    listOf("silent", "error", "warning", "info", "debug"), null,
                ),
                RemoteSetting("bypass_lan_apps", Setting.BOOL, SettingValue.Bool(false), emptyList(), "Обход LAN-приложений"),
            ),
            settings,
        )
    }

    @Test
    fun decodesTheSetResponseVector() {
        val setting = RemoteSetting.from(SettingResponse.fromJson(Json.parseToJsonElement(body("settings.set.response"))).setting)
        assertEquals(SettingValue.Bool(false), setting?.value)
    }

    @Test
    fun encodesSetRequestsExactlyLikeTheVectors() {
        assertEquals(
            body("settings.set.request.bool"),
            CanonicalJson.encode(SettingRequest("tun", SettingValue.Bool(false).toJson()).toJson()),
        )
        assertEquals(
            body("settings.set.request.enum"),
            CanonicalJson.encode(SettingRequest("log_level", SettingValue.Str("debug").toJson()).toJson()),
        )
    }

    @Test
    fun dropsSettingsItCannotShow() {
        // Unknown type: ignored like any unknown resource.
        assertNull(RemoteSetting.from(Setting("x", "range", JsonPrimitive(5))))
        // Value of the wrong type for its declared type.
        assertNull(RemoteSetting.from(Setting("tun", Setting.BOOL, JsonPrimitive("true"))))
        assertNull(RemoteSetting.from(Setting("log_level", Setting.ENUM, JsonPrimitive(true), listOf("info"))))
        // A number is neither a boolean nor a string.
        assertNull(SettingValue.of(JsonPrimitive(1)))
    }
}
