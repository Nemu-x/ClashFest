package com.github.kr328.clash.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandalonePingTest {
    @Test
    fun quotedServerAndPortAreAccepted() {
        assertEquals("node.example" to 8443, StandalonePing.parseServerPortFromProxyYaml(
            "server: 'node.example'\nport: \"8443\"\ntype: vless"
        ))
    }

    @Test
    fun invalidPortIsNotReplacedWithAWorkingDefault() {
        for (port in listOf("0", "65536", "-1", "garbage", "443suffix")) {
            assertNull(StandalonePing.parseServerPortFromProxyYaml("server: node.example\nport: $port"))
        }
    }

    @Test
    fun missingPortKeepsLegacyDefault() {
        assertEquals("node.example" to 443, StandalonePing.parseServerPortFromProxyYaml("server: node.example"))
    }

    @Test
    fun emptyFieldsCannotConsumeTheNextYamlLine() {
        assertNull(StandalonePing.parseServerPortFromProxyYaml("server:\nport: 443"))
        assertNull(StandalonePing.parseServerPortFromProxyYaml("server: node.example\nport:\ntype: vless"))
        assertNull(StandalonePing.parseServerPortFromProxyYaml("server: ''\nport: 443"))
    }

    @Test
    fun yamlCommentsDoNotBecomePartOfEndpoint() {
        assertEquals("node.example" to 443, StandalonePing.parseServerPortFromProxyYaml(
            "server: node.example # endpoint\nport: 443 # TLS"
        ))
    }

    @Test
    fun tcpTestDoesNotClaimToTestUdpProtocols() {
        for (type in listOf("hysteria", "hysteria2", "tuic", "wireguard")) {
            assertFalse(StandalonePing.supportsTcpProbe("type: '$type'\nserver: node.example\nport: 443"))
        }
        assertTrue(StandalonePing.supportsTcpProbe("type: vless\nserver: node.example\nport: 443"))
    }
}
