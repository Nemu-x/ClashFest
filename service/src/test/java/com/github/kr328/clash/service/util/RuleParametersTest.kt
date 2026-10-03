package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Trailing rule parameters (no-resolve, src) must survive the
 * RuleState round trip and take part in the storage identity.
 */
class RuleParametersTest {

    private fun parse(line: String, order: Int = 0) =
        requireNotNull(RuleMapper.parseRuleLine(line, order))

    @Test
    fun toRuleLine_roundTripsParametersVerbatim() {
        val line = "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve"
        assertEquals(line, RuleMapper.toRuleLine(parse(line)))
    }

    @Test
    fun toRuleLine_keepsParametersWhenPolicyChanges() {
        val rule = parse("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve,src")
        assertEquals(
            "IP-CIDR,10.0.0.0/8,REJECT,no-resolve,src",
            RuleMapper.toRuleLine(rule.copy(policy = "REJECT")),
        )
    }

    @Test
    fun toRuleLine_dropsParametersWhenTypeChanges() {
        val rule = parse("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve")
        assertEquals(
            "DOMAIN,example.com,DIRECT",
            RuleMapper.toRuleLine(rule.copy(type = "DOMAIN", value = "example.com")),
        )
    }

    @Test
    fun ruleStorageKey_distinguishesParametersAndLogicalBodies() {
        val lines = listOf(
            "AND,((NETWORK,UDP),(DST-PORT,443)),REJECT",
            "AND,((NETWORK,TCP),(DST-PORT,443)),REJECT",
            "IP-CIDR,10.0.0.0/8,DIRECT",
            "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
        )
        val keys = lines.mapIndexed { index, line -> ruleStorageKey(parse(line, index)) }
        assertEquals(4, keys.toSet().size)
    }

    @Test
    fun ruleStorageKey_ignoresCaseForPlainRules() {
        assertEquals(
            ruleStorageKey(parse("domain,Example.com,direct")),
            ruleStorageKey(parse("DOMAIN,example.com,DIRECT")),
        )
    }
}
