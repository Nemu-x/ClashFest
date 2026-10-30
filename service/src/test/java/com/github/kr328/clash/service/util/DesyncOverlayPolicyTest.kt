package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.DesyncGeneratedConfig
import com.github.kr328.clash.service.model.DesyncProfileSettings
import com.github.kr328.clash.service.model.ProxyHardeningMode
import com.github.kr328.clash.service.model.RuleItem
import com.github.kr328.clash.service.model.RuleState
import com.github.kr328.clash.service.util.DesyncOverlayPolicy.Operator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesyncOverlayPolicyTest {
    private val youtube = DesyncProfileSettings(services = listOf("youtube"))
    private val on = DesyncOverlay(enabled = true, settings = youtube)
    private val off = DesyncOverlay(enabled = false, settings = youtube)

    @Test fun allow_applies_only_what_the_user_turned_on() {
        assertNull(DesyncOverlayPolicy.effective(null, Operator.Allow))
        assertNull(DesyncOverlayPolicy.effective(off, Operator.Allow))
        assertEquals(youtube, DesyncOverlayPolicy.effective(on, Operator.Allow))
    }

    @Test fun operator_on_applies_the_defaults_until_the_user_turns_it_off() {
        assertEquals(DesyncProfileSettings(), DesyncOverlayPolicy.effective(null, Operator.On))
        assertEquals(youtube, DesyncOverlayPolicy.effective(on, Operator.On))
        assertNull(DesyncOverlayPolicy.effective(off, Operator.On))
    }

    @Test fun deny_wins_over_a_stored_choice() {
        assertNull(DesyncOverlayPolicy.effective(on, Operator.Deny))
        assertNull(DesyncOverlayPolicy.effective(null, Operator.Deny))
    }

    @Test fun header_values_map_to_the_operator_policy() {
        assertEquals(Operator.Allow, DesyncOverlayPolicy.operatorOf(null))
        assertEquals(Operator.Allow, DesyncOverlayPolicy.operatorOf("allow"))
        assertEquals(Operator.On, DesyncOverlayPolicy.operatorOf("ON"))
        assertEquals(Operator.Deny, DesyncOverlayPolicy.operatorOf(" deny "))
        assertEquals(Operator.Allow, DesyncOverlayPolicy.operatorOf("garbage"))
    }

    @Test fun runner_composes_without_the_overlay_when_the_engine_refuses() {
        var calls = 0
        val failing = DesyncOverlayPolicy.runner(Operator.Allow) { _, _ -> calls++; DesyncGeneratedConfig(error = "rejected") }
        assertNull(failing.apply("rules: []", on))
        assertEquals(1, calls)
        val skipped = DesyncOverlayPolicy.runner(Operator.Deny) { _, _ -> calls++; DesyncGeneratedConfig(yaml = "x") }
        assertNull(skipped.apply("rules: []", on))
        assertEquals(1, calls, "a denied overlay must not reach the engine")
    }

    @Test fun user_rules_stay_ahead_of_the_desync_rules() {
        val fetched = """
            mixed-port: 7890
            mode: rule
            proxies: []
            proxy-groups: []
            rules:
              - MATCH,DIRECT
        """.trimIndent()
        // Stand-in for the bridge: prepends a desync rule the way the overlay does.
        val runner = DesyncOverlayPolicy.runner(Operator.Allow) { yaml, _ ->
            DesyncGeneratedConfig(yaml = yaml.replace("rules:\n", "rules:\n  - DOMAIN-SUFFIX,youtube.com,DIRECT\n"))
        }
        val layer = UserLayer(
            rules = RuleState(rules = listOf(RuleItem(id = "u1", type = "DOMAIN", value = "mine.test", policy = "DIRECT"))),
            desync = on,
        )
        val geo = GeoDataUrls(geoIp = "", geoSite = "", mmdb = "", asn = "")
        val out = ConfigComposer.compose(fetched, layer, geo, ProxyHardeningMode.Off, desyncRunner = runner)
        val mine = out.indexOf("mine.test")
        val desync = out.indexOf("DOMAIN-SUFFIX,youtube.com")
        val final = out.indexOf("MATCH,DIRECT")
        assertTrue(mine in 0 until desync && desync < final, "order must be user, desync, subscription:\n$out")
    }
}
