package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.companion.protocol.CanonicalJson
import com.github.kr328.clash.companion.protocol.Changed
import com.github.kr328.clash.companion.protocol.Events
import com.github.kr328.clash.companion.protocol.Status
import com.github.kr328.clash.companion.protocol.Traffic
import com.github.kr328.clash.util.ChangeBus
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Fans app changes out to the open `GET /v1/events` streams (PROTOCOL.md §9.2).
 *
 * Listens to [ChangeBus], so changes made on the device itself (node picked in the Home picker,
 * mode toggle, profile switch, VPN on/off, subscription update) reach controllers exactly like the
 * ones made through the gateway. Bursts are coalesced for [debounceMs] — one tap often produces a
 * broadcast and an explicit emit — and a `status` event goes out only when the status object
 * actually changed. While a stream is open a slow watch also catches what nothing announces: a
 * url-test group flipping the node in use (summary) and settings changed on the Settings screen.
 *
 * Each stream is an [EventStream] that NanoHTTPD copies to the socket as chunked transfer, one
 * chunk per frame. A reader that falls [STREAM_BUFFER] frames behind is dropped; streams of a
 * revoked pairing are closed.
 */
internal class CompanionEventHub(
    private val status: () -> Status?,
    private val settingsFingerprint: () -> String?,
    private val traffic: (() -> Traffic)?,
    private val isAuthorized: (tokenHash: String) -> Boolean,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val watchMs: Long = WATCH_MS,
) : ChangeBus.Listener {
    private val streams = CopyOnWriteArraySet<EventStream>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "companion-events").apply { isDaemon = true }
    }
    private val pending = LinkedHashSet<ChangeBus.Change>()
    private var flushTask: ScheduledFuture<*>? = null
    private var watchTask: ScheduledFuture<*>? = null

    @Volatile
    private var lastStatusJson: String? = null

    @Volatile
    private var lastSettings: String? = null

    fun start() {
        ChangeBus.addListener(this)
    }

    fun stop() {
        ChangeBus.removeListener(this)
        streams.forEach { it.close() }
        scheduler.shutdownNow()
    }

    /** Opens a stream whose first frame is [first] (the §8.1 status). */
    fun open(tokenHash: String, wantsTraffic: Boolean, first: Status): EventStream {
        val stream = EventStream(tokenHash, wantsTraffic && traffic != null)
        stream.offer(Events.frame(Events.STATUS, first.toJson()))
        streams += stream
        synchronized(this) {
            if (watchTask == null) {
                lastSettings = runCatching { settingsFingerprint() }.getOrNull()
                watchTask = scheduler.scheduleWithFixedDelay(::watch, watchMs, watchMs, TimeUnit.MILLISECONDS)
            }
        }
        return stream
    }

    /** §7.3/§7.4: end every stream of a revoked pairing. */
    fun dropToken(tokenHash: String) {
        streams.filter { it.tokenHash == tokenHash }.forEach { it.close() }
    }

    val openStreams: Int
        get() = streams.size

    override fun onChanged(change: ChangeBus.Change, remote: Boolean) {
        if (streams.isEmpty()) return
        synchronized(this) {
            pending += change
            if (flushTask == null) {
                flushTask = scheduler.schedule(::flush, debounceMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun flush() {
        val changes = synchronized(this) {
            flushTask = null
            pending.toList().also { pending.clear() }
        }
        for (change in changes) {
            resourceOf(change)?.let { broadcast(Events.frame(Events.CHANGED, Changed(it).toJson())) }
        }
        if (changes.any { it in STATUS_CHANGES }) publishStatusIfChanged()
    }

    private fun watch() {
        if (streams.isEmpty()) {
            synchronized(this) {
                if (streams.isEmpty()) {
                    watchTask?.cancel(false)
                    watchTask = null
                }
            }
            return
        }
        publishStatusIfChanged()
        val settings = runCatching { settingsFingerprint() }.getOrNull()
        if (settings != null && lastSettings != null && settings != lastSettings) {
            broadcast(Events.frame(Events.CHANGED, Changed(Changed.SETTINGS).toJson()))
        }
        if (settings != null) lastSettings = settings
    }

    private fun publishStatusIfChanged() {
        val st = runCatching { status() }
            .onFailure { Log.d("Companion events: status failed: ${it.message}") }
            .getOrNull() ?: return
        val json = CanonicalJson.encode(st.toJson())
        if (json == lastStatusJson) return
        lastStatusJson = json
        broadcast("event: ${Events.STATUS}\ndata: $json\n\n")
    }

    private fun broadcast(frame: String) {
        for (stream in streams) stream.offer(frame)
    }

    private fun trafficFrame(): String? = runCatching { traffic?.invoke() }.getOrNull()
        ?.let { Events.frame(Events.TRAFFIC, it.toJson()) }

    /**
     * One SSE stream, read by NanoHTTPD's response writer on the connection's own thread. [read]
     * blocks until a frame, a traffic tick or a heartbeat is due, and returns -1 once closed so the
     * writer finishes the chunked body.
     */
    inner class EventStream(val tokenHash: String, private val wantsTraffic: Boolean) : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>(STREAM_BUFFER)

        @Volatile
        private var closed = false
        private var current: ByteArray? = null
        private var position = 0
        private var nextHeartbeat = now() + heartbeatMs
        private var nextTraffic = now() + TRAFFIC_MS

        val isClosed: Boolean
            get() = closed

        fun offer(frame: String) {
            if (closed) return
            if (!queue.offer(frame.toByteArray(Charsets.UTF_8))) {
                Log.i("Companion events: dropping a stream that fell behind")
                close()
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var chunk = current
            if (chunk == null || position >= chunk.size) {
                chunk = next() ?: return -1
                current = chunk
                position = 0
            }
            val n = minOf(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, n)
            position += n
            return n
        }

        private fun next(): ByteArray? {
            while (!closed) {
                val t = now()
                val due = if (wantsTraffic) minOf(nextHeartbeat, nextTraffic) else nextHeartbeat
                val frame = queue.poll((due - t).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                if (closed) break
                if (frame != null) return frame
                val after = now()
                if (wantsTraffic && after >= nextTraffic) {
                    nextTraffic = after + TRAFFIC_MS
                    trafficFrame()?.let { return it.toByteArray(Charsets.UTF_8) }
                }
                if (after >= nextHeartbeat) {
                    nextHeartbeat = after + heartbeatMs
                    if (!isAuthorized(tokenHash)) {
                        close()
                        break
                    }
                    return Events.HEARTBEAT.toByteArray(Charsets.UTF_8)
                }
            }
            return null
        }

        override fun close() {
            if (closed) return
            closed = true
            streams.remove(this)
            queue.clear()
            queue.offer(ByteArray(0))
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val DEBOUNCE_MS = 200L
        const val HEARTBEAT_MS = 15_000L
        const val WATCH_MS = 15_000L
        const val TRAFFIC_MS = 1_000L
        const val STREAM_BUFFER = 64

        val STATUS_CHANGES = setOf(
            ChangeBus.Change.Power,
            ChangeBus.Change.Name,
            ChangeBus.Change.Mode,
            ChangeBus.Change.Profiles,
            ChangeBus.Change.Proxies,
        )

        fun resourceOf(change: ChangeBus.Change): String? = when (change) {
            ChangeBus.Change.Proxies -> Changed.PROXIES
            ChangeBus.Change.Mode -> Changed.MODE
            ChangeBus.Change.Profiles -> Changed.PROFILES
            ChangeBus.Change.Providers -> Changed.PROVIDERS
            ChangeBus.Change.Connections -> Changed.CONNECTIONS
            ChangeBus.Change.Settings -> Changed.SETTINGS
            ChangeBus.Change.Power, ChangeBus.Change.Name -> null
        }
    }
}
