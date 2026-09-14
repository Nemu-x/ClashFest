package com.github.kr328.clash.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionMetadataParserTest {
    private fun parse(vararg headers: Pair<String, String>): SubscriptionMetadata {
        val map = headers.associate { (k, v) -> k.lowercase() to v }
        return SubscriptionMetadataFetcher.parseHeaders { key -> map[key.lowercase()] }
    }

    @Test
    fun announce_turns_literal_backslash_n_into_line_breaks() {
        // Header values are single-line; panels write the two characters `\n` to ask for a break.
        val meta = parse("announce" to "Maintenance tonight\\nBack at 06:00")
        assertEquals("Maintenance tonight\nBack at 06:00", meta.announcement)
    }

    @Test
    fun announce_without_escapes_is_untouched() {
        assertEquals("Plain text", parse("announce" to "Plain text").announcement)
    }

    @Test
    fun policy_headers_and_web_page_url_parse() {
        val meta = parse(
            "x-network-stack" to "gvisor",
            "x-bypass-preset" to "ru",
            "profile-web-page-url" to "https://panel.example/account",
        )
        assertEquals("gvisor", meta.networkStack)
        assertEquals("ru", meta.bypassPreset)
        assertEquals("https://panel.example/account", meta.profileWebPageUrl)
    }

    @Test
    fun empty_headers_give_empty_metadata() {
        val meta = parse()
        assertEquals(true, meta.isEmpty())
        assertNull(meta.announcement)
    }
}
