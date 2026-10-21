package com.github.kr328.clash.companion.controller

import com.github.kr328.clash.companion.protocol.Vectors
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The controller's side of `GET /v1/events` (PROTOCOL.md §9.2), checked against control.json. */
class SseParserTest {
    private data class VectorEvent(val type: String, val data: String, val frame: String)

    private val vectors: List<VectorEvent> by lazy {
        Vectors.json("control.json").jsonObject["events"]!!.jsonArray.map {
            val o = it.jsonObject
            VectorEvent(o["type"]!!.jsonPrimitive.content, o["data"]!!.jsonPrimitive.content, o["frame"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun parsesEveryVectorFrame() {
        for (v in vectors) {
            val frames = SseParser().feed(v.frame)
            assertEquals(listOf(SseFrame(v.type, v.data)), frames, "frame ${v.type}")
        }
    }

    @Test
    fun ignoresHeartbeatsBetweenFrames() {
        val stream = ": ping\n\n" + vectors.joinToString(": ping\n\n") { it.frame } + ":\n\n"
        assertEquals(vectors.map { SseFrame(it.type, it.data) }, SseParser().feed(stream))
    }

    @Test
    fun reassemblesFramesSplitAtEveryByte() {
        val stream = vectors.joinToString("") { it.frame + ": ping\n\n" }
        val parser = SseParser()
        val out = mutableListOf<SseFrame>()
        // One code point at a time: a chunk can end inside a field name, a value or the blank line.
        var i = 0
        while (i < stream.length) {
            val n = Character.charCount(stream.codePointAt(i))
            out += parser.feed(stream.substring(i, i + n))
            i += n
        }
        assertEquals(vectors.map { SseFrame(it.type, it.data) }, out)
    }

    @Test
    fun reassemblesFramesSplitIntoArbitraryChunks() {
        val stream = vectors.joinToString("") { it.frame }
        for (size in listOf(2, 7, 13, 64)) {
            val parser = SseParser()
            val out = stream.chunked(size).flatMap(parser::feed)
            assertEquals(vectors.map { SseFrame(it.type, it.data) }, out, "chunk size $size")
        }
    }

    @Test
    fun acceptsCrLfAndJoinsMultiLineData() {
        val frames = SseParser().feed("event: x\r\ndata: a\r\ndata:b\r\n\r\ndata: only\n\n")
        assertEquals(listOf(SseFrame("x", "a\nb"), SseFrame("message", "only")), frames)
    }

    @Test
    fun blankLinesAloneDispatchNothing() {
        assertTrue(SseParser().feed("\n\n\n: ping\n\n").isEmpty())
    }

    @Test
    fun decodesKnownEventsAndSkipsUnknownOnes() {
        val parser = SseParser()
        val events = vectors.flatMap { parser.feed(it.frame) }.map(CompanionEvent::decode)
        val changed = assertIs<CompanionEvent.ResourceChanged>(events[0])
        assertEquals("mode", changed.resource)
        val traffic = assertIs<CompanionEvent.TrafficTick>(events[1])
        assertEquals(3276800L, traffic.traffic.down)
        val log = assertIs<CompanionEvent.Log>(events[2])
        assertEquals("line1\nline2 «ü»", log.line.msg)

        assertNull(CompanionEvent.decode(SseFrame("future_event", "{\"x\":1}")))
        assertNull(CompanionEvent.decode(SseFrame("changed", "not json")))
        assertNull(CompanionEvent.decode(SseFrame("changed", "{\"resource\":5}")))
    }

    @Test
    fun decodesStatusEventFromTheStatusVector() {
        val body = Vectors.json("control.json").jsonObject["bodies"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "status.response.summary" }
            .jsonObject["canonical"]!!.jsonPrimitive.content
        val frame = SseParser().feed("event: status\ndata: $body\n\n").single()
        val status = assertIs<CompanionEvent.StatusChanged>(CompanionEvent.decode(frame)).status
        assertEquals("🇯🇵 Japan", status.summary?.proxy)
        assertEquals("events", status.capabilities.last())
    }
}
