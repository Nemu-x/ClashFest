package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.companion.protocol.Ids
import com.github.kr328.clash.util.ChangeBus
import fi.iki.elonen.NanoHTTPD.Method
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayRemoteButtonsTest {
    private val servers = mutableListOf<GatewayServer>()

    private fun server(hooks: FakeHooks = FakeHooks(), pairings: FakePairings = FakePairings()) =
        gateway(hooks, pairings).also { servers += it }

    @AfterTest
    fun tearDown() {
        servers.forEach { it.events.stop() }
    }

    @Test
    fun statusAdvertisesClashFestButtonsInProtocolOrder() {
        val response = server().serve(FakeSession(Method.GET, "/v1/status"))
        val caps = Json.parseToJsonElement(response.bodyText()).jsonObject["capabilities"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(
            listOf(
                "status", "power", "subscription", "rename", "unpair", "proxies", "select", "delay", "mode",
                "profiles", "profile_update", "providers", "connections", "traffic", "settings", "actions", "events",
            ),
            caps,
        )
    }

    @Test
    fun statusCarriesTheSummary() {
        val body = server().serve(FakeSession(Method.GET, "/v1/status")).bodyText()
        assertTrue(body.contains("\"summary\":{\"appVersion\":\"1.0.0\",\"mode\":\"rule\",\"profile\":\"Main\",\"proxy\":\"HK-01\"}"), body)
    }

    @Test
    fun summaryFailureDoesNotFailStatus() {
        val hooks = object : FakeHooks() {
            override fun summary() = throw IllegalStateException("engine gone")
        }
        val response = server(hooks).serve(FakeSession(Method.GET, "/v1/status"))
        assertEquals(200, response.status.requestStatus)
        assertTrue(!response.bodyText().contains("summary"))
    }

    @Test
    fun selectGoesThroughTheSelectHookAndRepliesNow() {
        val hooks = FakeHooks()
        val response = server(hooks).serve(
            FakeSession(Method.POST, "/v1/proxies/select", """{"group":"Proxy","node":"JP-01"}"""),
        )
        assertEquals(200, response.status.requestStatus)
        assertEquals("""{"group":"Proxy","now":"JP-01","ok":true}""", response.bodyText())
        assertEquals(listOf("select:Proxy/JP-01"), hooks.calls)
    }

    @Test
    fun hookNotFoundAndRefusedMapToTheErrorModel() {
        val hooks = FakeHooks()
        val gw = server(hooks)
        val missing = gw.serve(FakeSession(Method.POST, "/v1/proxies/select", """{"group":"Nope","node":"x"}"""))
        assertEquals(404, missing.status.requestStatus)
        assertTrue(missing.bodyText().contains("\"code\":\"not_found\""))

        val refused = gw.serve(FakeSession(Method.POST, "/v1/mode", """{"mode":"direct"}"""))
        assertEquals(400, refused.status.requestStatus)
        assertTrue(refused.bodyText().contains("\"code\":\"bad_request\""))
    }

    @Test
    fun otherHookFailuresAreCoreUnavailable() {
        val hooks = object : FakeHooks() {
            override fun proxyGroups() = throw CompanionHooks.CoreUnavailable("VPN is not running")
        }
        val response = server(hooks).serve(FakeSession(Method.GET, "/v1/proxies"))
        assertEquals(502, response.status.requestStatus)
        assertTrue(response.bodyText().contains("\"code\":\"core_unavailable\""))
    }

    @Test
    fun unadvertisedButtonsAnswerNotFoundWithoutSideEffects() {
        val hooks = FakeHooks()
        val gw = server(hooks)
        for ((path, body) in listOf(
            "/v1/proxies/unfix" to """{"group":"Auto"}""",
            "/v1/profiles/delete" to """{"id":"p-1"}""",
        )) {
            val response = gw.serve(FakeSession(Method.POST, path, body))
            assertEquals(404, response.status.requestStatus, path)
        }
        assertEquals(404, gw.serve(FakeSession(Method.GET, "/v1/logs")).status.requestStatus)
        assertTrue(hooks.calls.isEmpty())
    }

    @Test
    fun delayDefaultsTheTimeoutAndValidatesTheUrl() {
        val hooks = FakeHooks()
        val gw = server(hooks)
        assertEquals(200, gw.serve(FakeSession(Method.POST, "/v1/proxies/delay", """{"group":"Proxy"}""")).status.requestStatus)
        assertEquals(listOf("delay:Proxy/null/null/5000"), hooks.calls)
        val bad = gw.serve(FakeSession(Method.POST, "/v1/proxies/delay", """{"group":"Proxy","url":"ftp://x/y"}"""))
        assertEquals(400, bad.status.requestStatus)
    }

    @Test
    fun settingsAreTypeCheckedAgainstTheAdvertisedList() {
        val hooks = FakeHooks()
        val gw = server(hooks)
        assertEquals(400, gw.serve(FakeSession(Method.POST, "/v1/settings", """{"key":"ipv6","value":"yes"}""")).status.requestStatus)
        assertEquals(400, gw.serve(FakeSession(Method.POST, "/v1/settings", """{"key":"tun_stack","value":"lwip"}""")).status.requestStatus)
        assertEquals(404, gw.serve(FakeSession(Method.POST, "/v1/settings", """{"key":"allow_lan","value":true}""")).status.requestStatus)
        val ok = gw.serve(FakeSession(Method.POST, "/v1/settings", """{"key":"ipv6","value":true}"""))
        assertEquals("""{"ok":true,"setting":{"key":"ipv6","type":"bool","value":true}}""", ok.bodyText())
        assertEquals(listOf("setting:ipv6=true"), hooks.calls)
    }

    @Test
    fun unknownActionIsNotFound() {
        val hooks = FakeHooks()
        val gw = server(hooks)
        assertEquals(404, gw.serve(FakeSession(Method.POST, "/v1/actions", """{"action":"flush_dns"}""")).status.requestStatus)
        assertEquals(200, gw.serve(FakeSession(Method.POST, "/v1/actions", """{"action":"reconnect"}""")).status.requestStatus)
        assertEquals(listOf("action:reconnect"), hooks.calls)
    }

    @Test
    fun unpairRevokesOnlyTheCaller() {
        val pairings = FakePairings()
        pairings.pairings += PairingStore.Pairing(tokenHash = Ids.tokenHash("other"), pairedAt = 2L)
        val gw = server(pairings = pairings)
        assertEquals("""{"ok":true}""", gw.serve(FakeSession(Method.POST, "/v1/unpair", "{}")).bodyText())
        assertEquals(listOf(Ids.tokenHash(GOOD_TOKEN)), pairings.revoked)
        assertEquals(401, gw.serve(FakeSession(Method.GET, "/v1/status")).status.requestStatus)
        assertEquals(200, gw.serve(FakeSession(Method.GET, "/v1/status", token = "other")).status.requestStatus)
    }

    @Test
    fun eventStreamStartsWithStatusFollowsTheBusAndClosesOnUnpair() {
        val gw = server()
        gw.events.start()
        val response = gw.serve(FakeSession(Method.GET, "/v1/events"))
        assertEquals(200, response.status.requestStatus)
        assertEquals("text/event-stream", response.mimeType)
        val stream = response.data

        val first = stream.readFrame()!!
        assertTrue(first.startsWith("event: status\ndata: {\"app\":\"clashfest\""), first)

        // A change made on the device itself (not through the gateway) reaches the stream too.
        ChangeBus.emit(ChangeBus.Change.Mode, remote = false)
        assertEquals("event: changed\ndata: {\"resource\":\"mode\"}\n\n", stream.readFrame())
        assertTrue(stream.readFrame()!!.startsWith("event: status\n"))

        gw.serve(FakeSession(Method.POST, "/v1/unpair", "{}"))
        assertNull(stream.readFrame())
        assertEquals(0, gw.events.openStreams)
    }

    @Test
    fun eventsRejectsABadLevelBeforeStreaming() {
        val response = server().serve(FakeSession(Method.GET, "/v1/events?topics=logs&level=trace"))
        assertEquals(400, response.status.requestStatus)
    }
}
