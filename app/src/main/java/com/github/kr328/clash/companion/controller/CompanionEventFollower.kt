package com.github.kr328.clash.companion.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Keeps one `GET /v1/events` stream (PROTOCOL.md §9.2) open while the caller's coroutine runs:
 * reconnects with exponential backoff when the stream drops, and tells the caller each time it is
 * (re)opened so it can refetch what it shows — events are not replayed across reconnects.
 *
 * Cancelling the coroutine cancels the HTTP call, so the stream stops as soon as the screen that
 * owns it is no longer visible.
 */
class CompanionEventFollower(
    private val client: CompanionClient,
    private val topics: List<String> = emptyList(),
    private val initialBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 30_000L,
) {
    enum class State { Connecting, Live, Reconnecting }

    /**
     * Runs until cancelled. [onState] reports link changes (with the failure that caused a
     * reconnect); [onOpen] runs after every successful (re)connect, so the caller refetches;
     * [onEvent] receives decoded events. A [CompanionError] the agent answered with (e.g.
     * `unauthorized` after an un-pair on the TV) goes to [onRejected], which returns true to keep
     * retrying and false to stop.
     */
    suspend fun run(
        onState: suspend (State, Throwable?) -> Unit,
        onOpen: suspend () -> Unit,
        onEvent: suspend (CompanionEvent) -> Unit,
        onRejected: suspend (CompanionError) -> Boolean = { true },
    ) {
        var backoff = initialBackoffMs
        var first = true
        while (coroutineContext.isActive) {
            onState(if (first) State.Connecting else State.Reconnecting, null)
            first = false
            var opened = false
            val failure: Throwable? = try {
                stream(
                    onOpened = {
                        opened = true
                        backoff = initialBackoffMs
                        onState(State.Live, null)
                        onOpen()
                    },
                    onEvent = onEvent,
                )
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionError) {
                if (!onRejected(e)) return
                e
            } catch (e: Exception) {
                e
            }
            if (!coroutineContext.isActive) return
            onState(State.Reconnecting, failure)
            if (opened) {
                delay(initialBackoffMs)
            } else {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
            }
        }
    }

    /** One stream: returns when the agent ends it, throws when it fails, cancels the call on cancel. */
    private suspend fun stream(
        onOpened: suspend () -> Unit,
        onEvent: suspend (CompanionEvent) -> Unit,
    ) = coroutineScope {
        val call = client.events(topics)
        // Blocking socket reads do not observe coroutine cancellation; closing the call does.
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        // One consumer in the caller's context keeps "opened" first and events in wire order.
        val inbox = Channel<Signal>(Channel.UNLIMITED)
        val consumer = launch {
            for (signal in inbox) {
                when (signal) {
                    Signal.Opened -> onOpened()
                    is Signal.Event -> onEvent(signal.event)
                }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                call.read(
                    onOpen = { inbox.trySend(Signal.Opened) },
                    onEvent = { event -> inbox.trySend(Signal.Event(event)) },
                )
            }
        } finally {
            inbox.close()
            canceller.cancel()
        }
        consumer.join()
    }

    private sealed class Signal {
        object Opened : Signal()
        data class Event(val event: CompanionEvent) : Signal()
    }
}
