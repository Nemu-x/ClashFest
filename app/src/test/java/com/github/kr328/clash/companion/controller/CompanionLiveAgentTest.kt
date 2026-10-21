package com.github.kr328.clash.companion.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Live interop against a running agent — typically the clash-companion Go reference gateway with
 * fake hooks — over real pinned TLS. Skipped unless `COMPANION_LIVE_AGENT` names a JSON file with
 * `host`, `port`, `fp` and `token` of a pairing on that agent. It ends by un-pairing (§7.4), so the
 * pairing is single-use.
 */
class CompanionLiveAgentTest {
    private fun agent(): ControllerStore.PairedAgent {
        val path = System.getenv("COMPANION_LIVE_AGENT")
        assumeTrue("COMPANION_LIVE_AGENT not set", !path.isNullOrBlank() && File(path).isFile)
        val o = Json.parseToJsonElement(File(path!!).readText()).jsonObject
        return ControllerStore.PairedAgent(
            deviceId = "live",
            name = "live",
            app = "",
            fp = o["fp"]!!.jsonPrimitive.content,
            token = o["token"]!!.jsonPrimitive.content,
            host = o["host"]!!.jsonPrimitive.content,
            port = o["port"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun drivesTheLiveAgent() {
        val agent = agent()

        // A wrong pin never reaches the HTTP layer.
        val wrongPin = CompanionClient(agent.copy(fp = "00".repeat(32)), "ctl", "JVM")
        val pinFailure = assertFailsWith<IOException> { wrongPin.status() }
        assertTrue(isUnreachable(pinFailure))

        val client = CompanionClient(agent, "ctl", "JVM")
        val status = client.status()
        println("live status: $status")
        assertTrue("events" in status.capabilities && "unpair" in status.capabilities)

        val groups = client.proxies().groups
        println("live groups: ${groups.map { "${it.name}=${it.now}" }}")
        val proxy = groups.first { it.selectable }
        val target = proxy.nodes.first { it.name != proxy.now }.name
        assertEquals(target, client.select(proxy.name, target).now)
        println("live delay group: ${client.delay(proxy.name)}")
        println("live delay node: ${client.delay(proxy.name, target, timeoutMs = 3000)}")

        val mode = client.mode()
        assertEquals("global", client.setMode("global"))
        assertEquals("bad_request", assertFailsWith<CompanionError> { client.setMode("direct") }.code)
        client.setMode(mode.mode)

        val profiles = client.profiles()
        println("live profiles: $profiles")
        client.updateProfile(null)
        println("live providers: ${client.providers()}")
        println("live connections: ${client.connections()}")
        println("live closed: ${client.closeConnections(null)}")
        println("live traffic: ${client.traffic()}")
        val settings = client.settings()
        println("live settings: $settings")
        settings.firstOrNull { it.value is SettingValue.Bool }?.let { s ->
            val flipped = !(s.value as SettingValue.Bool).value
            assertEquals(SettingValue.Bool(flipped), client.setSetting(s.key, SettingValue.Bool(flipped))?.value)
        }
        settings.firstOrNull { it.value is SettingValue.Str && it.options.size > 1 }?.let { s ->
            val other = s.options.first { it != (s.value as SettingValue.Str).value }
            assertEquals(SettingValue.Str(other), client.setSetting(s.key, SettingValue.Str(other))?.value)
        }
        client.actions().firstOrNull()?.let { client.runAction(it.id) }
        assertEquals("not_found", assertFailsWith<CompanionError> { client.runAction("no_such_action") }.code)

        runBlocking {
            val seen = Channel<CompanionEvent>(Channel.UNLIMITED)
            val rejected = Channel<CompanionError>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) {
                CompanionEventFollower(client, listOf("traffic"), initialBackoffMs = 100).run(
                    onState = { s, e -> println("live link: $s ${e?.javaClass?.simpleName ?: ""}") },
                    onOpen = {},
                    onEvent = { seen.send(it) },
                    onRejected = {
                        rejected.send(it)
                        false
                    },
                )
            }
            withTimeout(15_000) {
                assertIs<CompanionEvent.StatusChanged>(seen.receive())
                // A gateway change is announced to every controller, including this one.
                client.select(proxy.name, proxy.now)
                val kinds = mutableSetOf<String>()
                while (!("traffic" in kinds && "changed" in kinds)) {
                    when (val e = seen.receive()) {
                        is CompanionEvent.TrafficTick -> kinds += "traffic"
                        is CompanionEvent.ResourceChanged -> {
                            println("live changed: ${e.resource}")
                            kinds += "changed"
                        }
                        is CompanionEvent.StatusChanged -> println("live status event: ${e.status.summary}")
                        is CompanionEvent.Log -> Unit
                    }
                }
                // Forget: unpair, the agent drops the stream, the reconnect is refused.
                client.unpair()
                assertEquals("unauthorized", rejected.receive().code)
                job.join()
            }
        }
        assertEquals("unauthorized", assertFailsWith<CompanionError> { client.status() }.code)
    }
}
