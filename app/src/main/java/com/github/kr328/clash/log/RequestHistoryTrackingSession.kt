package com.github.kr328.clash.log

import com.github.kr328.clash.common.util.RemoteServiceUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class RequestHistoryTrackingSession(
    private val scope: CoroutineScope,
    private val start: suspend () -> Unit,
    private val stop: suspend () -> Unit,
    private val refresh: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val refreshIntervalMs: Long = 2_000L,
    private val requestTimeoutMs: Long = 5_000L,
    private val stopTimeoutMs: Long = 1_000L,
) {
    private var job: Job? = null

    suspend fun setVisible(visible: Boolean) {
        if (visible && job?.isActive == true) return
        job?.cancelAndJoin()
        job = null
        if (!visible) return

        job = scope.launch {
            var ownsTracking = false
            try {
                val started = withTimeoutOrNull(requestTimeoutMs) {
                    start()
                    ownsTracking = true
                    true
                } ?: false
                if (!started) {
                    onFailure(RemoteServiceUnavailableException())
                    return@launch
                }

                while (isActive) {
                    try {
                        withTimeoutOrNull(requestTimeoutMs) { refresh() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onFailure(error)
                        if (error is RemoteServiceUnavailableException) break
                    }
                    delay(refreshIntervalMs)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error)
            } finally {
                if (ownsTracking) {
                    withContext(NonCancellable) {
                        try {
                            withTimeoutOrNull(stopTimeoutMs) { stop() }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            onFailure(error)
                        }
                    }
                }
            }
        }
    }
}
