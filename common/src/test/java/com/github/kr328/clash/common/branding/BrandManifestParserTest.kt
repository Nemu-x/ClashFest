package com.github.kr328.clash.common.branding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the header → [BrandManifest] mapping via the connection-free [BrandManifestParser.parse].
 * Doubles as a brand-input audit: adversarial / malformed operator headers must be sanitised
 * (not crash, not pass through) — every field routes through [BrandValidation].
 */
class BrandManifestParserTest {

    private fun parse(headers: Map<String, String?>): BrandManifest =
        BrandManifestParser.parse { key -> headers[key] }

    @Test
    fun allHeadersAbsent_yieldsEmptyManifest_noCrash() {
        val m = BrandManifestParser.parse { null }
        assertNull(m.name)
        assertNull(m.accentColor)
        assertNull(m.logoUrl)
        assertNull(m.websiteUrl)
        assertNull(m.enabled)
        assertNull(m.hideRouting)
    }

    @Test
    fun validHeaders_mapToManifestFields() {
        val m = parse(
            mapOf(
                BrandHeaders.NAME to "Acme VPN",
                BrandHeaders.ACCENT_COLOR to "#aabbcc",
                BrandHeaders.WEBSITE_URL to "https://acme.example.com",
                BrandHeaders.LOGO_URL to "https://acme.example.com/logo.png",
                BrandHeaders.TELEGRAM_URL to "t.me/acme",
                BrandHeaders.BRANDING_ENABLED to "true",
                BrandHeaders.HIDE_ROUTING to "yes",
            ),
        )
        assertEquals("Acme VPN", m.name)
        assertEquals("#AABBCC", m.accentColor)
        assertEquals("https://acme.example.com", m.websiteUrl)
        assertEquals("https://acme.example.com/logo.png", m.logoUrl)
        assertEquals("https://t.me/acme", m.telegramUrl) // t.me promoted to https
        assertEquals(true, m.enabled)
        assertEquals(true, m.hideRouting)
    }

