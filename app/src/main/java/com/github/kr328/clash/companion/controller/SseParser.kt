package com.github.kr328.clash.companion.controller

/** One server-sent event as framed on the wire (PROTOCOL.md §9.2): its type and raw data. */
data class SseFrame(val type: String, val data: String)

/**
 * Incremental parser for the `GET /v1/events` stream (PROTOCOL.md §9.2, WHATWG server-sent events).
 * Fed either whole lines ([line]) or arbitrary chunks ([feed]) that may split a line or a frame
 * anywhere. Lines starting with `:` are heartbeats and ignored; a blank line dispatches the frame
 * collected so far. Fields other than `event` and `data` are ignored.
 */
class SseParser {
    private val pending = StringBuilder()
    private var type: String? = null
    private val data = mutableListOf<String>()

    /** Appends a chunk and returns every frame it completed, in order. */
    fun feed(chunk: String): List<SseFrame> {
        pending.append(chunk)
        val out = mutableListOf<SseFrame>()
        while (true) {
            val nl = pending.indexOf("\n")
            if (nl < 0) break
            val raw = pending.substring(0, nl)
            pending.delete(0, nl + 1)
            line(raw.removeSuffix("\r"))?.let(out::add)
        }
        return out
    }

    /** Consumes one line without its terminator; returns the frame a blank line completes. */
    fun line(line: String): SseFrame? {
        when {
            line.isEmpty() -> {
                val t = type
                val frame = if (t != null || data.isNotEmpty()) {
                    SseFrame(t?.takeIf { it.isNotEmpty() } ?: DEFAULT_TYPE, data.joinToString("\n"))
                } else {
                    null
                }
                type = null
                data.clear()
                return frame
            }
            line.startsWith(":") -> Unit
            else -> {
                val colon = line.indexOf(':')
                val field = if (colon < 0) line else line.substring(0, colon)
                val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
                when (field) {
                    "event" -> type = value
                    "data" -> data += value
                }
            }
        }
        return null
    }

    private companion object {
        const val DEFAULT_TYPE = "message"
    }
}
