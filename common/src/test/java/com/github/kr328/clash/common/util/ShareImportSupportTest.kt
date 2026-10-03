package com.github.kr328.clash.common.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareImportSupportTest {
    private val vless = "vless://00000000-0000-0000-0000-000000000001@vpn.example:443#Mikan"

    @Test
    fun acceptsShareLinksAndLists() {
        for (source in listOf(vless, vless.uppercase(), "  $vless\r\n\n$vless  ", "$vless\nmierus://user:password@vpn.example?port=443&protocol=TCP")) {
            assertTrue(ShareImportSupport.isAllowedUrlProfileSource(source))
        }
    }

    @Test
    fun preservesExistingImportSources() {
        for (source in listOf("https://vpn.example/sub", "http://vpn.example/sub", "content://profile", "mierus://vpn.example")) {
            assertTrue(ShareImportSupport.isAllowedUrlProfileSource(source))
        }
    }

    @Test
    fun rejectsUnrecognizedPayloads() {
        for (source in listOf("", "\n  ", "configuration error", "$vless\nnot a link", "vmess://unsupported", "proxies:\n  - type: vless")) {
            assertFalse(ShareImportSupport.isAllowedUrlProfileSource(source))
        }
    }
}
