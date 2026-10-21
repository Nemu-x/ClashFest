package com.github.kr328.clash.companion.agent

import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Early answers (401, unadvertised not_found, unpair, unknown endpoint) never parse the body. On a
 * keep-alive connection the gateway must still consume it, or the next request on the same socket
 * starts with the leftover bytes (PROTOCOL.md §5, §8.6).
 */
class GatewayKeepAliveTest {
    private class Reply(val code: Int, val headers: Map<String, String>, val body: String)

    private fun withConnection(block: (send: (String) -> Unit, read: () -> Reply) -> Unit) {
        val server = gateway(FakeHooks())
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = 10_000
                val out = socket.getOutputStream()
                val input = BufferedInputStream(socket.getInputStream())
                block({ out.write(it.toByteArray(Charsets.UTF_8)); out.flush() }, { input.readReply() })
            }
        } finally {
            server.stop()
        }
    }

    private fun post(path: String, body: String, token: String? = GOOD_TOKEN): String = buildString {
        append("POST $path HTTP/1.1\r\nHost: agent\r\n")
        if (token != null) append("Authorization: Bearer $token\r\n")
        append("Content-Type: application/json\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
        append(body)
    }

    private fun get(path: String): String =
        "GET $path HTTP/1.1\r\nHost: agent\r\nAuthorization: Bearer $GOOD_TOKEN\r\n\r\n"

    @Test
    fun unadvertisedPostLeavesTheConnectionUsable() = withConnection { send, read ->
        send(post("/v1/proxies/unfix", """{"group":"Proxy"}"""))
        val first = read()
        assertEquals(404, first.code)
        assertTrue(first.body.contains("\"not_found\""))

        send(get("/v1/proxies"))
        val second = read()
        assertEquals(200, second.code)
        assertTrue(second.body.startsWith("{\"groups\""), second.body)
    }

    @Test
    fun earlyAnswersDrainTheirBodies() = withConnection { send, read ->
        send(post("/v1/profiles/delete", """{"id":"p-1"}"""))
        assertEquals(404, read().code)
        send(post("/v1/status", """{"x":1}""", token = "wrong"))
        assertEquals(401, read().code)
        send(post("/v1/nope", """{"y":2}"""))
        assertEquals(404, read().code)
        send(post("/v1/core/configs", """{"z":3}"""))
        assertEquals(404, read().code)
        send(post("/v1/proxies/delay", """{"group":"Proxy","timeout":1}"""))
        val delay = read()
        assertEquals(400, delay.code)
        assertTrue(delay.body.contains("\"bad_request\""), delay.body)
        send(get("/v1/status"))
        assertEquals(200, read().code)
    }

    @Test
    fun unpairBodyIsConsumedBeforeTheNextRequest() = withConnection { send, read ->
        send(post("/v1/unpair", "{}"))
        assertEquals(200, read().code)
        send(get("/v1/events"))
        assertEquals(401, read().code)
    }

    private fun InputStream.readReply(): Reply {
        val statusLine = readLine()
        val code = statusLine.split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = read(body, off, length - off)
            check(n >= 0) { "connection closed mid-body" }
            off += n
        }
        return Reply(code, headers, body.decodeToString())
    }

    private fun InputStream.readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = read()
            check(b >= 0) { "connection closed" }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    // A body sent as plain "application/json" (no charset), as the Go reference controller does,
    // must still reach the hook as UTF-8: group and node names are usually emoji and Cyrillic.
    @Test
    fun bodyWithoutCharsetIsDecodedAsUtf8() {
        val hooks = FakeHooks()
        val server = gateway(hooks)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = 10_000
                val group = "🌐 Сервер по умолчанию"
                val node = "🇫🇮 Finland"
                socket.getOutputStream().apply {
                    write(post("/v1/proxies/select", """{"group":"$group","node":"$node"}""").toByteArray(Charsets.UTF_8))
                    flush()
                }
                val reply = BufferedInputStream(socket.getInputStream()).readReply()
                assertEquals(404, reply.code)
                assertTrue(hooks.calls.contains("select:$group/$node"), hooks.calls.toString())
                assertTrue(reply.body.contains(group), reply.body)
            }
        } finally {
            server.stop()
        }
    }
}