    @Test
    fun mojibake_utf8HeaderReadAsLatin1_isRecovered() {
        // HttpURLConnection.getHeaderField hands us UTF-8 bytes decoded as ISO-8859-1. Simulate that
        // for a Cyrillic + flag-emoji display name and confirm we recover the original.
        val original = "Пётр 🇸🇪"
        val asLatin1 = String(original.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals(original, BrandManifestParser.decodeHeaderUtf8(asLatin1))
    }

    @Test
    fun asciiHeader_roundTripsUnchanged() {
        val url = "https://example.com/logo.png?ref=abc"
        val asLatin1 = String(url.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals(url, BrandManifestParser.decodeHeaderUtf8(asLatin1))
    }

    @Test
    fun hideGlobalMode_isPolicy_appliesWithoutBrandingEnabled() {
        // Operator sends ONLY the policy header — no X-Branding-Enabled, no identity.
        val m = parse(mapOf(BrandHeaders.HIDE_GLOBAL_MODE to "true"))
        assertEquals(true, m.hideGlobalMode)
        assertNull(m.enabled)
        // Policy applies without branding; it is NOT visual brand identity.
        assertTrue(m.hasPolicy())
        assertTrue(!m.hasBrandIdentity())
        assertTrue(!m.isEmpty())
    }

    @Test
    fun hideGlobalMode_survivesBrandingDisabled_asPolicy() {
        val m = parse(
            mapOf(
                BrandHeaders.HIDE_GLOBAL_MODE to "yes",
                BrandHeaders.BRANDING_ENABLED to "false",
                BrandHeaders.NAME to "Acme", // cosmetic — must not count as active while disabled
            ),
        )
        assertEquals(true, m.hideGlobalMode)
        assertEquals(false, m.enabled)
        assertTrue(m.hasPolicy())
        assertTrue(!m.hasBrandIdentity()) // enabled=false → no visual brand
    }

    @Test
    fun malformedValues_areSanitisedToNull() {
        val m = parse(
            mapOf(
                BrandHeaders.BRANDING_ENABLED to "true",
                BrandHeaders.ACCENT_COLOR to "red",                 // not #RRGGBB
                BrandHeaders.LOGO_URL to "http://insecure/logo.png", // http rejected for logo
                BrandHeaders.WEBSITE_URL to "javascript:alert(1)",   // bad scheme
                BrandHeaders.HIDE_ROUTING to "garbage",              // not a boolean
            ),
        )
        assertNull(m.accentColor)
        assertNull(m.logoUrl)
        assertNull(m.websiteUrl)
        assertNull(m.hideRouting)
    }

    @Test
    fun nameIsTrimmedQuotedAndTruncated() {
        val quoted = parse(mapOf(BrandHeaders.BRANDING_ENABLED to "true", BrandHeaders.NAME to "  \"Acme\"  "))
        assertEquals("Acme", quoted.name)

        // Over NAME_MAX_LENGTH (spaces keep it off the base64 decode path).
        val long = "This Brand Name Is Definitely Way Over The Limit"
        val truncated = parse(mapOf(BrandHeaders.BRANDING_ENABLED to "true", BrandHeaders.NAME to long))
        assertEquals(BrandValidation.NAME_MAX_LENGTH, truncated.name!!.length)
    }

    @Test
    fun controlCharsInGreetingAreStripped() {
        val m = parse(mapOf(BrandHeaders.BRANDING_ENABLED to "true", BrandHeaders.GREETING to "Hi\u0000 there\u0007"))
        assertEquals("Hi there", m.greeting)
    }

    @Test
    fun blankHeaderValuesYieldNull() {
        val m = parse(
            mapOf(
                BrandHeaders.BRANDING_ENABLED to "true",
                BrandHeaders.NAME to "   ",
                BrandHeaders.ACCENT_COLOR to "",
            ),
        )
        assertNull(m.name)
        assertNull(m.accentColor)
    }

    @Test
    fun booleanFlagsParseTruthyAndFalsy() {
        val m = parse(
            mapOf(
                BrandHeaders.HIDE_ROUTING to "1",
                BrandHeaders.SHOW_OPERATOR_TAB to "off",
                BrandHeaders.BRANDING_ENABLED to "ON",
            ),
        )
        assertEquals(true, m.hideRouting)
        assertEquals(false, m.showOperatorTab)
        assertEquals(true, m.enabled)
    }

    @Test
    fun parse_preservesAlreadyDecodedUtf8() {
        // Values sourced from fetch-headers.json (written by the Go fetch) are
        // already correct UTF-8. The decoded entry point `parse` must pass them
        // through untouched. Guards the ProfileManager.updateFlow /
        // syncSubscriptionMetadata file paths: routing these through
        // parseHttpHeaders would apply ISO-8859-1 recovery and mangle any
        // character above U+00FF (Greek here; same class as Cyrillic names).
        val name = "Ωμέγα VPN"
        val m = parse(mapOf(BrandHeaders.BRANDING_ENABLED to "true", BrandHeaders.NAME to name))
        assertEquals(name, m.name)
    }

    @Test
    fun parseHttpHeaders_isForRawTransportValuesOnly() {
        // Characterization: parseHttpHeaders recovers UTF-8 that a transport
        // decoded as ISO-8859-1 - the inverse operation of the test above.
        // Together they document why file-sourced headers MUST use `parse`.
        val original = "Ωμέγα VPN"
        val asLatin1 = String(original.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val m = BrandManifestParser.parseHttpHeaders { key ->
            when (key) {
                BrandHeaders.NAME -> asLatin1
                BrandHeaders.BRANDING_ENABLED -> "true"
                else -> null
            }
        }
        assertEquals(original, m.name)
    }

    @Test
    fun parseNeverThrowsOnArbitraryInput() {
        // A spread of hostile values across every header must not throw.
        val hostile = mapOf<String, String?>(
            BrandHeaders.NAME to "\u0000\u0007\"'",
            BrandHeaders.ACCENT_COLOR to "#zzzzzz",
            BrandHeaders.LOGO_URL to "ftp://x/y z",
            BrandHeaders.WEBSITE_URL to "   ",
            BrandHeaders.BRANDING_ENABLED to "maybe",
            BrandHeaders.GREETING to "😀".repeat(200), // long emoji
        )
        val m = BrandManifestParser.parse { hostile[it] }
        // No assertion on values beyond "did not throw"; sanity-check a couple.
        assertNull(m.accentColor)
        assertTrue(m.greeting == null || m.greeting!!.length <= BrandValidation.GREETING_MAX_LENGTH)
    }

    @Test
    fun identityAndLogoAreIgnoredWithoutExplicitOptIn() {
        val m = parse(
            mapOf(
                BrandHeaders.NAME to "Unsolicited brand",
                BrandHeaders.LOGO_URL to "https://tracker.example/logo.png",
            ),
        )
        assertNull(m.name)
        assertNull(m.logoUrl)
        assertTrue(m.isEmpty())
    }

    // --- Operator policy: X-Brand-Lock-Config-Script ---
    // A user config script rewrites proxies / dns / rules wholesale, so on a managed subscription
    // it is a way around operator policy. Like hide-global-mode this is a restriction, not a skin,
    // and therefore must apply without X-Branding-Enabled and survive the kill-switch.

    @Test
    fun lockConfigScript_appliesWithoutBrandingEnabled() {
        val m = parse(mapOf(BrandHeaders.LOCK_CONFIG_SCRIPT to "true"))
        assertEquals(true, m.lockConfigScript)
        assertTrue(m.hasPolicy())
        assertTrue("policy alone must not brand the app", !m.hasBrandIdentity())
    }

    @Test
    fun lockConfigScript_survivesBrandingKillSwitch() {
        val m = parse(
            mapOf(
                BrandHeaders.BRANDING_ENABLED to "false",
                BrandHeaders.LOCK_CONFIG_SCRIPT to "true",
                BrandHeaders.NAME to "Example VPN",
            )
        )
        assertEquals("policy must outlive X-Branding-Enabled: false", true, m.lockConfigScript)
        assertTrue(m.hasPolicy())
        assertEquals("cosmetic identity must be dropped", null, m.name)
    }

    @Test
    fun lockConfigScript_absentOrFalse_doesNotLock() {
        assertEquals(null, parse(emptyMap()).lockConfigScript)
        val explicit = parse(mapOf(BrandHeaders.LOCK_CONFIG_SCRIPT to "false"))
        assertEquals(false, explicit.lockConfigScript)
        assertTrue("false must not count as policy", !explicit.hasPolicy())
    }

    @Test
    fun lockConfigScript_readAlongsideFullBranding() {
        val m = parse(
            mapOf(
                BrandHeaders.BRANDING_ENABLED to "true",
                BrandHeaders.NAME to "Example VPN",
                BrandHeaders.LOCK_CONFIG_SCRIPT to "true",
            )
        )
        assertEquals(true, m.lockConfigScript)
        assertEquals("Example VPN", m.name)
    }
}
