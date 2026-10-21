package com.github.kr328.clash.companion.controller

import com.github.kr328.clash.companion.agent.FakeHooks
import com.github.kr328.clash.companion.agent.FakePairings
import com.github.kr328.clash.companion.agent.GOOD_TOKEN
import com.github.kr328.clash.companion.agent.GatewayServer
import com.github.kr328.clash.companion.agent.gateway
import com.github.kr328.clash.companion.protocol.Capabilities
import com.github.kr328.clash.util.ChangeBus
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The controller client driving ClashFest's own gateway over a real socket (plain HTTP: pinning is
 * the socket factory's job and is covered by the live check against the Go agent). Every button,
 * the error mapping on the wire, and the live event stream with reconnect and self-unpair.
 */
class CompanionClientGatewayTest {
    private fun <T> withGateway(block: (client: CompanionClient, hooks: FakeHooks, pairings: FakePairings) -> T): T {
        val hooks = FakeHooks()
        val pairings = FakePairings()
        val server: GatewayServer = gateway(hooks, pairings)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            val client = CompanionClient("http://127.0.0.1:${server.listeningPort}", GOOD_TOKEN, "ctl-id", "Phone", http)
            return block(client, hooks, pairings)
        } finally {
            server.stop()
            server.events.stop()
        }
    }

    @Test
    fun everyButtonRoundTrips() = withGateway { client, hooks, _ ->
        val status = client.status()
        assertEquals("on", status.power)
        assertEquals("HK-01", status.summary?.proxy)
        assertTrue(Capabilities.EVENTS in status.capabilities)

        val groups = client.proxies().groups
        assertEquals("Proxy", groups.single().name)
        assertEquals("JP-01", client.select("Proxy", "JP-01").now)
        assertEquals(listOf(com.github.kr328.clash.companion.protocol.Delay("HK-01", 64)), client.delay("Proxy", "HK-01"))

        assertEquals("rule", client.mode().mode)
        assertEquals("global", client.setMode("global"))

        assertEquals("p-1", client.profiles().active)
        assertEquals("p-1", client.selectProfile("p-1"))
        client.updateProfile(null)
        client.updateProfile("p-1")

        assertEquals(emptyList(), client.providers())
        client.updateProvider("proxy", "sub1")
        client.healthCheckProvider("sub1")

        assertEquals(0, client.connections().connections.size)
        assertEquals(3, client.closeConnections(null))
        assertEquals(3, client.closeConnections("c1"))
        assertEquals(2L, client.traffic().down)

        val settings = client.settings()
        assertEquals(SettingValue.Bool(false), settings.first { it.key == "ipv6" }.value)
        assertEquals(listOf("system", "gvisor"), settings.first { it.key == "tun_stack" }.options)
        assertEquals(SettingValue.Bool(true), client.setSetting("ipv6", SettingValue.Bool(true))?.value)
        assertEquals(SettingValue.Str("gvisor"), client.setSetting("tun_stack", SettingValue.Str("gvisor"))?.value)

        assertEquals(listOf("healthcheck_all", "reconnect"), client.actions().map { it.id })
        client.runAction("healthcheck_all")

        assertEquals(
            listOf(
                "select:Proxy/JP-01", "delay:Proxy/HK-01/null/5000", "mode:global", "profile:p-1", "update:null", "update:p-1",
                "provider:proxy/sub1", "providercheck:sub1", "close:null", "close:c1",
                "setting:ipv6=true", "setting:tun_stack=\"gvisor\"", "action:healthcheck_all",
            ),
            hooks.calls,
        )
    }

    @Test
    fun errorsArriveAsTheirProtocolCodes() = withGateway { client, _, _ ->
        // ClashFest does not advertise unfix (§8.6: not_found after auth, no side effects).
        assertEquals("not_found", assertFailsWith<CompanionError> { client.unfix("Proxy") }.code)
        // The hook refuses direct → bad_request; an unknown group → not_found.
        assertEquals("bad_request", assertFailsWith<CompanionError> { client.setMode("direct") }.code)
        assertEquals("not_found", assertFailsWith<CompanionError> { client.select("Nope", "JP-01") }.code)
        // Out-of-range timeout is rejected by the gateway before any hook runs.
        assertEquals("bad_request", assertFailsWith<CompanionError> { client.delay("Proxy", timeoutMs = 99) }.code)
    }

    @Test
    fun wrongTokenIsUnauthorized() {
        val pairings = FakePairings()
        val server = gateway(FakeHooks(), pairings)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val client = CompanionClient("http://127.0.0.1:${server.listeningPort}", "bad", "id", "n", OkHttpClient())
            val e = assertFailsWith<CompanionError> { client.status() }
            assertEquals("unauthorized", e.code)
            assertEquals(401, e.httpStatus)
            assertEquals("unauthorized", assertFailsWith<CompanionError> { client.events().read {} }.code)
        } finally {
            server.stop()
            server.events.stop()
        }
    }

    @Test
    fun followerStreamsStatusThenChanges() = withGateway { client, _, _ ->
        runBlocking {
            val seen = Channel<CompanionEvent>(Channel.UNLIMITED)
            val states = Channel<CompanionEventFollower.State>(Channel.UNLIMITED)
            var opens = 0
            val job = launch(Dispatchers.Default) {
                CompanionEventFollower(client, listOf("traffic"), initialBackoffMs = 50).run(
                    onState = { s, _ -> states.send(s) },
                    onOpen = { opens++ },
                    onEvent = { seen.send(it) },
                )
            }
            withTimeout(10_000) {
                assertEquals(CompanionEventFollower.State.Connecting, states.receive())
                assertEquals(CompanionEventFollower.State.Live, states.receive())
                assertIs<CompanionEvent.StatusChanged>(seen.receive())
                ChangeBus.emit(ChangeBus.Change.Proxies, remote = true)
                var changed: CompanionEvent.ResourceChanged? = null
                while (changed == null) {
                    when (val e = seen.receive()) {
                        is CompanionEvent.ResourceChanged -> changed = e
                        else -> Unit // traffic ticks arrive about once a second
                    }
                }
                assertEquals("proxies", changed.resource)
            }
            assertEquals(1, opens)
            // Leaving the screen cancels the follower; the blocked read must not hold it.
            withTimeout(5_000) {
                job.cancel()
                job.join()
            }
        }
    }

    @Test
    fun unpairRevokesAndEndsTheStream() = withGateway { client, _, pairings ->
        runBlocking {
            val rejected = Channel<CompanionError>(Channel.UNLIMITED)
            val opened = Channel<Unit>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) {
                CompanionEventFollower(client, initialBackoffMs = 50).run(
                    onState = { _, _ -> },
                    onOpen = { opened.send(Unit) },
                    onEvent = {},
                    onRejected = {
                        rejected.send(it)
                        false
                    },
                )
            }
            withTimeout(10_000) {
                opened.receive()
                client.unpair()
                assertEquals(1, pairings.revoked.size)
                // The agent closes the revoked controller's stream; the reconnect is refused.
                assertEquals("unauthorized", rejected.receive().code)
                job.join()
            }
            assertEquals("unauthorized", assertFailsWith<CompanionError> { client.status() }.code)
        }
    }
}
