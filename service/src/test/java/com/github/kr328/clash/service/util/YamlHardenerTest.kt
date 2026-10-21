package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.ProxyHardeningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class YamlHardenerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun parse(yaml: String): Map<String, Any?> {
        return YamlFormatting.parseRootMap(yaml) ?: error("not a YAML map")
    }

    // -- in-memory hardenYaml ---------------------------------------------

    @Test
    fun strict_dropsListenersBlock() {
        val input = """
            listeners:
              - name: leaky
                type: socks
                listen: 0.0.0.0
                port: 1080
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        val root = parse(hardened!!)
        assertNull("Strict must remove the entire listeners block", root["listeners"])
    }

    @Test
    fun strict_dropsQuotedListenersBlock() {
        val input = """
            "listeners":
              - name: leaky
                type: socks
                listen: 0.0.0.0
                port: 1080
            'proxies': []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        val root = parse(hardened!!)
        assertNull("Strict must remove quoted listeners blocks", root["listeners"])
        assertFalse("Strict must strip the quoted listeners source text", hardened.contains("listen: 0.0.0.0"))
        assertTrue("following quoted top-level keys must survive", root.containsKey("proxies"))
    }

    @Test
    fun compat_rebindsListenersToLoopback() {
        val input = """
            listeners:
              - name: lan-socks
                type: socks
                listen: 0.0.0.0
                port: 1080
              - name: ipv6-any
                type: http
                listen: '[::]:8080'
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Compat)
        assertNotNull(hardened)
        val root = parse(hardened!!)
        val listeners = root["listeners"] as? List<*> ?: error("listeners must survive Compat")
        assertEquals("Compat keeps every listener, just rewrites listen", 2, listeners.size)
        val first = listeners[0] as Map<*, *>
        assertEquals("127.0.0.1", first["listen"])
        // Port and other fields untouched.
        assertEquals(1080, first["port"])
        assertEquals("lan-socks", first["name"])
    }

    @Test
    fun off_isFullNoop() {
        val input = """
            listeners:
              - name: leaky
                type: socks
                listen: 0.0.0.0
            allow-lan: true
            external-controller: 0.0.0.0:9090
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Off)
        // Off must return the original text, no parse, no transform.
        assertEquals(input, hardened)
    }

    @Test
    fun strict_forcesAllowLanFalseWhenExplicitlyTrue() {
        val input = """
            allow-lan: true
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        assertEquals(false, parse(hardened!!)["allow-lan"])
    }

    @Test
    fun strict_leavesAbsentAllowLanAlone() {
        // mihomo defaults allow-lan to false when absent. Touching the file
        // just to write the same default would churn the signature cache
        // and produce a misleading "modified" event for the user.
        val input = """
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertEquals(input, hardened)
    }

    @Test
    fun strict_rewritesExternalControllerHostToLoopback() {
        val input = """
            external-controller: 0.0.0.0:9090
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        assertEquals("127.0.0.1:9090", parse(hardened!!)["external-controller"])
    }

    @Test
    fun strict_rewritesExternalControllerBarePort() {
        val input = """
            external-controller: ':9090'
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        assertEquals("127.0.0.1:9090", parse(hardened!!)["external-controller"])
    }

    @Test
    fun strict_keepsLoopbackExternalControllerUntouched() {
        val input = """
            external-controller: 127.0.0.1:9090
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertEquals(input, hardened)
    }

    @Test
    fun strict_rebindsDnsListenToLoopback() {
        for (unsafe in listOf("0.0.0.0:53", "[::]:53", ":1053", "192.168.1.10:53")) {
            val input = "dns:\n  enable: true\n  listen: '$unsafe'\nproxies: []\n"
            val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
            assertNotNull("$unsafe must be hardened", hardened)
            val dns = parse(hardened!!)["dns"] as Map<*, *>
            val expectedPort = if (unsafe.endsWith("1053")) 1053 else 53
            assertEquals("127.0.0.1:$expectedPort", dns["listen"])
        }
    }

    @Test
    fun strict_keepsLoopbackDnsListenUntouched() {
        val input = "dns:\n  enable: true\n  listen: '127.0.0.1:1053'\nproxies: []\n"
        assertEquals(input, YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict))
    }

    @Test
    fun strict_removesMalformedDnsListen() {
        val input = "dns:\n  enable: true\n  listen: '127.0.0.1'\nproxies: []\n"
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        val dns = parse(hardened!!)["dns"] as Map<*, *>
        assertFalse(dns.containsKey("listen"))
        assertEquals(true, dns["enable"])
    }

    @Test
    fun off_keepsUnsafeDnsListen() {
        val input = "dns:\n  enable: true\n  listen: '0.0.0.0:53'\nproxies: []\n"
        assertEquals(input, YamlHardener.hardenYaml(input, ProxyHardeningMode.Off))
    }

    @Test
    fun dnsListenHardeningIsIdempotent() {
        val input = "dns:\n  enable: true\n  listen: '[::]:53'\nproxies: []\n"
        val first = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(first)
        assertEquals(first, YamlHardener.hardenYaml(first!!, ProxyHardeningMode.Strict))
    }

    @Test
    fun strict_rewritesBindAddressToLoopback() {
        val input = """
            bind-address: '*'
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        assertEquals("127.0.0.1", parse(hardened!!)["bind-address"])
    }

    // -- SEC-1: global proxy ports stripped in Strict ---------------------

    @Test
    fun strict_stripsAllGlobalProxyPorts() {
        val input = """
            port: 7892
            socks-port: 7891
            mixed-port: 7890
            redir-port: 7893
            tproxy-port: 7894
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        val root = parse(hardened!!)
        for (key in listOf("port", "socks-port", "mixed-port", "redir-port", "tproxy-port")) {
            assertNull("Strict must strip $key from the model", root[key])
            assertFalse("Strict must strip $key from the source text", hardened.contains(key))
        }
        // unrelated keys survive
        assertTrue(root.containsKey("proxies"))
    }

    @Test
    fun strict_stripsQuotedGlobalProxyPorts() {
        val input = """
            "mixed-port": 7890
            'socks-port': 7891
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(hardened)
        val root = parse(hardened!!)
        assertNull(root["mixed-port"])
        assertNull(root["socks-port"])
        assertFalse(hardened.contains("mixed-port"))
        assertFalse(hardened.contains("socks-port"))
    }

    @Test
    fun strict_portStripIsIdempotent() {
        val input = "mixed-port: 7890\nproxies: []\n"
        val first = YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict)
        assertNotNull(first)
        assertFalse(first!!.contains("mixed-port"))
        val second = YamlHardener.hardenYaml(first, ProxyHardeningMode.Strict)
        assertEquals("second pass on already-stripped YAML must be a no-op", first, second)
    }

    @Test
    fun compat_keepsGlobalProxyPorts() {
        val input = "mixed-port: 7890\nsocks-port: 7891\nproxies: []\n"
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Compat)
        // Compat must not strip global ports (loopback bind + RuntimeSocksAuth).
        assertEquals(7890, parse(hardened!!)["mixed-port"])
        assertEquals(7891, parse(hardened)["socks-port"])
    }

    @Test
    fun off_keepsGlobalProxyPorts() {
        val input = "mixed-port: 7890\nproxies: []\n"
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Off)
        assertEquals(input, hardened)
    }

    @Test
    fun isIdempotent_secondPassNoChange() {
        val dirty = """
            listeners:
              - name: leaky
                type: socks
                listen: 0.0.0.0
                port: 1080
            allow-lan: true
            external-controller: 0.0.0.0:9090
        """.trimIndent()
        val first = YamlHardener.hardenYaml(dirty, ProxyHardeningMode.Strict)
        assertNotNull(first)
        val second = YamlHardener.hardenYaml(first!!, ProxyHardeningMode.Strict)
        assertEquals("re-running hardener on clean YAML must be a no-op", first, second)
    }

    @Test
    fun returnsNullOnUnparseableInput() {
        assertNull(YamlHardener.hardenYaml("not a map - just a string", ProxyHardeningMode.Strict))
    }

    // -- on-disk hardenProfile + config.original.yaml ---------------------

    @Test
    fun hardenProfile_savesOriginalOnceAndRewritesConfig() {
        val profile = tmp.newFolder("profile-1")
        val original = """
            listeners:
              - name: leaky
                type: socks
                listen: 0.0.0.0
            proxies: []
        """.trimIndent()
        File(profile, "config.yaml").writeText(original)

        val changed = YamlHardener.hardenProfile(profile, ProxyHardeningMode.Strict)
        assertTrue("first run must report a change", changed)

        val originalFile = File(profile, "config.original.yaml")
        assertTrue("config.original.yaml must be created", originalFile.isFile)
        assertEquals(original, originalFile.readText())

        val cfgText = File(profile, "config.yaml").readText()
        assertFalse("listeners must be stripped on disk", cfgText.contains("listen: 0.0.0.0"))
    }

    @Test
    fun hardenProfile_keepsOriginalAcrossSubsequentRuns() {
        // First import saves original. A later subscription update writes a
        // fresh subscription YAML; YamlHardener must not overwrite the
        // pre-existing original.yaml — the user's first snapshot of the
        // subscription is the audit trail we never want to clobber.
        val profile = tmp.newFolder("profile-2")
        val firstImport = "listeners:\n  - name: a\n    listen: 0.0.0.0\n"
        File(profile, "config.yaml").writeText(firstImport)
        YamlHardener.hardenProfile(profile, ProxyHardeningMode.Strict)
        val originalAfterFirst = File(profile, "config.original.yaml").readText()
        assertEquals(firstImport, originalAfterFirst)

        // Simulate refresh: fresh subscription replaces config.yaml.
        val secondImport = "listeners:\n  - name: b\n    listen: 0.0.0.0\n"
        File(profile, "config.yaml").writeText(secondImport)
        YamlHardener.hardenProfile(profile, ProxyHardeningMode.Strict)
        assertEquals(
            "original.yaml must reflect the very first import, not later refreshes",
            firstImport,
            File(profile, "config.original.yaml").readText(),
        )
    }

    @Test
    fun hardenProfile_offModeIsNoOp() {
        val profile = tmp.newFolder("profile-off")
        val original = "listeners:\n  - name: leaky\n    listen: 0.0.0.0\n"
        File(profile, "config.yaml").writeText(original)

        val changed = YamlHardener.hardenProfile(profile, ProxyHardeningMode.Off)
        assertFalse("Off must not rewrite the file", changed)
        assertFalse(
            "Off must not create the original snapshot — there's nothing to back up",
            File(profile, "config.original.yaml").isFile,
        )
        assertEquals(original, File(profile, "config.yaml").readText())
    }
    // -- XDrive -----------------------------------------------------------

    private val xdriveProfile = """
        listeners:
          - name: drive-in
            type: vless
            port: 0
            xdrive-config:
              service: google-drive
              remote-folder: folder
          - name: lan-socks
            type: socks
            listen: 0.0.0.0
            port: 1080
        proxies:
          - name: local-node
            type: vless
            server: 127.0.0.1
            port: 443
            uuid: 27848739-7e62-4138-9fd3-098a63964b6b
            network: xdrive
            encryption: mlkem768x25519plus.native.0rtt.key
            xdrive-opts:
              service: local
              remote-folder: /sdcard/Download
          - name: cloud-node
            type: vless
            server: www.googleapis.com
            port: 443
            uuid: 27848739-7e62-4138-9fd3-098a63964b6b
            network: xdrive
            encryption: mlkem768x25519plus.native.0rtt.key
            xdrive-opts:
              service: google-drive
              remote-folder: folder
              secrets: [a, b, c]
        proxy-providers:
          inline:
            type: inline
            payload:
              - name: provider-local
                type: vless
                server: 127.0.0.1
                port: 443
                uuid: 27848739-7e62-4138-9fd3-098a63964b6b
                network: xdrive
                xdrive-opts:
                  service: local
                  remote-folder: /data/local/tmp
        proxy-groups:
          - name: G
            type: select
            proxies: [local-node, cloud-node]
    """.trimIndent()

    @Test
    fun strict_dropsXDriveListenersWithTheWholeBlock() {
        val root = parse(YamlHardener.hardenYaml(xdriveProfile, ProxyHardeningMode.Strict)!!)
        assertNull(root["listeners"])
    }

    @Test
    fun compat_removesXDriveListenersAndRebindsTheRest() {
        val hardened = YamlHardener.hardenYaml(xdriveProfile, ProxyHardeningMode.Compat)!!
        val listeners = parse(hardened)["listeners"] as List<*>
        assertEquals(1, listeners.size)
        val kept = listeners[0] as Map<*, *>
        assertEquals("lan-socks", kept["name"])
        assertEquals("127.0.0.1", kept["listen"])
        assertFalse(hardened.contains("xdrive-config"))
    }

    @Test
    fun compat_dropsListenersBlockWhenOnlyXDriveListenersWereThere() {
        val input = """
            listeners:
              - name: drive-in
                type: vless
                listen: 127.0.0.1
                xdrive-config:
                  service: local
                  remote-folder: /tmp/x
            proxies: []
        """.trimIndent()
        val hardened = YamlHardener.hardenYaml(input, ProxyHardeningMode.Compat)!!
        val root = parse(hardened)
        assertNull(root["listeners"])
        assertFalse(hardened.contains("xdrive-config"))
        assertTrue(root.containsKey("proxies"))
    }

    @Test
    fun strictAndCompat_turnLocalXDriveProxiesIntoReject() {
        for (mode in listOf(ProxyHardeningMode.Strict, ProxyHardeningMode.Compat)) {
            val hardened = YamlHardener.hardenYaml(xdriveProfile, mode)!!
            val root = parse(hardened)
            val proxies = root["proxies"] as List<*>
            assertEquals("$mode", mapOf("name" to "local-node", "type" to "reject"), proxies[0])
            val cloud = proxies[1] as Map<*, *>
            assertEquals("$mode keeps cloud xdrive nodes", "xdrive", cloud["network"])
            val payload = ((root["proxy-providers"] as Map<*, *>)["inline"] as Map<*, *>)["payload"] as List<*>
            assertEquals("$mode", mapOf("name" to "provider-local", "type" to "reject"), payload[0])
            assertEquals("inline", ((root["proxy-providers"] as Map<*, *>)["inline"] as Map<*, *>)["type"])
            assertFalse("$mode", hardened.contains("/sdcard/Download"))
            assertFalse("$mode", hardened.contains("/data/local/tmp"))
            // The group keeps resolving: the node name survives.
            val group = (root["proxy-groups"] as List<*>)[0] as Map<*, *>
            assertEquals(listOf("local-node", "cloud-node"), group["proxies"])
        }
    }

    @Test
    fun strictAndCompat_neutralizeLocalXDriveUnderCapitalizedProviderPayloadKey() {
        // The provider decoder matches keys case-insensitively, so `Payload:` loads too.
        val input = """
            proxy-providers:
              caps:
                Type: inline
                Payload:
                  - name: provider-caps
                    type: vless
                    server: 127.0.0.1
                    port: 443
                    uuid: 27848739-7e62-4138-9fd3-098a63964b6b
                    network: xdrive
                    xdrive-opts:
                      service: local
                      remote-folder: /data/local/tmp
        """.trimIndent()
        for (mode in listOf(ProxyHardeningMode.Strict, ProxyHardeningMode.Compat)) {
            val hardened = YamlHardener.hardenYaml(input, mode)!!
            val provider = (parse(hardened)["proxy-providers"] as Map<*, *>)["caps"] as Map<*, *>
            assertEquals("$mode", listOf(mapOf("name" to "provider-caps", "type" to "reject")), provider["Payload"])
            assertEquals("inline", provider["Type"])
            assertFalse("$mode", hardened.contains("/data/local/tmp"))
        }
    }

    @Test
    fun off_keepsXDriveUntouched() {
        assertEquals(xdriveProfile, YamlHardener.hardenYaml(xdriveProfile, ProxyHardeningMode.Off))
    }

    @Test
    fun cloudXDriveOnlyProfileIsNotRewritten() {
        val input = """
            proxies:
              - name: cloud-node
                type: vless
                network: xdrive
                xdrive-opts:
                  service: google-drive
                  remote-folder: folder
        """.trimIndent()
        assertEquals(input, YamlHardener.hardenYaml(input, ProxyHardeningMode.Strict))
        assertEquals(input, YamlHardener.hardenYaml(input, ProxyHardeningMode.Compat))
    }

    @Test
    fun xdriveHardeningIsIdempotent() {
        for (mode in listOf(ProxyHardeningMode.Strict, ProxyHardeningMode.Compat)) {
            val once = YamlHardener.hardenYaml(xdriveProfile, mode)!!
            assertEquals("$mode", once, YamlHardener.hardenYaml(once, mode))
        }
    }

    @Test
    fun strictAndCompat_matchXDriveKeysTheWayTheEngineDecodesThem() {
        // mihomo's decoder matches keys case-insensitively with `_` read as `-`.
        val input = """
            listeners:
              - name: drive-in
                type: socks
                listen: 0.0.0.0
                XDrive_Config:
                  service: local
            proxies:
              - name: snake-node
                type: vless
                network: xdrive
                xdrive_opts:
                  service: local
                  remote-folder: /sdcard/Download
              - Name: caps-node
                type: vless
                Network: xdrive
                XDrive-Opts:
                  Service: local
                  remote_folder: /sdcard/Download
        """.trimIndent()
        for (mode in listOf(ProxyHardeningMode.Strict, ProxyHardeningMode.Compat)) {
            val hardened = YamlHardener.hardenYaml(input, mode)!!
            val root = parse(hardened)
            assertNull("$mode", root["listeners"])
            assertEquals(
                "$mode",
                listOf(
                    mapOf("name" to "snake-node", "type" to "reject"),
                    mapOf("name" to "caps-node", "type" to "reject"),
                ),
                root["proxies"],
            )
            assertFalse("$mode", hardened.contains("/sdcard/Download"))
        }
    }
}
