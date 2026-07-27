package com.github.kr328.clash.service.clash.module

internal data class NetworkSwitchReactionDecision<T : Any>(
    val reaction: T? = null,
    val retryAfterMs: Long? = null,
    val cancelPendingRetry: Boolean = false,
)

internal class NetworkSwitchReactionGate<T : Any>(
    private val startedAt: Long,
    private val startupGraceMs: Long = 5_000L,
    /**
     * Minimum spacing between two full reactions, nudged 3s -> 5s so a single network change
     * that arrives as a burst of callbacks (available -> losing -> capabilities, which can
     * straggle over a couple of seconds) reacts once instead of twice.
     *
     * Deliberately NOT larger. A wider guard was tried while chasing a battery report, on the
     * theory that repeated reactions were burning radio; measurement showed the drain came from
     * the subscription's own url-test timers, not from here, and stretching the guard only made
     * the phone slower to recover after a real Wi-Fi <-> cellular switch — the exact wait this
     * feature exists to remove.
     */
    private val flapGuardMs: Long = 5_000L,
) {
    private var initialized = false
    private var observed: T? = null
    private var settled: T? = null
    private var lastReactionAt: Long? = null

    fun observe(candidate: T?, now: Long, enabled: Boolean): NetworkSwitchReactionDecision<T> {
        if (!initialized) {
            initialized = true
            observed = candidate
            settled = candidate
            return NetworkSwitchReactionDecision(cancelPendingRetry = true)
        }
        if (candidate == observed) return NetworkSwitchReactionDecision()

        observed = candidate
        return decide(candidate, now, enabled)
    }

    fun retry(candidate: T?, now: Long, enabled: Boolean): NetworkSwitchReactionDecision<T> {
        if (candidate != observed) return observe(candidate, now, enabled)

        return decide(candidate, now, enabled)
    }

    private fun decide(
        candidate: T?,
        now: Long,
        enabled: Boolean,
    ): NetworkSwitchReactionDecision<T> {
        if (candidate == null) {
            settled = null
            return NetworkSwitchReactionDecision(cancelPendingRetry = true)
        }
        if (!enabled || now - startedAt < startupGraceMs) {
            settled = candidate
            return NetworkSwitchReactionDecision(cancelPendingRetry = true)
        }
        if (candidate == settled) {
            return NetworkSwitchReactionDecision(cancelPendingRetry = true)
        }

        val previousReactionAt = lastReactionAt
        if (previousReactionAt != null) {
            val elapsed = now - previousReactionAt
            if (elapsed < flapGuardMs) {
                return NetworkSwitchReactionDecision(
                    retryAfterMs = flapGuardMs - elapsed,
                    cancelPendingRetry = true,
                )
            }
        }

        lastReactionAt = now
        settled = candidate
        return NetworkSwitchReactionDecision(
            reaction = candidate,
            cancelPendingRetry = true,
        )
    }
}
