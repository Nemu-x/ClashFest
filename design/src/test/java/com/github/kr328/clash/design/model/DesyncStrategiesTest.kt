package com.github.kr328.clash.design.model

import com.github.kr328.clash.core.model.DesyncPreset
import com.github.kr328.clash.core.model.DesyncPresetCatalog
import com.github.kr328.clash.core.model.DesyncProxyStatus
import com.github.kr328.clash.core.model.DesyncService
import com.github.kr328.clash.core.model.DesyncStatus
import com.github.kr328.clash.service.model.DesyncProfileSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesyncStrategiesTest {
    private fun preset(id: String, name: String, options: String) =
        DesyncPreset(id, name, "$name description", 0, Json.parseToJsonElement(options) as JsonObject)

    // Option shapes as cfa/native/desync serves them (presets.go Options).
    private val tlsrec1 = preset("s1", "tlsrec-1s", """{"tlsrec":"1+s"}""")
    private val tlsrec3 = preset("s2", "tlsrec-3s", """{"tlsrec":"3+s"}""")
    private val split1 = preset("s3", "split-1", """{"split":"1"}""")
    private val combined = preset("s4", "tlsrec-1s-split-1", """{"tlsrec":"1+s","split":"1"}""")
    private val random = preset("s5", "random-split", """{"random":{"split":[{"from":"1","to":"120"}]}}""")
    private val disorder = preset("s6", "disorder-1", """{"split":"1","disorder":true}""")
    private val oob = preset("s7", "oob-1", """{"split":"1","oob":true}""")
    private val fake = preset("s8", "disorder-1-fake-ttl5", """{"split":"1","disorder":true,"fake":{"ttl":5}}""")
    private val waitSent = preset("s9", "split-1s-wait-sent", """{"split":"1+s","split-wait":"sent"}""")
    private val udp = preset("u1", "udp-zero-ttl5", """{"udp":{"ttl":5,"repeats":3}}""")

    private val catalog = DesyncPresetCatalog(
        catalogVersion = "test",
        defaultTcp = "s1",
        tcp = listOf(tlsrec1, fake, split1, tlsrec3, combined, random, disorder, oob, waitSent),
        udp = listOf(udp),
    )

    private val services = listOf(
        DesyncService("youtube", "YouTube", listOf("youtube.com")),
        DesyncService("discord", "Discord", listOf("discord.com")),
        DesyncService("telegram", "Telegram (web)", listOf("t.me")),
    )

    @Test
    fun presets_are_classified_by_the_keys_they_set() {
        assertEquals(DesyncStrategyKind.RecordSplit, DesyncStrategies.kindOf(tlsrec1))
        assertEquals(DesyncStrategyKind.PacketSplit, DesyncStrategies.kindOf(split1))
        assertEquals(DesyncStrategyKind.PacketSplit, DesyncStrategies.kindOf(waitSent))
        assertEquals(DesyncStrategyKind.Combined, DesyncStrategies.kindOf(combined))
        assertEquals(DesyncStrategyKind.Random, DesyncStrategies.kindOf(random))
        assertEquals(DesyncStrategyKind.Disorder, DesyncStrategies.kindOf(disorder))
        assertEquals(DesyncStrategyKind.OutOfBand, DesyncStrategies.kindOf(oob))
        // A decoy wins over the split / disorder it is combined with.
        assertEquals(DesyncStrategyKind.Decoy, DesyncStrategies.kindOf(fake))
        assertEquals(DesyncStrategyKind.UdpDecoy, DesyncStrategies.kindOf(udp, udp = true))
        assertEquals(DesyncStrategyKind.PacketSplit, DesyncStrategies.kindOf(preset("x", "x", """{"disorder":false,"split":"2"}""")))
    }

    @Test
    fun groups_follow_kind_order_and_keep_catalog_order_inside() {
        val groups = DesyncStrategies.group(catalog.tcp)
        assertEquals(
            listOf(
                DesyncStrategyKind.RecordSplit, DesyncStrategyKind.PacketSplit, DesyncStrategyKind.Combined,
                DesyncStrategyKind.Random, DesyncStrategyKind.Disorder, DesyncStrategyKind.OutOfBand,
                DesyncStrategyKind.Decoy,
            ),
            groups.map { it.first },
        )
        val record = groups.first().second
        assertEquals(listOf("s1" to 1, "s2" to 2), record.map { it.preset.id to it.variant })
        assertEquals(listOf("s3" to 1, "s9" to 2), groups[1].second.map { it.preset.id to it.variant })
    }

    @Test
    fun effective_strategy_is_the_catalog_default_when_automatic() {
        assertEquals("s1", DesyncStrategies.effectiveTcp(catalog, "")?.preset?.id)
        assertEquals(tlsrec3, DesyncStrategies.effectiveTcp(catalog, "s2")?.preset)
        assertEquals(2, DesyncStrategies.effectiveTcp(catalog, "s2")?.variant)
        assertEquals(DesyncStrategyKind.UdpDecoy, DesyncStrategies.entryFor(catalog, "u1")?.kind)
        assertNull(DesyncStrategies.effectiveTcp(catalog, "s-gone"))
        assertNull(DesyncStrategies.effectiveTcp(catalog.copy(defaultTcp = ""), ""))
    }

    @Test
    fun form_drops_what_the_library_no_longer_offers() {
        val stored = DesyncProfileSettings(
            services = listOf("discord", "removed-service", "youtube"),
            customDomains = listOf("example.org"),
            strategyPresetId = "s-from-an-older-catalog",
            udpPresetId = "u-gone",
        )
        val settings = DesyncSettingsForm.from(stored, services, catalog).toSettings()

        // Catalog order, unknown service dropped; unknown presets fall back to automatic / off.
        assertEquals(listOf("youtube", "discord"), settings.services)
        assertEquals(listOf("example.org"), settings.customDomains)
        assertEquals(DesyncProfileSettings.AUTOMATIC, settings.strategyPresetId)
        assertEquals("", settings.udpPresetId)
    }

    @Test
    fun form_keeps_valid_choices_and_maps_edits_to_settings() {
        val form = DesyncSettingsForm.from(
            DesyncProfileSettings(services = listOf("youtube"), strategyPresetId = "s6", udpPresetId = "u1"),
            services,
            catalog,
        )
        assertTrue(form.isSelected("youtube"))
        assertFalse(form.isSelected("discord"))

        form.setSelected("telegram", true)
        form.setSelected("youtube", false)
        form.setSelected("not-a-service", true)
        form.customDomains = listOf(" a.example ", "", "a.example", "b.example")

        assertEquals(
            DesyncProfileSettings(
                services = listOf("telegram"),
                customDomains = listOf("a.example", "b.example"),
                strategyPresetId = "s6",
                udpPresetId = "u1",
            ),
            form.toSettings(),
        )
    }

    @Test
    fun new_profile_defaults_are_youtube_and_discord_with_automatic_strategy() {
        val settings = DesyncSettingsForm.from(DesyncProfileSettings(), services, catalog).toSettings()
        assertEquals(listOf("youtube", "discord"), settings.services)
        assertEquals(DesyncProfileSettings.AUTOMATIC, settings.strategyPresetId)
    }

    @Test
    fun form_without_catalog_keeps_automatic_and_reports_empty() {
        val form = DesyncSettingsForm.from(DesyncProfileSettings(strategyPresetId = "s1"), services, null)
        assertEquals(DesyncProfileSettings.AUTOMATIC, form.toSettings().strategyPresetId)
        form.setSelected("youtube", false)
        form.setSelected("discord", false)
        assertTrue(form.isEmpty)
        form.customDomains = listOf("  ")
        assertTrue(form.isEmpty)
        form.customDomains = listOf("example.org")
        assertFalse(form.isEmpty)
    }

    @Test
    fun runtime_summary() {
        val loaded = DesyncStatus(
            listOf(
                DesyncProxyStatus(name = "desync-youtube", desync = JsonObject(emptyMap())),
                DesyncProxyStatus(name = "desync-discord", desync = JsonObject(emptyMap())),
            ),
        )
        assertEquals(DesyncRuntimeSummary.Stopped, DesyncRuntimeSummary.of(false, loaded))
        assertEquals(DesyncRuntimeSummary.NotLoaded, DesyncRuntimeSummary.of(true, null))
        assertEquals(DesyncRuntimeSummary.NotLoaded, DesyncRuntimeSummary.of(true, DesyncStatus()))
        assertEquals(DesyncRuntimeSummary.Active(2), DesyncRuntimeSummary.of(true, loaded))
    }
}
