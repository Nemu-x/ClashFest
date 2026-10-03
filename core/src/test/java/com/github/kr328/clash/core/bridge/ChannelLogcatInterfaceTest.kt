package com.github.kr328.clash.core.bridge

import com.github.kr328.clash.core.model.LogMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ChannelLogcatInterfaceTest {
    @Test
    fun openChannelReceivesDecodedMessage() {
        val channel = Channel<LogMessage>(1)
        val callback = ChannelLogcatInterface(channel)

        callback.received(payload("first"))

        val message = channel.tryReceive().getOrThrow()
        assertEquals(LogMessage.Level.Info, message.level)
        assertEquals("first", message.message)
        assertEquals(1L, message.time.time)
        channel.cancel()
    }

    @Test
    fun fullBufferDropsMessageWithoutStoppingSubscriber() {
        val channel = Channel<LogMessage>(1)
        val callback = ChannelLogcatInterface(channel)

        callback.received(payload("first"))
        callback.received(payload("dropped"))
        assertEquals("first", channel.tryReceive().getOrThrow().message)
        assertFalse(channel.tryReceive().isSuccess)

        callback.received(payload("next"))
        assertEquals("next", channel.tryReceive().getOrThrow().message)
        channel.cancel()
    }

    @Test
    fun canceledChannelSignalsNativeSubscriberToStop() {
        val channel = Channel<LogMessage>(1)
        val callback = ChannelLogcatInterface(channel)
        channel.cancel()

        assertThrows(CancellationException::class.java) {
            callback.received(payload("after cancellation"))
        }
    }

    @Test
    fun normallyClosedChannelSignalsStopWithBufferedMessagesRemaining() {
        val channel = Channel<LogMessage>(1)
        val callback = ChannelLogcatInterface(channel)
        callback.received(payload("buffered"))
        channel.close()

        assertThrows(CancellationException::class.java) {
            callback.received(payload("after close"))
        }
        assertEquals("buffered", channel.tryReceive().getOrThrow().message)
    }

    private fun payload(message: String): String =
        """{"level":"info","message":"$message","time":1}"""
}
