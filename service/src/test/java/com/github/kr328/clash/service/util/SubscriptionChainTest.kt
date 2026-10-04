package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ProfileSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SubscriptionChainTest {
    private val first = ChainEndpoint("00000000-0000-0000-0000-000000000001", "Same name", "name: Same name\ntype: socks5\nserver: 127.0.0.1\nport: 1080\nudp: true\n")
    private val exit = ChainEndpoint("00000000-0000-0000-0000-000000000002", "Same name", "name: Same name\ntype: socks5\nserver: 127.0.0.2\nport: 1081\n")
    private val chain = SubscriptionChain("00000000-0000-0000-0000-000000000003", first, exit)
    private val base = "# keep me\nproxies: []\nrules:\n  - MATCH,DIRECT\n"

    @Test fun duplicate_names_across_subscriptions_have_separate_aliases_and_correct_direction() {
        val yaml = SubscriptionChainComposer.compose(base, chain)
        val root = MihomoConfigDocument.parseOrThrow(yaml).root
        val nodes = root["proxies"] as List<*>
        val entry = nodes[0] as Map<*, *>
        val out = nodes[1] as Map<*, *>
        assertEquals(chain.firstAlias, entry["name"])
        assertEquals(chain.exitAlias, out["name"])
        assertEquals(chain.firstAlias, out["dialer-proxy"])
        assertFalse(entry.containsKey("dialer-proxy"))
        assertEquals("127.0.0.1", entry["server"])
        assertEquals("127.0.0.2", out["server"])
        assertEquals("global", root["mode"])
        val global = (root["proxy-groups"] as List<*>).last() as Map<*, *>
        assertEquals(listOf(chain.exitAlias), global["proxies"])
        assertTrue(yaml.startsWith("# keep me"))
    }

    @Test fun self_chain_is_rejected() {
        assertFailsWith<IllegalArgumentException> { SubscriptionChainComposer.compose(base, chain.copy(exit = first)) }
    }

    @Test fun generated_chain_is_detectable_when_saved_metadata_is_missing() {
        assertFalse(SubscriptionChainComposer.hasChainAliases(base))
        assertTrue(SubscriptionChainComposer.hasChainAliases(SubscriptionChainComposer.compose(base, chain)))
    }

    @Test fun saving_and_clearing_chain_preserves_other_layer_settings() {
        val dir = Files.createTempDirectory("mikan-chain-store").toFile()
        try {
            val uuid = UUID.randomUUID()
            val store = UserLayerStore(dir)
            val layer = UserLayer(subscriptionChain = chain, dnsHosts = DnsHostsConfig(enable = true))
            store.save(uuid, layer)
            assertEquals(layer, store.load(uuid))
            assertFalse(store.load(uuid).isEmpty())
            store.update(uuid) { it.copy(subscriptionChain = null) }
            assertEquals(true, store.load(uuid).dnsHosts?.enable)
            assertNull(store.load(uuid).subscriptionChain)
        } finally { dir.deleteRecursively() }
    }

    @Test fun replacing_and_removing_chain_preserves_original_servers() {
        val original = "proxies:\n  - {name: original, type: socks5, server: 127.0.0.3, port: 1082}\nrules: ['MATCH,DIRECT']\n"
        val old = SubscriptionChainComposer.compose(original, chain)
        val next = chain.copy(id = "00000000-0000-0000-0000-000000000004")
        val replaced = SubscriptionChainComposer.compose(old, next, chain)
        val cleared = SubscriptionChainComposer.remove(replaced, next)
        val nodes = MihomoConfigDocument.parseOrThrow(cleared).root["proxies"] as List<*>
        assertEquals(1, nodes.size)
        assertEquals("original", (nodes[0] as Map<*, *>)["name"])
    }

    @Test fun missing_subscription_fails_without_using_cached_credentials() {
        val dir = Files.createTempDirectory("mikan-chain-missing").toFile()
        try { assertFailsWith<IllegalArgumentException> { SubscriptionChainComposer.refresh(chain, dir) { ProfileSnapshot() } } }
        finally { dir.deleteRecursively() }
    }

    @Test fun source_update_is_read_again_before_composition() {
        val dir = Files.createTempDirectory("mikan-chain-refresh").toFile()
        try {
            for (endpoint in listOf(first, exit)) File(dir, endpoint.profileId).apply { mkdirs(); File(this, "config.yaml").writeText("proxies: []") }
            val node = Json.parseToJsonElement("""{"name":"Same name","type":"socks5","server":"127.0.0.8","port":1080}""") as JsonObject
            val refreshed = SubscriptionChainComposer.refresh(chain, dir) { ProfileSnapshot(proxies = listOf(node)) }
            assertTrue(refreshed.first.yaml.contains("127.0.0.8"))
            assertFalse(refreshed.first.yaml.contains("127.0.0.1"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun inline_provider_names_and_overrides_are_preserved() {
        val provider = Json.parseToJsonElement("""{"type":"inline","payload":[{"name":"Node","type":"socks5","server":"127.0.0.1","port":1080}],"override":{"additional-prefix":"[A] ","udp":true}}""") as JsonObject
        val nodes = SubscriptionChainComposer.proxies(ProfileSnapshot(proxyProviders = mapOf("p" to provider)), File("."))
        val node = assertNotNull(nodes["[A] Node"])
        assertTrue(node.contains("udp: true"))
    }

    @Test fun chain_with_external_dialer_dependency_is_rejected() {
        assertFailsWith<IllegalArgumentException> { SubscriptionChainComposer.compose(base, chain.copy(first = first.copy(yaml = first.yaml + "dialer-proxy: external\n"))) }
    }

    @Test fun engine_accepts_generated_chain_and_rejects_unknown_hop() {
        val root = listOf(File(".."), File(".")).first { File(it, "core/src/main/golang/go.mod").isFile }.canonicalFile
        val actual = SubscriptionChainComposer.compose(base, chain)
        val expected = "proxies:\n  - {name: '${chain.firstAlias}', type: socks5, server: 127.0.0.1, port: 1080, udp: true}\n  - {name: '${chain.exitAlias}', type: socks5, server: 127.0.0.2, port: 1081, dialer-proxy: '${chain.firstAlias}'}\nproxy-groups:\n  - {name: GLOBAL, type: select, proxies: ['${chain.exitAlias}']}\nmode: global\nrules: ['MATCH,DIRECT']\n"
        val dir = Files.createTempDirectory("mikan-chain-oracle").toFile()
        try {
            File(dir, "expected.yaml").writeText(expected)
            File(dir, "actual.yaml").writeText(actual)
            val invalid = MihomoConfigDocument.parseOrThrow(actual)
            @Suppress("UNCHECKED_CAST")
            val invalidExit = (invalid.root["proxies"] as List<*>)[1] as MutableMap<String, Any?>
            invalidExit["dialer-proxy"] = "missing-hop"
            File(dir, "invalid.yaml").writeText(invalid.renderReplacing("proxies"))
            val output = File(dir, "oracle.log")
            val process = ProcessBuilder("go", "run", "-tags", "foss,with_gvisor,cmfa", File(root, "service/src/test/golang/rule_oracle.go").path, dir.path)
                .directory(File(root, "core/src/main/golang")).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Chain oracle timed out") }
            assertEquals(0, process.exitValue(), output.readText())
        } finally { dir.deleteRecursively() }
    }
}
