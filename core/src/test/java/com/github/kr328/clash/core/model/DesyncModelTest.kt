package com.github.kr328.clash.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON below is what `cfa/native/desync` emits (taken from its Go tests' output), so a shape
 * change on either side fails here instead of at the first JNI decode on a device.
 */
class DesyncModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun capabilities_decode() {
        val caps = json.decodeFromString(
            DesyncCapabilities.serializer(),
            """{"os":"android","fakeTcp":true,"fakeUdp":true,"ttlControl":true,
              |"disorder":{"supported":true,"mechanism":"SIOCOUTQ","observed":true,"reliable":true},
              |"oob":true,"ack":{"mechanism":"SIOCOUTQ/SIOCOUTQNSD","sent":true,"acked":true,"reliable":true},
              |"autoTtl":{"mechanism":"IPV6_RECVHOPLIMIT/IPV6_2292PKTOPTIONS","ipv4":false,"ipv6":true},
              |"unsupported":{"auto-ttl-ipv4":"no TTL"},"libraryVersion":"v0.2.0","static":true,
              |"note":"static"}""".trimMargin(),
        )
        assertEquals("android", caps.os)
        assertTrue(caps.autoTtl.ipv6)
        assertEquals("no TTL", caps.unsupported["auto-ttl-ipv4"])
        assertEquals("v0.2.0", caps.libraryVersion)
        assertTrue(caps.conditional.isEmpty())
        assertNull(caps.probed)
    }

    @Test
    fun capabilities_decode_conditional_and_probe() {
        val caps = json.decodeFromString(
            DesyncCapabilities.serializer(),
            """{"os":"android","fakeTcp":false,"fakeUdp":true,"ttlControl":true,
              |"disorder":{"supported":false,"mechanism":"","observed":false,"reliable":false},
              |"oob":true,"ack":{"mechanism":"SIOCOUTQ","sent":false,"acked":true,"reliable":true},
              |"autoTtl":{"mechanism":"IPV6_RECVHOPLIMIT/IPV6_2292PKTOPTIONS","ipv4":false,"ipv6":true},
              |"unsupported":{"auto-ttl-ipv4":"no TTL","fake-tcp":"per socket","disorder":"per socket","split-wait-sent":"per socket"},
              |"conditional":{"fake-tcp":"per socket","disorder":"per socket","split-wait-sent":"per socket"},
              |"probed":{"fakeTcp":true,"disorder":{"supported":true,"mechanism":"TCP_INFO","observed":true,"reliable":true},
              |"ack":{"mechanism":"SIOCOUTQ/TCP_INFO","sent":true,"acked":true,"reliable":true},
              |"unsupported":{"auto-ttl-ipv4":"no TTL"},"error":""},
              |"libraryVersion":"v0.2.1-0.20261029232908-673cbefddb80","static":true,"note":"static"}""".trimMargin(),
        )
        assertEquals(setOf("fake-tcp", "disorder", "split-wait-sent"), caps.conditional.keys)
        val probed = caps.probed!!
        assertTrue(probed.fakeTcp)
        assertEquals("TCP_INFO", probed.disorder!!.mechanism)
        assertTrue(probed.ack!!.sent)
        assertEquals("", probed.error)
    }

    @Test
    fun catalog_decode_keeps_outbound_keys() {
        val catalog = json.decodeFromString(
            DesyncPresetCatalog.serializer(),
            """{"catalogVersion":"c61e5e9e1265fbbc6","defaultTcp":"s30879a4293",
              |"tcp":[{"id":"s30879a4293","name":"tlsrec-1s","description":"d","rank":10,"options":{"tlsrec":"1+s"}},
              |{"id":"s1","name":"fake-ttl5","description":"d","rank":50,"options":{"fake":{"ttl":5,"prefix":-1}}}],
              |"udp":[{"id":"u1","name":"udp-zero-ttl3","description":"d","rank":20,"options":{"udp":{"ttl":3}}}],
              |"skipped":[{"kind":"tcp","name":"oob-1","reason":"oob: not implemented"}]}""".trimMargin(),
        )
        assertEquals(catalog.defaultTcp, catalog.tcp.first().id)
        assertEquals("1+s", catalog.tcp[0].options["tlsrec"]!!.jsonPrimitive.content)
        assertEquals("5", catalog.tcp[1].options["fake"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
        assertEquals("3", catalog.udp[0].options["udp"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
        assertEquals("oob-1", catalog.skipped.single().name)
    }

    @Test
    fun status_decode() {
        val status = json.decodeFromString(
            DesyncStatus.serializer(),
            """{"proxies":[{"name":"desync-youtube","alive":true,
              |"desync":{"strategy":"static","tcp":{"tlsrec":["1+s"],"split":[],"fake":null},
              |"udp":{"ttl":3,"counters":{"packets":4,"fakePackets":18,"fakeErrors":0,"peers":1}},
              |"dns-fallback":false,"reject-quic":true},
              |"udpDiagnostics":{"ttl":3}},
              |{"name":"p","provider":"sub","alive":false,"desync":{"strategy":"static"},"dnsFallbacks":2}]}""".trimMargin(),
        )
        val yt = status.proxies[0]
        assertEquals("", yt.provider)
        assertEquals("1+s", yt.desync["tcp"]!!.jsonObject["tlsrec"]!!.jsonArray[0].jsonPrimitive.content)
        assertNull(yt.dnsFallbacks)
        assertEquals(2L, status.proxies[1].dnsFallbacks)
        assertEquals("sub", status.proxies[1].provider)
    }

    @Test
    fun request_encodes_without_unset_fields() {
        val encoded = json.encodeToString(
            DesyncConfigRequest.serializer(),
            DesyncConfigRequest(services = listOf("youtube", "discord")),
        )
        assertEquals("""{"services":["youtube","discord"]}""", encoded)
        val withQuic = json.encodeToString(
            DesyncConfigRequest.serializer(),
            DesyncConfigRequest(services = listOf("discord"), strategyPresetId = "s1", rejectQuic = false),
        )
        assertEquals("""{"services":["discord"],"strategyPresetId":"s1","rejectQuic":false}""", withQuic)
    }

    @Test
    fun result_decode() {
        val ok = json.decodeFromString(DesyncGeneratedConfig.serializer(), """{"yaml":"mode: rule\n"}""")
        assertEquals("mode: rule\n", ok.yaml)
        assertNull(ok.error)
        val bad = json.decodeFromString(DesyncGeneratedConfig.serializer(), """{"error":"unknown service \"x\""}""")
        assertNull(bad.yaml)
    }
}
