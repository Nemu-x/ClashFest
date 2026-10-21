package com.github.kr328.clash.companion.protocol

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.github.kr328.clash.companion.agent.FakeSession
import com.github.kr328.clash.companion.agent.GatewayServer
import com.github.kr328.clash.companion.agent.bodyText
import com.github.kr328.clash.companion.agent.gateway
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Byte-for-byte conformance against the clash-companion golden vectors (PROTOCOL.md §11).
 * A one-byte divergence here means ClashFest and the Go reference are not interoperable.
 */
class VectorConformanceTest {

    @Test
    fun canonicalJson() {
        val cases = Vectors.json("canonical_json.json").jsonObject["cases"]!!.jsonArray
        for (case in cases) {
            val obj = case.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val input = obj["input"]!!
            val expected = obj["canonical"]!!.jsonPrimitive.content
            assertEquals("canonical_json case: $name", expected, CanonicalJson.encode(input))
        }
    }

    @Test
    fun ids() {
        val root = Vectors.json("ids.json").jsonObject

        val device = root["deviceId"]!!.jsonObject
        assertEquals(
            device["encoded"]!!.jsonPrimitive.content,
            Base64Url.encode(Hex.decode(device["rawHex"]!!.jsonPrimitive.content)),
        )

        val token = root["token"]!!.jsonObject
        assertEquals(
            token["encoded"]!!.jsonPrimitive.content,
            Base64Url.encode(Hex.decode(token["rawHex"]!!.jsonPrimitive.content)),
        )

        val hash = root["tokenHash"]!!.jsonObject
        assertEquals(
            hash["sha256"]!!.jsonPrimitive.content,
            Ids.tokenHash(hash["token"]!!.jsonPrimitive.content),
        )
    }

    @Test
    fun base64UrlRoundTrips() {
        val device = Vectors.json("ids.json").jsonObject["deviceId"]!!.jsonObject
        val raw = Hex.decode(device["rawHex"]!!.jsonPrimitive.content)
        assertEquals(
            device["rawHex"]!!.jsonPrimitive.content,
            Hex.encode(Base64Url.decode(Base64Url.encode(raw))),
        )
    }

    @Test
    fun fingerprint() {
        val root = Vectors.json("fingerprint.json").jsonObject
        val der = Base64.getDecoder().decode(root["certDer"]!!.jsonPrimitive.content)
        assertEquals(root["fp"]!!.jsonPrimitive.content, Fingerprint.of(der))
    }

    @Test
    fun discoveryTxt() {
        val root = Vectors.json("discovery_txt.json").jsonObject
        val fields = root["fields"]!!.jsonObject
        val txt = DiscoveryTxt(
            app = fields["App"]!!.jsonPrimitive.content,
            id = fields["ID"]!!.jsonPrimitive.content,
            name = fields["Name"]!!.jsonPrimitive.content,
            ver = fields["Ver"]!!.jsonPrimitive.int,
            fp = fields["FP"]!!.jsonPrimitive.content,
        )

        val expected = root["encoded"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, txt.encode())

        // decode tolerates any order
        assertEquals(txt, DiscoveryTxt.decode(expected.shuffled()))
    }

    @Test
    fun pairing() {
        val cases = Vectors.json("pairing.json").jsonObject["cases"]!!.jsonArray
        for (case in cases) {
            val obj = case.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val uri = obj["uri"]!!.jsonPrimitive.content
            val decodes = obj["decodes"]!!.jsonPrimitive.boolean
            val fields = obj["fields"]?.jsonObject

            if (fields != null) {
                val payload = PairingPayload(
                    ip = fields["ip"]!!.jsonPrimitive.content,
                    port = fields["port"]!!.jsonPrimitive.int,
                    id = fields["id"]!!.jsonPrimitive.content,
                    fp = fields["fp"]!!.jsonPrimitive.content,
                    token = fields["token"]!!.jsonPrimitive.content,
                    name = fields["name"]?.jsonPrimitive?.content,
                    app = fields["app"]?.jsonPrimitive?.content,
                )
                // Encode(fields) must equal uri exactly.
                assertEquals("pairing encode: $name", uri, payload.toUri())
                // Decode(uri) must reproduce fields.
                assertEquals("pairing decode: $name", payload, PairingPayload.parse(uri))
            } else if (decodes) {
                assertNotNull("pairing should decode: $name", PairingPayload.parse(uri))
            } else {
                assertNull("pairing should reject: $name", PairingPayload.parse(uri))
            }
        }
    }

    // --- control.json (remote buttons, §8.6–§8.19, §9.2) -------------------------------------

