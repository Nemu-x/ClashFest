package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.DesyncCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class DesyncDeviceMethodsTest {
    private val perSocket = "decided per socket"

    // Android as the library reports it: fake, disorder and split-wait-sent decided per socket.
    private fun android(probed: DesyncCapabilities.Probe?) = DesyncCapabilities(
        os = "android",
        fakeTcp = false,
        fakeUdp = true,
        ttlControl = true,
        disorder = DesyncCapabilities.Disorder(supported = false, mechanism = "", observed = false, reliable = false),
        oob = true,
        ack = DesyncCapabilities.Ack(mechanism = "", sent = false, acked = true, reliable = true),
        autoTtl = DesyncCapabilities.AutoTtl(mechanism = "", ipv4 = false, ipv6 = true),
        unsupported = mapOf(
            "fake-tcp" to perSocket,
            "disorder" to perSocket,
            "split-wait-sent" to perSocket,
            "auto-ttl-ipv4" to "no received TTL",
            "quantum-split" to "a method of a newer library",
        ),
        conditional = mapOf("fake-tcp" to perSocket, "disorder" to perSocket, "split-wait-sent" to perSocket),
        probed = probed,
    )

    private fun states(caps: DesyncCapabilities) = DesyncDeviceMethods.of(caps).associate { it.key to it.state }

    @Test
    fun probe_confirmed_methods_are_checked() {
        val probe = DesyncCapabilities.Probe(
            fakeTcp = true,
            disorder = DesyncCapabilities.Disorder(supported = true, mechanism = "TCP_INFO", observed = true, reliable = true),
            ack = DesyncCapabilities.Ack(mechanism = "TCP_INFO", sent = true, acked = true, reliable = true),
        )
        val s = states(android(probe))
        assertEquals(DesyncMethodLine.State.Checked, s["fake-tcp"])
        assertEquals(DesyncMethodLine.State.Checked, s["disorder"])
        assertEquals(DesyncMethodLine.State.Checked, s["split-wait-sent"])
        assertEquals(DesyncMethodLine.State.Available, s["ttl"])
        assertEquals(DesyncMethodLine.State.Available, s["oob"])
        assertEquals(DesyncMethodLine.State.Available, s["auto-ttl-ipv6"])
        assertEquals(DesyncMethodLine.State.Unavailable, s["auto-ttl-ipv4"])
        assertEquals(DesyncMethodLine.State.Unavailable, s["quantum-split"])
    }

    @Test
    fun probe_refused_methods_are_unavailable_with_its_reason() {
        val probe = DesyncCapabilities.Probe(
            fakeTcp = false,
            disorder = DesyncCapabilities.Disorder(supported = true, mechanism = "TCP_INFO", observed = true, reliable = true),
            ack = DesyncCapabilities.Ack(mechanism = "", sent = false, acked = true, reliable = true),
            unsupported = mapOf("fake-tcp" to "no tcpi_notsent_bytes"),
        )
        val lines = DesyncDeviceMethods.of(android(probe)).associateBy { it.key }
        assertEquals(DesyncMethodLine(key = "fake-tcp", state = DesyncMethodLine.State.Unavailable, reason = "no tcpi_notsent_bytes"), lines["fake-tcp"])
        assertEquals(DesyncMethodLine.State.Checked, lines["disorder"]?.state)
        assertEquals(DesyncMethodLine(key = "split-wait-sent", state = DesyncMethodLine.State.Unavailable, reason = perSocket), lines["split-wait-sent"])
    }

    @Test
    fun without_a_probe_conditional_methods_are_per_connection() {
        for (probe in listOf(null, DesyncCapabilities.Probe(error = "loopback refused"))) {
            val s = states(android(probe))
            assertEquals(DesyncMethodLine.State.PerConnection, s["fake-tcp"])
            assertEquals(DesyncMethodLine.State.PerConnection, s["disorder"])
            assertEquals(DesyncMethodLine.State.PerConnection, s["split-wait-sent"])
        }
    }

    @Test
    fun known_methods_come_first_in_display_order() {
        val keys = DesyncDeviceMethods.of(android(null)).map { it.key }
        assertEquals(DesyncDeviceMethods.keys + "quantum-split", keys)
    }
}
