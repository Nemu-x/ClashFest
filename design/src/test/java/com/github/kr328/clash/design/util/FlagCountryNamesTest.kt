package com.github.kr328.clash.design.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlagCountryNamesTest {
    @Test fun russianServerNamesResolveBundledFlagCodes() {
        mapOf("Нидерланды · Amsterdam" to "NL", "Германия · Frankfurt" to "DE",
            "Финляндия · Helsinki" to "FI", "Швеция · Stockholm" to "SE",
            "Франция · Paris" to "FR", "Великобритания · London" to "GB").forEach { (name, code) ->
            assertEquals(code, countryCodeFromName(name))
        }
    }
    @Test fun shortCodesCannotMatchInsideOtherWords() {
        assertNull(countryCodeFromName("Status Plus"))
        assertNull(countryCodeFromName("business node"))
        assertEquals("AU", countryCodeFromName("Australia · Sydney"))
    }
    @Test fun explicitCountryNamesAreCaseInsensitiveAndDelimiterIndependent() {
        assertEquals("NL", countryCodeFromName("#NETHERLANDS-01"))
        assertEquals("GB", countryCodeFromName("United Kingdom / London"))
        assertNull(countryCodeFromName("Автовыбор"))
    }
}
