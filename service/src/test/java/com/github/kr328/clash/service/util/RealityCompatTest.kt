package com.github.kr328.clash.service.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RealityCompatTest {
    // 32 zero bytes, base64url without padding — shape-valid for RealityOptions.Parse.
    private val pub = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    private val base = """
        # keep me
        mixed-port: 7890
        proxies:
          - name: r1
            type: vless
            server: 1.2.3.4
            port: 443
            uuid: 9b0e8a1e-1b2c-4d3e-8f90-123456789abc
            tls: true
            servername: www.example.com
            reality-opts:
              public-key: $pub
              short-id: 0123abcd
          - name: r2
            type: trojan
            server: 1.2.3.5
            port: 443
            password: pw
            client-fingerprint: firefox
            reality-opts:
              public-key: $pub
          - name: ok
            type: vless
            server: 1.2.3.6
            port: 443
            uuid: 9b0e8a1e-1b2c-4d3e-8f90-123456789abc
            tls: true
            client-fingerprint: chrome
            reality-opts:
              public-key: $pub
              support-x25519mlkem768: true
          - name: plain
            type: vless
            server: 1.2.3.7
            port: 443
            uuid: 9b0e8a1e-1b2c-4d3e-8f90-123456789abc
            tls: true
            client-fingerprint: firefox
          - name: ss
            type: ss
            server: 1.2.3.8
            port: 8388
            cipher: aes-128-gcm
            password: x
        proxy-groups:
          - name: G
            type: select
            proxies: [r1, r2, ok, plain, ss]
        rules:
          - MATCH,G
    """.trimIndent() + "\n"

    @Suppress("UNCHECKED_CAST")
    private fun proxies(yaml: String): Map<String, Map<String, Any?>> =
        (YamlFormatting.parseRootMap(yaml)!!["proxies"] as List<Map<String, Any?>>).associateBy { it["name"].toString() }

    @Test
    fun enablesMlkemAndPinsChromeOnRealityProxies() {
        val out = RealityCompat.applyToText(base)!!
        val p = proxies(out)
        // missing fingerprint -> chrome; flag on
        assertEquals("chrome", p["r1"]!!["client-fingerprint"])
        assertEquals(true, (p["r1"]!!["reality-opts"] as Map<*, *>)["support-x25519mlkem768"])
        assertEquals("0123abcd", (p["r1"]!!["reality-opts"] as Map<*, *>)["short-id"])
        // firefox (no ML-KEM in utls 1.8.8) -> chrome; flag on
        assertEquals("chrome", p["r2"]!!["client-fingerprint"])
        assertEquals(true, (p["r2"]!!["reality-opts"] as Map<*, *>)["support-x25519mlkem768"])
    }

    @Test
    fun leavesNonRealityAndAlreadyCompatibleProxiesAlone() {
        val out = RealityCompat.applyToText(base)!!
        val p = proxies(out)
        assertEquals("firefox", p["plain"]!!["client-fingerprint"]) // TLS without REALITY: untouched
        assertFalse(p["plain"]!!.containsKey("reality-opts"))
        assertFalse(p["ss"]!!.containsKey("client-fingerprint"))
        assertEquals("chrome", p["ok"]!!["client-fingerprint"])
    }

    @Test
    fun onlyProxiesBlockIsRerendered() {
        val out = RealityCompat.applyToText(base)!!
        assertTrue(out.startsWith("# keep me\nmixed-port: 7890\n"))
        assertTrue(out.contains("proxies: [r1, r2, ok, plain, ss]")) // flow style outside proxies kept
        assertTrue(out.contains("- MATCH,G"))
    }

    @Test
    fun idempotentAndNoopWhenNothingToDo() {
        val first = RealityCompat.applyToText(base)!!
        val second = RealityCompat.applyToText(first)!!
        assertSame(first, second) // returns the same instance when unchanged
        val noReality = "proxies:\n  - {name: a, type: ss, server: 1.1.1.1, port: 1, cipher: aes-128-gcm, password: x}\n"
        assertSame(noReality, RealityCompat.applyToText(noReality))
    }

    @Test
    fun realityOptsWithoutPublicKeyIsNotReality() {
        val yaml = "proxies:\n  - name: a\n    type: vless\n    server: 1.1.1.1\n    port: 1\n    uuid: 9b0e8a1e-1b2c-4d3e-8f90-123456789abc\n    reality-opts: {}\n"
        assertSame(yaml, RealityCompat.applyToText(yaml))
    }

    @Test
    fun nullOnNonMapInput() {
        assertNull(RealityCompat.applyToText("- just\n- a list\n"))
    }
}
