package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.ProxyHardeningMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class MihomoConfigDocumentFlowTest {
    private val profile = """{"mixed-port":7890,"allow-lan":true,"proxies":[{"name":"on","type":"socks5","server":"127.0.0.1","port":1080,"username":"yes","password":"0123"}],"proxy-groups":[{"name":"VPN","type":"select","proxies":["on"]}],"rules":["MATCH,VPN"],"dns":{"enable":true,"nameserver":["https://1.1.1.1/dns-query"]},"geox-url":{"geoip":"https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat"}}"""

    @Test
    fun replacingJsonBlockPreservesUnrelatedSections() {
        val document = MihomoConfigDocument.parseOrThrow(profile)
        document.root["allow-lan"] = false
        val expected = LinkedHashMap(document.root)

        val rendered = document.renderReplacing("allow-lan")

        assertEquals(expected, MihomoConfigDocument.parseOrThrow(rendered).root)
    }

    @Test
    fun removingJsonBlockPreservesProxyCredentialsAndGroups() {
        val document = MihomoConfigDocument.parseOrThrow(profile)
        val expected = LinkedHashMap(document.root).apply { remove("mixed-port") }

        val rendered = document.renderRemoving("mixed-port")

        assertEquals(expected, MihomoConfigDocument.parseOrThrow(rendered).root)
    }

    @Test
    fun importSanitizersPreserveJsonProxyProfile() {
        val original = MihomoConfigDocument.parseOrThrow(profile).root
        val sanitized = assertNotNull(GeoUrlSanitizer.sanitizeYaml(profile))
        val hardened = assertNotNull(YamlHardener.hardenYaml(sanitized, ProxyHardeningMode.Strict))
        val root = MihomoConfigDocument.parseOrThrow(hardened).root

        for (key in listOf("proxies", "proxy-groups", "rules", "dns")) {
            assertEquals(original[key], root[key], "lost $key during import")
        }
        assertEquals(false, root["allow-lan"])
        assertEquals(false, root["geo-auto-update"])
        assertFalse(root.containsKey("mixed-port"))
    }

    @Test
    fun strictHardeningDoesNotEmptyJsonProfile() {
        val hardened = assertNotNull(YamlHardener.hardenYaml(profile, ProxyHardeningMode.Strict))
        val root = MihomoConfigDocument.parseOrThrow(hardened).root

        assertEquals(MihomoConfigDocument.parseOrThrow(profile).root["proxies"], root["proxies"])
        assertFalse(root.containsKey("mixed-port"))
        assertEquals(false, root["allow-lan"])
    }

    @Test
    fun flowYamlWithPreamblePreservesUnrelatedBlocks() {
        val flow = "# subscription\n---\n{mixed-port: 7890, proxies: [{name: on, type: socks5, server: 127.0.0.1, port: 1080}], rules: [MATCH,DIRECT]}\n"
        val document = MihomoConfigDocument.parseOrThrow(flow)
        document.root["mixed-port"] = 0

        assertEquals(document.root, MihomoConfigDocument.parseOrThrow(document.renderReplacing("mixed-port")).root)
    }
}
