package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.companion.protocol.Status
import com.github.kr328.clash.companion.protocol.Traffic
import com.github.kr328.clash.util.ChangeBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionEventHubTest {
    private val status = Status("clashfest", listOf("status"), "id", "TV", "on", 1)

    private fun hub(
        authorized: () -> Boolean = { true },
        traffic: (() -> Traffic)? = null,
        heartbeatMs: Long = 100,
    ) = CompanionEventHub(
        status = { status },
        settingsFingerprint = { null },
        traffic = traffic,
        isAuthorized = { authorized() },
        debounceMs = 10,
        heartbeatMs = heartbeatMs,
    )

    @Test
    fun heartbeatsWhileIdleAndClosesOnceThePairingIsGone() {
        var paired = true
        val hub = hub(authorized = { paired })
        val stream = hub.open("hash", wantsTraffic = false, first = status)
        assertTrue(stream.readFrame()!!.startsWith("event: status\n"))
        assertEquals(": ping\n\n", stream.readFrame())
        paired = false
        assertNull(stream.readFrame())
        hub.stop()
    }

    @Test
    fun burstsAreCoalescedIntoOneChangedEvent() {
        val hub = hub()
        hub.start()
        val stream = hub.open("hash", wantsTraffic = false, first = status)
        stream.readFrame()
        repeat(5) { ChangeBus.emit(ChangeBus.Change.Connections) }
        assertEquals("event: changed\ndata: {\"resource\":\"connections\"}\n\n", stream.readFrame())
        // Next frame is the heartbeat, not a second "connections".
        assertEquals(": ping\n\n", stream.readFrame())
        hub.stop()
        assertNull(stream.readFrame())
    }

    @Test
    fun trafficTopicStreamsSnapshots() {
        val hub = hub(traffic = { Traffic(1, 2, 3, 4) }, heartbeatMs = 60_000)
        val stream = hub.open("hash", wantsTraffic = true, first = status)
        stream.readFrame()
        assertEquals(
            "event: traffic\ndata: {\"down\":2,\"downTotal\":4,\"up\":1,\"upTotal\":3}\n\n",
            stream.readFrame(),
        )
        hub.stop()
    }

    @Test
    fun aRevokedTokenDropsOnlyItsStreams() {
        val hub = hub()
        val mine = hub.open("mine", wantsTraffic = false, first = status)
        val other = hub.open("other", wantsTraffic = false, first = status)
        hub.dropToken("mine")
        assertNull(mine.readFrame())
        assertEquals(1, hub.openStreams)
        assertTrue(other.readFrame()!!.startsWith("event: status"))
        hub.stop()
    }
}
