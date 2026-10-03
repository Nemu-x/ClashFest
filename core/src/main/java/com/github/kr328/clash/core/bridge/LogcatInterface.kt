package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep
import com.github.kr328.clash.core.model.LogMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.Json

@Keep
interface LogcatInterface {
    fun received(jsonPayload: String)
}

internal class ChannelLogcatInterface(private val channel: SendChannel<LogMessage>) : LogcatInterface {
    override fun received(jsonPayload: String) {
        val result = channel.trySend(Json.decodeFromString(LogMessage.serializer(), jsonPayload))
        // JNI treats a callback exception as the signal to unsubscribe and release its global ref.
        // A full buffer only drops this message; it must not stop the native subscriber.
        if (result.isClosed) {
            throw CancellationException("Log channel closed", result.exceptionOrNull())
        }
    }
}
