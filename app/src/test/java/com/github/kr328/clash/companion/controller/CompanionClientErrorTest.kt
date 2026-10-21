package com.github.kr328.clash.companion.controller

import com.github.kr328.clash.companion.protocol.Vectors
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Error responses map to the §5.5 codes, with or without the envelope. */
class CompanionClientErrorTest {
    private data class Case(val name: String, val status: Int, val code: String)

    private val cases by lazy {
        Vectors.json("control.json").jsonObject["errors"]!!.jsonArray.map {
            val o = it.jsonObject
            Case(o["name"]!!.jsonPrimitive.content, o["status"]!!.jsonPrimitive.int, o["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun envelopeCodeWins() {
        for (c in cases) {
            val error = CompanionClient.errorFrom(c.status, """{"error":{"code":"${c.code}","message":"m ${c.name}"}}""")
            assertEquals(c.code, error.code, c.name)
            assertEquals("m ${c.name}", error.message, c.name)
            assertEquals(c.status, error.httpStatus, c.name)
        }
    }

    @Test
    fun statusMapsToTheSameCodeWithoutAnEnvelope() {
        for (c in cases) {
            assertEquals(c.code, CompanionClient.errorFrom(c.status, "").code, c.name)
            assertEquals(c.code, CompanionClient.errorFrom(c.status, "<html>proxy error</html>").code, c.name)
        }
    }

    @Test
    fun everyTableCodeMaps() {
        val table = mapOf(
            400 to "bad_request", 401 to "unauthorized", 403 to "forbidden", 404 to "not_found",
            409 to "version_unsupported", 429 to "pin_rate_limited", 500 to "internal", 502 to "core_unavailable",
        )
        table.forEach { (status, code) -> assertEquals(code, CompanionClient.errorFrom(status, "{}").code) }
        assertEquals("http_418", CompanionClient.errorFrom(418, "").code)
        // A malformed envelope (non-string code) falls back to the status.
        assertEquals("not_found", CompanionClient.errorFrom(404, """{"error":{"code":7}}""").code)
    }
}