    private val controlDecoders: Map<String, (JsonElement) -> JsonObject> = mapOf(
        "ProxyList" to { e -> ProxyList.fromJson(e).toJson() },
        "SelectRequest" to { e -> SelectRequest.fromJson(e).toJson() },
        "SelectResponse" to { e -> SelectResponse.fromJson(e).toJson() },
        "UnfixRequest" to { e -> UnfixRequest.fromJson(e).toJson() },
        "DelayRequest" to { e -> DelayRequest.fromJson(e).toJson() },
        "DelayResponse" to { e -> DelayResponse.fromJson(e).toJson() },
        "ModeState" to { e -> ModeState.fromJson(e).toJson() },
        "ModeRequest" to { e -> ModeRequest.fromJson(e).toJson() },
        "ModeResponse" to { e -> ModeResponse.fromJson(e).toJson() },
        "ProfileList" to { e -> ProfileList.fromJson(e).toJson() },
        "ProfileRequest" to { e -> ProfileRequest.fromJson(e).toJson() },
        "ProfileSelectResponse" to { e -> ProfileSelectResponse.fromJson(e).toJson() },
        "OKResponse" to { e -> OkResponse.fromJson(e).toJson() },
        "ProviderList" to { e -> ProviderList.fromJson(e).toJson() },
        "ProviderRequest" to { e -> ProviderRequest.fromJson(e).toJson() },
        "ConnectionList" to { e -> ConnectionList.fromJson(e).toJson() },
        "CloseRequest" to { e -> CloseRequest.fromJson(e).toJson() },
        "CloseResponse" to { e -> CloseResponse.fromJson(e).toJson() },
        "Traffic" to { e -> Traffic.fromJson(e).toJson() },
        "LogList" to { e -> LogList.fromJson(e).toJson() },
        "SettingList" to { e -> SettingList.fromJson(e).toJson() },
        "SettingRequest" to { e -> SettingRequest.fromJson(e).toJson() },
        "SettingResponse" to { e -> SettingResponse.fromJson(e).toJson() },
        "ActionList" to { e -> ActionList.fromJson(e).toJson() },
        "ActionRequest" to { e -> ActionRequest.fromJson(e).toJson() },
        "Status" to { e -> Status.fromJson(e).toJson() },
        "Changed" to { e -> Changed.fromJson(e).toJson() },
    )

    @Test
    fun controlBodies() {
        val bodies = Vectors.json("control.json").jsonObject["bodies"]!!.jsonArray
        for (case in bodies) {
            val obj = case.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val type = obj["type"]!!.jsonPrimitive.content
            val canonical = obj["canonical"]!!.jsonPrimitive.content
            val decode = controlDecoders[type] ?: error("control body $name: no model for $type")
            // decode into the named model, re-encode canonically: bytes must match (§3.4).
            assertEquals("control body: $name", canonical, CanonicalJson.encode(decode(Json.parseToJsonElement(canonical))))
        }
    }

    @Test
    fun controlEvents() {
        val events = Vectors.json("control.json").jsonObject["events"]!!.jsonArray
        for (case in events) {
            val obj = case.jsonObject
            val type = obj["type"]!!.jsonPrimitive.content
            val data = obj["data"]!!.jsonPrimitive.content
            val parsed = Json.parseToJsonElement(data)
            val model = when (type) {
                Events.CHANGED -> Changed.fromJson(parsed).toJson()
                Events.TRAFFIC -> Traffic.fromJson(parsed).toJson()
                Events.LOG -> LogLine.fromJson(parsed.jsonObject).toJson()
                Events.STATUS -> Status.fromJson(parsed).toJson()
                else -> error("unknown event type $type")
            }
            assertEquals("event data: $type", data, CanonicalJson.encode(model))
            assertEquals("event frame: $type", obj["frame"]!!.jsonPrimitive.content, Events.frame(type, model))
        }
    }

    /**
     * Requests the gateway itself must reject. Agent "full" in the vectors advertises every
     * capability; ClashFest does not advertise unfix / profile_delete / logs, so those cases must
     * answer not_found instead (§8.6). Agent "p1" advertises no remote button.
     */
    @Test
    fun controlErrors() {
        val notAdvertised = mapOf(
            "/v1/proxies/unfix" to Capabilities.UNFIX,
            "/v1/profiles/delete" to Capabilities.PROFILE_DELETE,
            "/v1/logs" to Capabilities.LOGS,
        )
        val errors = Vectors.json("control.json").jsonObject["errors"]!!.jsonArray
        for (case in errors) {
            val obj = case.jsonObject
            val name = obj["name"]!!.jsonPrimitive.content
            val agent = obj["agent"]!!.jsonPrimitive.content
            val auth = obj["auth"]!!.jsonPrimitive.boolean
            val path = obj["path"]!!.jsonPrimitive.content
            val body = obj["body"]?.jsonPrimitive?.content
            val method = NanoHTTPD.Method.valueOf(obj["method"]!!.jsonPrimitive.content)

            val remote = if (agent == "p1") emptyList() else GatewayServer.CLASHFEST_REMOTE_CAPABILITIES
            val server = gateway(remote = remote)
            try {
                val response = server.serve(FakeSession(method, path, body, token = if (auth) "good-token" else null))
                val unadvertised = auth && agent == "full" && path.substringBefore('?') in notAdvertised
                val status = if (unadvertised) 404 else obj["status"]!!.jsonPrimitive.int
                val code = if (unadvertised) "not_found" else obj["code"]!!.jsonPrimitive.content
                val text = response.bodyText()
                assertEquals("control error status: $name", status, response.status.requestStatus)
                assertEquals(
                    "control error code: $name",
                    code,
                    Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
                )
            } finally {
                server.events.stop()
            }
        }
    }
}
