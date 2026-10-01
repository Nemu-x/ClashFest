package com.github.kr328.clash.service.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.github.kr328.clash.service.model.ProxyHardeningMode

/**
 * Layer 2 (producer half) — emits round-tripped fixtures for the engine-oracle
 * Go test in `native/snapshot` (roundtrip_oracle_test.go). For a curated,
 * engine-valid config carrying the dialect-sensitive cases, we write the
 * original plus a per-top-level-block re-dump. The Go side then asserts
 * `snapshot(original) == snapshot(block re-dump)` — the engine certifying our
 * re-serialization preserves semantics.
 *
 * Run order: this test first (writes fixtures), then `go test ./native/snapshot`.
 * The Go oracle skips itself if the fixtures are absent.
 */
class WritePipelineOracleFixtureTest {
    private val curated = """
        mixed-port: 7890
        proxies:
          - name: "🇩🇪 Berlin"
            type: socks5
            server: 127.0.0.1
            port: 1080
            udp: off
          - name: 香港 01
            type: socks5
            server: 127.0.0.2
            port: 1081
            udp: true
        proxy-groups:
          - name: G
            type: select
            proxies:
              - "🇩🇪 Berlin"
              - 香港 01
        rules:
          - MATCH,G
        dns:
          enable: true
          enhanced-mode: fake-ip
          listen: ":1053"
          nameserver:
            - https://1.1.1.1/dns-query
        hosts:
          example.com: 1.2.3.4
    """.trimIndent() + "\n"

    @Test
    fun emit_roundtrip_fixtures_for_go_oracle() {
        val dir = listOf(
            "../core/src/main/golang/native/snapshot/testdata/roundtrip",
            "core/src/main/golang/native/snapshot/testdata/roundtrip",
        ).map(::File).first { it.parentFile.parentFile.parentFile.exists() }
        dir.mkdirs()

        File(dir, "original.yaml").writeText(curated)

        val root = MihomoConfigDocument.parseOrThrow(curated).root
        var n = 0
        for (key in root.keys) {
            val rendered = MihomoConfigDocument.parseOrThrow(curated).renderReplacing(key)
            File(dir, "block_$key.yaml").writeText(rendered)
            n++
        }
        assertTrue(n >= 5, "expected several blocks, emitted $n")
    }

    /**
     * Engine-oracle for the DNS & Hosts writer (AGENTS.md §6): `edited.yaml` is
     * `original.yaml` re-written through [DnsHostsYamlEdit] from a model read the
     * way the editor reads it (snapshot-shaped JSON, list host included). The Go
     * side asserts both snapshot-equal, i.e. mihomo sees the same dns + hosts.
     */
    @Test
    fun emit_dns_hosts_edit_fixture_for_go_oracle() {
        val dir = listOf(
            "../core/src/main/golang/native/snapshot/testdata/dnshosts",
            "core/src/main/golang/native/snapshot/testdata/dnshosts",
        ).map(::File).first { it.parentFile.parentFile.parentFile.exists() }
        dir.mkdirs()

        val original = curated
            .replace("listen: \":1053\"", "listen: \"127.0.0.1:1053\"")
            .replace("hosts:\n  example.com: 1.2.3.4\n", "hosts:\n  example.com: 1.2.3.4\n  multi.example.com: [1.1.1.1, 2.2.2.2]\n")
        val dns = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"enable":true,"enhanced-mode":"fake-ip","listen":"127.0.0.1:1053","nameserver":["https://1.1.1.1/dns-query"]}""",
        ) as kotlinx.serialization.json.JsonObject
        val hosts = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"example.com":"1.2.3.4","multi.example.com":["1.1.1.1","2.2.2.2"]}""",
        ) as kotlinx.serialization.json.JsonObject
        val edited = DnsHostsYamlEdit.render(original, DnsHostsConfig.from(dns, hosts))
        val written = MihomoConfigDocument.parseOrThrow(edited).root["hosts"] as Map<*, *>
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), written["multi.example.com"])

        File(dir, "original.yaml").writeText(original)
        File(dir, "edited.yaml").writeText(edited)
    }

    /** RealityCompat is a config-rewriting transform (AGENTS.md section 6): the engine must accept its output. */
    @Test
    fun emit_reality_mlkem_fixture_for_go_oracle() {
        val dir = listOf(
            "../core/src/main/golang/native/snapshot/testdata/hardening",
            "core/src/main/golang/native/snapshot/testdata/hardening",
        ).map(::File).first { it.parentFile.parentFile.parentFile.exists() }
        dir.mkdirs()

        val pub = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val reality = curated.replace(
            "proxy-groups:",
            """
            |  - name: reality-vless
            |    type: vless
            |    server: 127.0.0.3
            |    port: 443
            |    uuid: 9b0e8a1e-1b2c-4d3e-8f90-123456789abc
            |    tls: true
            |    servername: www.example.com
            |    reality-opts:
            |      public-key: $pub
            |      short-id: 0123abcd
            |  - name: reality-trojan
            |    type: trojan
            |    server: 127.0.0.4
            |    port: 443
            |    password: pw
            |    client-fingerprint: firefox
            |    reality-opts:
            |      public-key: $pub
            |proxy-groups:
            """.trimMargin(),
        )
        val out = RealityCompat.applyToText(reality)
        assertNotNull(out)
        assertTrue(out!!.contains("support-x25519mlkem768: true"))
        File(dir, "reality_mlkem.yaml").writeText(out)
    }

    @Test
    fun emit_hardened_dns_listener_fixture_for_go_oracle() {
        val dir = listOf(
            "../core/src/main/golang/native/snapshot/testdata/hardening",
            "core/src/main/golang/native/snapshot/testdata/hardening",
        ).map(::File).first { it.parentFile.parentFile.parentFile.exists() }
        dir.mkdirs()

        val unsafe = curated.replace("listen: \":1053\"", "listen: \"[::]:1053\"")
        val hardened = YamlHardener.hardenYaml(unsafe, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        val dns = MihomoConfigDocument.parseOrThrow(hardened).root["dns"] as Map<*, *>
        assertEquals("127.0.0.1:1053", dns["listen"])
        File(dir, "dns_listener.yaml").writeText(hardened)
    }
}
