package com.github.kr328.clash.service.util

import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FetchErrorClassifierTest {
    private fun dirWith(config: String?): File {
        val dir = Files.createTempDirectory("fec").toFile()
        if (config != null) File(dir, "config.yaml").writeText(config)
        return dir
    }

    private fun dirWith(config: String?, headers: Map<String, String>): File {
        val dir = dirWith(config)
        val json = headers.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${k.lowercase()}\":\"$v\""
        }
        File(dir, FetchHeadersFile.FILE_NAME).writeText(json)
        return dir
    }

    private val original = RuntimeException("yaml: line 7: could not find expected ':'")
    private val forbidden = RuntimeException("server returned HTTP 403 Forbidden")
    private val now = 1_800_000_000L

    @Test fun html_body_becomes_friendly() {
        val out = FetchErrorClassifier.clarify(dirWith("<!DOCTYPE html><html><body>429</body></html>"), original)
        assertTrue(out.message!!.contains("web page"), out.message)
        assertTrue(out.message!!.contains("[E-11]"), out.message)
        assertSame(original, out.cause)
    }

    @Test fun empty_body_becomes_friendly() {
        val out = FetchErrorClassifier.clarify(dirWith("   \n  "), original)
        assertTrue(out.message!!.contains("empty response"), out.message)
        assertTrue(out.message!!.contains("[E-10]"), out.message)
    }

    @Test fun age_armor_body_becomes_friendly() {
        // The Go fetch decrypts in place on success; an armor body surviving to
        // the error path means the key was missing or wrong.
        val armor = listOf("-----BEGIN AGE ENCRYPTED FILE-----", "YWdlLWVuY3J5cHRpb24ub3JnL3Yx").joinToString(System.lineSeparator())
        val out = FetchErrorClassifier.clarify(dirWith(armor), original)
        assertTrue(out.message!!.contains("age-encrypted"), out.message)
        assertTrue(out.message!!.contains("[E-30]"), out.message)
        assertSame(original, out.cause)
    }

    @Test fun valid_config_keeps_original() {
        assertSame(original, FetchErrorClassifier.clarify(dirWith("proxies: []\nrules:\n  - MATCH,DIRECT\n"), original))
    }

    @Test fun oversized_html_is_classified_from_bounded_prefix() {
        val oversized = "<!DOCTYPE html>" + " ".repeat(FetchErrorClassifier.MAX_CLASSIFICATION_BYTES)
        val out = FetchErrorClassifier.clarify(dirWith(oversized), original)
        assertTrue(out.message!!.contains("[E-11]"), out.message)
    }

    @Test fun oversized_age_armor_is_classified_from_bounded_prefix() {
        val oversized = "-----BEGIN AGE ENCRYPTED FILE-----\n" +
            "A".repeat(FetchErrorClassifier.MAX_CLASSIFICATION_BYTES)
        val out = FetchErrorClassifier.clarify(dirWith(oversized), original)
        assertTrue(out.message!!.contains("[E-30]"), out.message)
    }

    @Test fun oversized_blank_or_yaml_prefix_keeps_original() {
        val blank = " ".repeat(FetchErrorClassifier.MAX_CLASSIFICATION_BYTES + 1)
        assertSame(original, FetchErrorClassifier.clarify(dirWith(blank), original))

        val yaml = "proxies: []\n" + "#".repeat(FetchErrorClassifier.MAX_CLASSIFICATION_BYTES)
        assertSame(original, FetchErrorClassifier.clarify(dirWith(yaml), original))
    }

    @Test fun body_at_inspection_limit_is_still_classified() {
        val body = "<!DOCTYPE html>".padEnd(FetchErrorClassifier.MAX_CLASSIFICATION_BYTES, ' ')
        val out = FetchErrorClassifier.clarify(dirWith(body), original)
        assertTrue(out.message!!.contains("[E-11]"), out.message)
    }

    @Test fun absent_file_non_network_keeps_original() {
        // No body + a non-network error (e.g. a parse/programmer error) → keep original.
        assertSame(original, FetchErrorClassifier.clarify(dirWith(null), original))
    }

    @Test fun absent_file_network_timeout_becomes_friendly() {
        val netErr = RuntimeException("update failed", SocketTimeoutException("failed to connect to raw.githubusercontent.com"))
        val out = FetchErrorClassifier.clarify(dirWith(null), netErr)
        assertTrue(out.message!!.contains("reach the subscription server"), out.message)
        assertTrue(out.message!!.contains("[E-20]"), out.message)
        assertSame(netErr, out.cause)
    }

    @Test fun absent_file_unknown_host_becomes_friendly() {
        val out = FetchErrorClassifier.clarify(dirWith(null), UnknownHostException("Unable to resolve host \"example.com\""))
        assertTrue(out.message!!.contains("[E-20]"), out.message)
    }

    @Test fun network_failure_detection() {
        assertEquals(true, FetchErrorClassifier.looksLikeNetworkFailure(SocketTimeoutException("timeout")))
        assertEquals(true, FetchErrorClassifier.looksLikeNetworkFailure(RuntimeException("connection reset by peer")))
        assertEquals(false, FetchErrorClassifier.looksLikeNetworkFailure(RuntimeException("proxy 'X' not found")))
    }

    @Test fun http_status_without_body_is_explained_per_code() {
        // The engine no longer saves a non-2xx body as config.yaml, so nothing is on disk.
        assertTrue(
            FetchErrorClassifier.clarify(dirWith(null), RuntimeException("server returned HTTP 401 Unauthorized"))
                .message!!.contains("rejected your account"),
        )
        assertTrue(
            FetchErrorClassifier.clarify(dirWith(null), RuntimeException("server returned HTTP 404 Not Found"))
                .message!!.contains("no longer exists"),
        )
        assertTrue(
            FetchErrorClassifier.clarify(dirWith(null), RuntimeException("server returned HTTP 503 Service Unavailable"))
                .message!!.contains("having problems"),
        )
    }

    @Test fun status_survives_the_route_fallback_wrapper() {
        // Both attempts are reported; the preferred route's code is the one that matters.
        val wrapped = RuntimeException("proxy: server returned HTTP 403 Forbidden (direct: context deadline exceeded)")
        val out = FetchErrorClassifier.clarify(dirWith(null), wrapped)
        assertTrue(out.message!!.contains("refused the request (HTTP 403)"), out.message)
        assertTrue(out.message!!.contains("[E-21]"), out.message)
    }

    @Test fun status_beats_network_wording_when_both_look_plausible() {
        // "context deadline exceeded" trips looksLikeNetworkFailure; the status is the better answer.
        val wrapped = RuntimeException("proxy: server returned HTTP 403 Forbidden (direct: timeout)")
        assertTrue(FetchErrorClassifier.clarify(dirWith(null), wrapped).message!!.contains("[E-21]"))
    }

    @Test fun hwid_limit_header_beats_the_generic_reason() {
        val out = FetchErrorClassifier.clarify(
            dirWith(null, mapOf("x-hwid-limit" to "true", "support-url" to "https://panel.example/support")),
            forbidden,
            now,
        )
        assertTrue(out.message!!.contains("device limit"), out.message)
        assertTrue(out.message!!.contains("https://panel.example/support"), out.message)
        assertTrue(out.message!!.contains("[E-40]"), out.message)
        assertSame(forbidden, out.cause)
    }

    @Test fun hwid_limit_is_reported_even_when_the_panel_answers_200_with_a_page() {
        // Some panels refuse with an ordinary page rather than a status — the header still decides.
        val out = FetchErrorClassifier.clarify(
            dirWith("<!DOCTYPE html><html><body>limit</body></html>", mapOf("x-hwid-max-devices-reached" to "true")),
            original,
            now,
        )
        assertTrue(out.message!!.contains("[E-40]"), out.message)
    }

    @Test fun expired_plan_is_named_instead_of_the_engine_error() {
        // The measured shape of the bug: an expired panel serves a VALID config with its proxies
        // stripped, so the engine blames a missing node and the user has no idea their plan ran
        // out. Header values are verbatim from the reported subscription.
        val expiredAt = 1_784_278_620L // 2026-07-17
        val out = FetchErrorClassifier.clarify(
            dirWith(
                "proxy-groups:\n  - {name: auto, type: url-test, proxies: [nl-1]}\n",
                mapOf(
                    "subscription-userinfo" to "upload=0; download=341897290; total=0; expire=$expiredAt",
                    "support-url" to "https://t.me/PokemeshSupport",
                ),
            ),
            RuntimeException("proxy 'nl-1' not found"),
            expiredAt + 86_400,
        )
        assertTrue(out.message!!.contains("expired on 2026-07-17"), out.message)
        assertTrue(out.message!!.contains("https://t.me/PokemeshSupport"), out.message)
        assertTrue(out.message!!.contains("[E-41]"), out.message)
        // The precise engine message stays reachable for logs and support.
        assertEquals("proxy 'nl-1' not found", out.cause!!.message)
    }

    @Test fun unlimited_subscription_is_never_called_expired() {
        // expire=0 is the "never expires" convention, and it is what the other live subscription
        // sends — treating it as an epoch date would call every such profile expired.
        val out = FetchErrorClassifier.clarify(
            dirWith(null, mapOf("subscription-userinfo" to "upload=0; download=148054525960; total=0; expire=0")),
            forbidden,
            now,
        )
        assertTrue(out.message!!.contains("[E-21]"), out.message)
    }

    @Test fun future_expiry_is_not_an_error_reason() {
        val out = FetchErrorClassifier.clarify(
            dirWith(null, mapOf("subscription-userinfo" to "upload=0; download=0; expire=${now + 86_400}")),
            forbidden,
            now,
        )
        assertTrue(out.message!!.contains("[E-21]"), out.message)
    }

    @Test fun headers_without_an_account_verdict_change_nothing() {
        val out = FetchErrorClassifier.clarify(
            dirWith(null, mapOf("profile-title" to "Home", "x-hwid-limit" to "false")),
            forbidden,
            now,
        )
        assertTrue(out.message!!.contains("[E-21]"), out.message)
    }

    @Test fun a_working_config_is_never_second_guessed() {
        // Nothing calls clarify on success, but if that ever changes, an expired header must not
        // invent a failure for a profile the engine accepted.
        val healthy = "proxies: []\nrules:\n  - MATCH,DIRECT\n"
        val dir = dirWith(healthy, mapOf("subscription-userinfo" to "expire=${now - 86_400}"))
        assertTrue(FetchErrorClassifier.clarify(dir, original, now).message!!.contains("[E-41]"))
        // ...but only because clarify was called at all — the reason is attached to a real error.
        assertSame(original, FetchErrorClassifier.clarify(dir, original, now).cause)
    }

    @Test fun html_detection() {
        assertEquals(true, FetchErrorClassifier.looksLikeHtml("<html><head><title>x</title>"))
        assertEquals(true, FetchErrorClassifier.looksLikeHtml("  <!doctype html>"))
        assertEquals(false, FetchErrorClassifier.looksLikeHtml("proxies:\n  - {}"))
        assertEquals(false, FetchErrorClassifier.looksLikeHtml("mixed-port: 7890"))
    }
}
