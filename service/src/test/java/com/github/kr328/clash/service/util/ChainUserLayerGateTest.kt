package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.ProxyHardeningMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A subscription `type: chain` group (core patch 0005) wires its hops with dialer-proxy when the
 * engine loads the config. The user's proxy-chain edit ([UserLayer.proxyChain]) writes
 * dialer-proxy too, so a user dialer on a hop the chain must wire is a conflict the engine
 * rejects. The runtime engine gate must then apply the clean subscription and name the edit.
 *
 * Engine half: `chain_user_layer_oracle_test.go` in native/snapshot validates the fixtures written
 * here with the real engine and checks that the rejection is exactly [ENGINE_ERROR_FILE].
 */
class ChainUserLayerGateTest {
    private val geo = GeoDataUrls(geoIp = "", geoSite = "", mmdb = "", asn = "")

    private val subscription = """
        proxies:
          - {name: entry, type: socks5, server: 127.0.0.1, port: 1081}
          - {name: mid, type: socks5, server: 127.0.0.1, port: 1082}
          - {name: exit, type: ss, server: 127.0.0.1, port: 1083, cipher: aes-128-gcm, password: pass}
          - {name: home, type: socks5, server: 127.0.0.1, port: 1085}
        proxy-groups:
          - name: cascade
            type: chain
            proxies: [entry, mid, exit]
          - name: G
            type: select
            proxies: [cascade, home]
        rules:
          - MATCH,G
    """.trimIndent() + "\n"

    // The user routes `exit` through `home`; the chain needs `exit` to go through `mid`.
    private val layer = UserLayer(proxyChain = mapOf("exit" to "home"))

    private fun fixtureDir(): File = listOf(
        "../core/src/main/golang/native/snapshot/testdata/chain",
        "core/src/main/golang/native/snapshot/testdata/chain",
    ).map(::File).first { it.parentFile.parentFile.parentFile.exists() }

    private fun compose() = ConfigComposer.compose(subscription, layer, geo, ProxyHardeningMode.Strict)

    // A user dialer on a proxy that is not a hop: no conflict, the engine accepts it (Go oracle).
    private fun composeUnrelated() = ConfigComposer.compose(
        subscription, UserLayer(proxyChain = mapOf("home" to "entry")), geo, ProxyHardeningMode.Strict,
    )

    @Test
    fun emit_fixtures_for_go_oracle() {
        val composed = compose()
        val exit = (MihomoConfigDocument.parseOrThrow(composed).root["proxies"] as List<*>)
            .map { it as Map<*, *> }
            .first { it["name"] == "exit" }
        assertEquals("home", exit["dialer-proxy"], "user layer must have written its dialer")

        val dir = fixtureDir()
        dir.mkdirs()
        File(dir, "subscription.yaml").writeText(subscription)
        File(dir, "composed.yaml").writeText(composed)
        File(dir, "composed_unrelated.yaml").writeText(composeUnrelated())
    }

    @Test
    fun gate_falls_back_to_subscription_and_names_the_edit() {
        val composed = compose()
        // The engine's verdict on these exact fixtures, certified by the Go oracle.
        val engineError = File(fixtureDir(), ENGINE_ERROR_FILE).readText().trim()
        val engine = { text: String ->
            when (text) {
                composed -> engineError
                subscription -> null
                else -> fail("gate validated a config that is neither the composed nor the fetched one")
            }
        }

        val gate = MergeEngineVerdict.gate(subscription, composed, engine)

        assertEquals(MergeEngineVerdict.MergeIntroduced, gate.verdict)
        assertEquals(subscription, gate.config, "the clean subscription must be applied")
        val reported = assertNotNull(gate.composedError)
        // The report names the proxy the user edited and the dialer the user chose.
        assertTrue(reported.contains("[exit]") && reported.contains("[home]"), reported)
        assertTrue(reported.contains("chain [cascade]"), reported)
    }

    @Test
    fun gate_applies_composed_when_user_edit_does_not_touch_a_hop() {
        val composed = composeUnrelated()
        val gate = MergeEngineVerdict.gate(subscription, composed) { text ->
            if (text == composed) null else fail("an accepted composition must not re-validate the fetched config")
        }
        assertEquals(MergeEngineVerdict.Ok, gate.verdict)
        assertEquals(composed, gate.config)
    }

    private companion object {
        const val ENGINE_ERROR_FILE = "composed.engine-error.txt"
    }
}
