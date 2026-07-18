package com.github.kr328.clash.service.util

import java.io.EOFException
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a cryptic fetch/parse failure into a clear, stable-coded reason. It handles
 * the two failure classes where the raw error misleads the user:
 *
 *  - **Non-config body** (`E-10`/`E-11`): the server returned 200 but the body is an
 *    empty response or an HTML error/rate-limit page instead of YAML. mihomo then
 *    fails deep in parse with a confusing `yaml: line N: ...` pointing at the error
 *    page.
 *  - **Network unreachable** (`E-20`): nothing was downloaded at all — DNS/TLS/connect
 *    timed out or the host is blocked — so the raw error is a stack-y
 *    `java.net.SocketTimeouteException: failed to connect to ...`.
 *  - **Refused by the server** (`E-21`): the server answered, with a non-2xx status. The
 *    engine rejects those bodies rather than saving an error page as config.yaml, so
 *    nothing lands on disk and the raw error is a bare `server returned HTTP 403 ...`.
 *
 * A genuine config error (valid YAML, invalid values) is left **untouched** so its
 * precise engine message (e.g. `proxy 'X' not found`) survives — see docs/errors.md
 * Layer 3. Codes are stable so support/wiki can key troubleshooting to them.
 */
object FetchErrorClassifier {
    internal const val MAX_CLASSIFICATION_BYTES = 64 * 1024
    private data class BodySample(val text: String, val complete: Boolean)

    fun clarify(processingDir: File, original: Throwable): Throwable {
        val file = File(processingDir, "config.yaml")
        // No body downloaded → network reachability failure (or an unrelated error we
        // shouldn't mask). Classify only the recognizable network case.
        if (!file.isFile) {
            val status = httpStatusOf(original)
            if (status != null) {
                return IllegalStateException(httpStatusReason(status), original)
            }
            if (looksLikeNetworkFailure(original)) {
                return IllegalStateException(
                    "couldn't reach the subscription server — check your connection or " +
                        "try again later (the host may be temporarily blocked). [E-20]",
                    original,
                )
            }
            return original
        }
        val sample = readBoundedText(file) ?: return original
        val body = sample.text

        val reason = when {
            sample.complete && body.isBlank() ->
                "the subscription server returned an empty response — try again later. [E-10]"
            looksLikeHtml(body) ->
                "the subscription server returned a web page, not a config " +
                    "(likely rate-limited or an error page) — try again later. [E-11]"
            looksLikeAgeArmor(body) ->
                "the subscription is age-encrypted and the decryption key is missing or wrong — " +
                    "import the full link from your dashboard (it carries the key), or set the " +
                    "profile's age secret key. [E-30]"
            else -> return original // valid-looking config body → keep the engine's precise error
        }
        return IllegalStateException(reason, original)
    }

    private fun readBoundedText(file: File): BodySample? {
        return runCatching {
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(MAX_CLASSIFICATION_BYTES + 1)
                var total = 0
                while (total < bytes.size) {
                    val read = input.read(bytes, total, bytes.size - total)
                    if (read < 0) break
                    if (read == 0) {
                        val one = input.read()
                        if (one < 0) break
                        bytes[total++] = one.toByte()
                    } else {
                        total += read
                    }
                }
                BodySample(
                    text = bytes.copyOf(minOf(total, MAX_CLASSIFICATION_BYTES)).toString(Charsets.UTF_8),
                    complete = total <= MAX_CLASSIFICATION_BYTES,
                )
            }
        }.getOrNull()
    }

    /**
     * Walks the cause chain (mihomo wraps the underlying I/O error in higher-level
     * subscription/parser exceptions). Mirrors [com.github.kr328.clash.util.ImportRetry]'s
     * transient set — the same reachability failures, here surfaced as a clear reason
     * once the retries are exhausted.
     */
    internal fun looksLikeNetworkFailure(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            when (cur) {
                is UnknownHostException, is SocketTimeoutException, is EOFException -> return true
            }
            val msg = cur.message?.lowercase().orEmpty()
            if (
                "unable to resolve host" in msg ||
                "no address associated" in msg ||
                "failed to connect" in msg ||
                "connection reset" in msg ||
                "connection refused" in msg ||
                "timed out" in msg ||
                "timeout" in msg ||
                "unexpected end of stream" in msg ||
                "network is unreachable" in msg
            ) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * Pulls the status code out of the engine's non-2xx error. The message is built by
     * `httpStatusError` in `native/config/fetch.go`, and may be wrapped once by the
     * route-fallback error, which reports both attempts: `proxy: server returned HTTP 403
     * Forbidden (direct: ...)`. The first code wins — it is the preferred route's answer.
     */
    internal fun httpStatusOf(e: Throwable): Int? {
        var cur: Throwable? = e
        while (cur != null) {
            val match = HTTP_STATUS.find(cur.message.orEmpty())
            if (match != null) return match.groupValues[1].toIntOrNull()
            cur = cur.cause
        }
        return null
    }

    /**
     * 401/403 are worth splitting: both mean "refused", but the fix differs. A 401 is the
     * subscription token, while a 403 survived the engine's automatic retry on the other route
     * (see `route` in fetch.go) — so it is refusing this account or this exit IP, not this path.
     */
    internal fun httpStatusReason(status: Int): String = when (status) {
        401, 402 ->
            "the subscription server rejected your account (HTTP $status) — the link may have " +
                "expired or been revoked; re-import it from your dashboard. [E-21]"
        403, 451 ->
            "the subscription server refused the request (HTTP $status), and the automatic retry " +
                "on the other route didn't help — your plan may be expired, or the panel may be " +
                "blocking this network. [E-21]"
        404, 410 ->
            "the subscription link no longer exists on the server (HTTP $status) — re-import it " +
                "from your dashboard. [E-21]"
        in 500..599 ->
            "the subscription server is having problems (HTTP $status) — try again later. [E-21]"
        else ->
            "the subscription server returned HTTP $status instead of a config — try again " +
                "later. [E-21]"
    }

    private val HTTP_STATUS = Regex("""server returned HTTP (\d{3})""")

    /**
     * The body downloaded fine but is an age armor the engine couldn't decrypt
     * (no key set, or the wrong one) — the fetch pipeline decrypts in place on
     * success, so an armor surviving to the error path means decryption failed.
     */
    internal fun looksLikeAgeArmor(body: String): Boolean =
        body.trimStart().startsWith("-----BEGIN AGE ENCRYPTED FILE-----")

    internal fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(512).lowercase()
        if (head.isEmpty()) return false
        return head.startsWith("<!doctype") ||
            head.startsWith("<html") ||
            head.startsWith("<?xml") ||
            head.contains("<head") ||
            head.contains("<body") ||
            head.contains("<title") ||
            (head.startsWith("<") && head.contains("</"))
    }
}
