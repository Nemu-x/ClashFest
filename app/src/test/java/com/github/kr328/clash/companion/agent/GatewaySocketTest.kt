package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.util.ChangeBus
import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives a real listening gateway (plain HTTP: TLS is the socket factory's job) the way a
 * controller does, to prove the SSE stream reaches the socket frame by frame through NanoHTTPD's
 * chunked writer even when the client accepts gzip.
 */
class GatewaySocketTest {
    @Test
    fun eventsStreamLiveOverTheSocket() {
        val hooks = FakeHooks()
        val server = gateway(hooks)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            val events = (URL("$base/v1/events").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $GOOD_TOKEN")
                setRequestProperty("Accept-Encoding", "gzip")
                readTimeout = 10_000
            }
            assertEquals(200, events.responseCode)
            assertTrue(events.contentType.startsWith("text/event-stream"))
            assertNull(events.contentEncoding)
            val stream = events.inputStream
            assertTrue(stream.readFrame()!!.startsWith("event: status\ndata: {\"app\":\"clashfest\""))

            val select = (URL("$base/v1/proxies/select").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $GOOD_TOKEN")
                setRequestProperty("Content-Type", "application/json")
            }
            select.outputStream.use { it.write("""{"group":"Proxy","node":"JP-01"}""".toByteArray()) }
            assertEquals(200, select.responseCode)
            assertEquals("""{"group":"Proxy","now":"JP-01","ok":true}""", select.inputStream.readBytes().decodeToString())
            assertEquals(listOf("select:Proxy/JP-01"), hooks.calls)

            // The shared select path announces the pick; the stream carries it live.
            ChangeBus.emit(ChangeBus.Change.Proxies, remote = true)
            assertEquals("event: changed\ndata: {\"resource\":\"proxies\"}\n\n", stream.readFrame())
            events.disconnect()
        } finally {
            server.stop()
        }
    }
}
