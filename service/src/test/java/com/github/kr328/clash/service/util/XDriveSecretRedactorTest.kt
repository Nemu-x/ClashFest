package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ProfileSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XDriveSecretRedactorTest {
    private val config = """
        # comment kept
        mixed-port: 0
        proxies:
          - name: drive
            type: vless
            server: www.googleapis.com
            port: 443
            uuid: 27848739-7e62-4138-9fd3-098a63964b6b
            network: xdrive
            xdrive-opts:
              service: google-drive
              remote-folder: folder-id
              secrets:
                - client-id-123
                - client-secret-456
                - refresh-token-789
          - name: plain
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: aes-128-gcm
            password: ss-pass
        proxy-providers:
          inline:
            type: inline
            payload:
              - name: tmpl
                type: vless
                network: xdrive
                xdrive-opts:
                  service: template
                  remote-folder: f
                  template:
                    auth:
                      type: static
                      header:
                        Authorization: Bearer static-token-abc
                    get:
                      method: GET
                      url: https://storage.example.com/{name}
        rules:
          - MATCH,drive
    """.trimIndent()

    @Test
    fun redactsSecretsAndTemplateAuthInFullConfig() {
        val out = XDriveSecretRedactor.redactConfigYaml(config)
        for (secret in listOf("client-id-123", "client-secret-456", "refresh-token-789", "static-token-abc")) {
            assertFalse("$secret leaked: $out", out.contains(secret))
        }
        val root = MihomoConfigDocument.parseOrThrow(out).root
        val drive = (root["proxies"] as List<*>)[0] as Map<*, *>
        val opts = drive["xdrive-opts"] as Map<*, *>
        assertEquals(List(3) { XDriveSecretRedactor.REDACTED }, opts["secrets"])
        assertEquals("folder-id", opts["remote-folder"])
        val plain = (root["proxies"] as List<*>)[1] as Map<*, *>
        assertEquals("ss-pass", plain["password"])
        val tmpl = ((((root["proxy-providers"] as Map<*, *>)["inline"] as Map<*, *>)["payload"] as List<*>)[0] as Map<*, *>)
        val template = (tmpl["xdrive-opts"] as Map<*, *>)["template"] as Map<*, *>
        val auth = template["auth"] as Map<*, *>
        assertEquals("static", auth["type"])
        assertEquals(mapOf("Authorization" to XDriveSecretRedactor.REDACTED), auth["header"])
        assertEquals("https://storage.example.com/{name}", (template["get"] as Map<*, *>)["url"])
        assertTrue("untouched blocks keep their source text", out.contains("# comment kept"))
        assertEquals(listOf("MATCH,drive"), root["rules"])
    }

    @Test
    fun configWithoutXDriveIsReturnedVerbatim() {
        val text = "proxies:\n  - {name: a, type: ss, password: p}\n"
        assertSame(text, XDriveSecretRedactor.redactConfigYaml(text))
    }

    @Test
    fun proxyWithoutCredentialsIsReturnedAsIs() {
        val proxy = mapOf("name" to "x", "network" to "xdrive", "xdrive-opts" to mapOf("service" to "template"))
        assertSame(proxy, XDriveSecretRedactor.redactProxy(proxy))
    }

    @Test
    fun proxyYamlPreviewRedactsXDriveSecrets() {
        val entry = Json.parseToJsonElement(
            """{"name":"drive","type":"vless","server":"s","port":443,"network":"xdrive",
               "xdrive-opts":{"service":"google-drive","remote-folder":"f","secrets":["id","the-secret","the-refresh"]}}""",
        ) as JsonObject
        val yaml = ProxyYamlPreview.extractProxyEntry(ProfileSnapshot(proxies = listOf(entry)), "drive")!!
        assertFalse(yaml, yaml.contains("the-secret"))
        assertFalse(yaml, yaml.contains("the-refresh"))
        assertTrue(yaml, yaml.contains(XDriveSecretRedactor.REDACTED))
        assertTrue("server/port stay readable for the offline ping", yaml.contains("server: s"))
    }
}
