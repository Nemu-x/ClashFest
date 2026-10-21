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
    @Test
    fun listenerStorageSecretsAreHiddenInReadOnlyConfigs() {
        val text = "listeners:\n  - name: cloud-listener\n    type: vless\n    network: xdrive\n    xdrive-opts:\n      service: google-drive\n      remote-folder: folder\n      secrets: [listener-id, listener-secret, listener-refresh]\n"
        val display = XDriveSecretRedactor.redactConfigYaml(text)
        assertFalse(display, display.contains("listener-secret"))
        assertTrue(display, display.contains(XDriveSecretRedactor.REDACTED))
    }

    @Test
    fun malformedXdriveDisplayDoesNotRevealCredentials() {
        val yaml = "proxies: [broken\nxdrive-opts: {secrets: [private-value]"
        kotlin.test.assertEquals(XDriveSecretRedactor.REDACTED, XDriveSecretRedactor.redactConfigYaml(yaml))
    }

    @Test
    fun entryPreviewHidesStorageCredentials() {
        val yaml = "name: cloud\ntype: vless\nnetwork: xdrive\nxdrive-opts:\n  service: google-drive\n  secrets: [client-id, client-secret, refresh-token]\n"
        val display = XDriveSecretRedactor.redactEntryYaml(yaml)
        kotlin.test.assertFalse(display.contains("client-secret"))
        kotlin.test.assertTrue(display.contains(XDriveSecretRedactor.REDACTED))
    }

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
    fun displayRedactsSecretsWhileRawYamlRemainsUsableForOfflinePings() {
        val entry = Json.parseToJsonElement(
            """{"name":"drive","type":"vless","server":"s","port":443,"network":"xdrive",
               "xdrive-opts":{"service":"google-drive","remote-folder":"f","secrets":["id","the-secret","the-refresh"]}}""",
        ) as JsonObject
        val raw = ProxyYamlPreview.extractProxyEntry(ProfileSnapshot(proxies = listOf(entry)), "drive")!!
        assertTrue("offline proxy tests need the real credentials", raw.contains("the-secret"))
        val yaml = XDriveSecretRedactor.redactEntryYaml(raw)
        assertFalse(yaml, yaml.contains("the-secret"))
        assertFalse(yaml, yaml.contains("the-refresh"))
        assertTrue(yaml, yaml.contains(XDriveSecretRedactor.REDACTED))
        assertTrue("server/port stay readable for the offline ping", yaml.contains("server: s"))
    }

    @Test
    fun redactsInlineProviderPayloadUnderCapitalizedKey() {
        val text = """
            proxy-providers:
              caps:
                Type: inline
                Payload:
                  - name: p
                    type: vless
                    network: xdrive
                    xdrive-opts:
                      service: google-drive
                      secrets: [payload-id, payload-secret, payload-refresh]
                  - name: t
                    type: vless
                    network: xdrive
                    xdrive-opts:
                      service: template
                      template:
                        auth:
                          type: static
                          header:
                            Authorization: Bearer payload-token
        """.trimIndent()
        val out = XDriveSecretRedactor.redactConfigYaml(text)
        for (secret in listOf("payload-id", "payload-secret", "payload-refresh", "payload-token")) {
            assertFalse("$secret leaked: $out", out.contains(secret))
        }
        assertTrue(out, out.contains(XDriveSecretRedactor.REDACTED))
    }

    @Test
    fun redactsCredentialsUnderEngineEquivalentKeySpellings() {
        val text = """
            proxies:
              - name: snake
                type: vless
                network: xdrive
                xdrive_opts:
                  service: google-drive
                  Secrets: [snake-id, snake-secret, snake-refresh]
              - name: caps
                type: vless
                Network: xdrive
                XDrive-Opts:
                  service: template
                  Template:
                    Auth:
                      Type: static
                      header:
                        Authorization: Bearer caps-token
        """.trimIndent()
        val out = XDriveSecretRedactor.redactConfigYaml(text)
        for (secret in listOf("snake-id", "snake-secret", "snake-refresh", "caps-token")) {
            assertFalse("$secret leaked: $out", out.contains(secret))
        }
        val proxies = MihomoConfigDocument.parseOrThrow(out).root["proxies"] as List<*>
        val auth = (((proxies[1] as Map<*, *>)["XDrive-Opts"] as Map<*, *>)["Template"] as Map<*, *>)["Auth"] as Map<*, *>
        assertEquals("static", auth["Type"])
    }

    @Test
    fun redactsCredentialHeadersAndQueryValuesInTemplateOps() {
        val text = """
            proxies:
              - name: t
                type: vless
                network: xdrive
                xdrive-opts:
                  service: template
                  remote-folder: f
                  template:
                    put:
                      method: PUT
                      url: https://s.example.com/up/{name}?access_token=put-token-1&mode=raw
                      headers:
                        Authorization: Bearer put-bearer
                        Content-Type: application/octet-stream
                    get:
                      method: GET
                      url: https://s.example.com/{name}?sig=get-sig-2&API_KEY=get-key-3#frag
                      headers:
                        Cookie: session=get-cookie
                        X-Api-Key: get-api-key
                    list:
                      method: PROPFIND
                      url: https://s.example.com/folder/
                      headers:
                        Depth: "1"
                        x-auth-token: list-token
                        Proxy-Authorization: Basic list-basic
                    Delete:
                      method: DELETE
                      url: https://s.example.com/{name}?client_secret=del-secret
                      headers:
                        X-Upload-Secret: del-header
        """.trimIndent()
        val out = XDriveSecretRedactor.redactConfigYaml(text)
        for (secret in listOf(
            "put-token-1", "put-bearer", "get-sig-2", "get-key-3", "get-cookie", "get-api-key",
            "list-token", "list-basic", "del-secret", "del-header",
        )) {
            assertFalse("$secret leaked: $out", out.contains(secret))
        }
        val proxy = (MihomoConfigDocument.parseOrThrow(out).root["proxies"] as List<*>)[0] as Map<*, *>
        val template = (proxy["xdrive-opts"] as Map<*, *>)["template"] as Map<*, *>
        val put = template["put"] as Map<*, *>
        assertEquals("https://s.example.com/up/{name}?access_token=[redacted]&mode=raw", put["url"])
        assertEquals("application/octet-stream", (put["headers"] as Map<*, *>)["Content-Type"])
        assertEquals("PUT", put["method"])
        val get = template["get"] as Map<*, *>
        assertEquals("https://s.example.com/{name}?sig=[redacted]&API_KEY=[redacted]#frag", get["url"])
        val list = template["list"] as Map<*, *>
        assertEquals("https://s.example.com/folder/", list["url"])
        assertEquals("1", (list["headers"] as Map<*, *>)["Depth"])
    }

    @Test
    fun redactsTemplateOpBodiesThatNameCredentials() {
        val text = """
            proxies:
              - name: t
                type: vless
                network: xdrive
                xdrive-opts:
                  service: template
                  remote-folder: f
                  template:
                    put:
                      method: POST
                      url: https://s.example.com/upload
                      body: 'client_id=abc&client_secret=put-form-secret&grant_type=refresh_token&refresh_token=put-form-refresh'
                    get:
                      method: POST
                      url: https://s.example.com/download
                      body: '{"path": "{name}", "access_token": "get-json-token"}'
                    List:
                      method: POST
                      url: https://s.example.com/list
                      Body: '{"folder":"{folder}","auth":{"api_key":"list-json-key"}}'
                    delete:
                      method: POST
                      url: https://s.example.com/delete
                      body: '{"path":"{name}","recursive":false}'
        """.trimIndent()
        val out = XDriveSecretRedactor.redactConfigYaml(text)
        for (secret in listOf("put-form-secret", "put-form-refresh", "get-json-token", "list-json-key")) {
            assertFalse("$secret leaked: $out", out.contains(secret))
        }
        val proxy = (MihomoConfigDocument.parseOrThrow(out).root["proxies"] as List<*>)[0] as Map<*, *>
        val template = (proxy["xdrive-opts"] as Map<*, *>)["template"] as Map<*, *>
        assertEquals(XDriveSecretRedactor.REDACTED, (template["put"] as Map<*, *>)["body"])
        assertEquals(XDriveSecretRedactor.REDACTED, (template["get"] as Map<*, *>)["body"])
        assertEquals(XDriveSecretRedactor.REDACTED, (template["List"] as Map<*, *>)["Body"])
        assertEquals("""{"path":"{name}","recursive":false}""", (template["delete"] as Map<*, *>)["body"])
        assertEquals("POST", (template["put"] as Map<*, *>)["method"])
    }

    @Test
    fun bodyCredentialDetection() {
        for (body in listOf(
            "client_secret=x",
            "a=1&refresh%5Ftoken=x",
            """{"token":"x"}""",
            """{ "Password" : "x" }""",
            """{"nested":{"secret":"x"}}""",
            "api_key: x",
            "key=x",
        )) {
            assertTrue(body, XDriveSecretRedactor.bodyHasCredential(body))
        }
        for (body in listOf("", "{name}", """{"path":"{name}","mode":"overwrite"}""", "name={name}&parent={folder}")) {
            assertFalse(body, XDriveSecretRedactor.bodyHasCredential(body))
        }
    }

    @Test
    fun templateOpsWithoutCredentialsAreReturnedAsIs() {
        val proxy = mapOf(
            "name" to "x",
            "xdrive-opts" to mapOf(
                "service" to "template",
                "template" to mapOf(
                    "get" to mapOf(
                        "url" to "https://s.example.com/{name}?mode=raw",
                        "headers" to mapOf("Depth" to "1"),
                        "body" to """{"path":"{name}"}""",
                    ),
                ),
            ),
        )
        assertSame(proxy, XDriveSecretRedactor.redactProxy(proxy))
    }

    @Test
    fun credentialNamesAndQueries() {
        for (name in listOf("Authorization", "proxy-authorization", "COOKIE", "X-Api-Key", "x-auth-token", "client_secret", "apikey")) {
            assertTrue(name, XDriveSecretRedactor.isCredentialName(name))
        }
        for (name in listOf("Content-Type", "Depth", "Accept", "mode", "name")) {
            assertFalse(name, XDriveSecretRedactor.isCredentialName(name))
        }
        assertEquals("https://a/b", XDriveSecretRedactor.redactQuery("https://a/b"))
        assertEquals("https://a/?t&token=[redacted]", XDriveSecretRedactor.redactQuery("https://a/?t&token=x"))
        assertEquals("https://a/?access%5Ftoken=[redacted]", XDriveSecretRedactor.redactQuery("https://a/?access%5Ftoken=x"))
    }
}
