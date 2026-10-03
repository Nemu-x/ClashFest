package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.RuleSource
import com.github.kr328.clash.service.model.RuleState
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RuleRoutingEditorTest {
    @Test fun editing_target_preserves_no_resolve_and_src() {
        val rule = RuleTextInput.parse("IP-CIDR,10.0.0.0/8,DIRECT,no-resolve,src").single()
        assertEquals("IP-CIDR,10.0.0.0/8,REJECT,no-resolve,src", RuleMapper.toRuleLine(rule.copy(policy = "REJECT")))
        assertEquals("DOMAIN,example.com,DIRECT", RuleMapper.toRuleLine(rule.copy(type = "DOMAIN", value = "example.com")))
    }

    @Test fun text_roundtrip_keeps_disabled_rules_and_manual_provider_references() {
        val rules = RuleTextInput.parse("# disabled: DOMAIN,example.com,DIRECT\n- RULE-SET,local,DIRECT\nAND,((NETWORK,UDP),(DST-PORT,443)),REJECT")
        assertFalse(rules[0].enabled)
        assertTrue(rules.all { it.source == RuleSource.MANUAL })
        assertEquals(RuleTextInput.format(rules), RuleTextInput.format(RuleTextInput.parse(RuleTextInput.format(rules))))
    }

    @Test fun logical_rules_and_parameters_have_distinct_storage_identities() {
        val rules = RuleTextInput.parse("AND,((NETWORK,UDP),(DST-PORT,443)),REJECT\nAND,((NETWORK,TCP),(DST-PORT,443)),REJECT\nIP-CIDR,10.0.0.0/8,DIRECT\nIP-CIDR,10.0.0.0/8,DIRECT,no-resolve")
        assertEquals(4, rules.map(::ruleStorageKey).toSet().size)
    }

    @Test fun invalid_or_oversized_import_is_rejected_as_a_whole() {
        listOf("DOMAIN,good.example,DIRECT\nDOMAIN,broken", "MATCH,DIRECT,src", "AND,((NETWORK,UDP),REJECT", "DOMAIN,\u0000,DIRECT", "x".repeat(RuleTextInput.MAX_LENGTH + 1), (0..RuleTextInput.MAX_RULES).joinToString("\n") { "DOMAIN,$it.example,DIRECT" }).forEach {
            assertTrue(runCatching { RuleTextInput.parse(it) }.isFailure)
        }
    }

    @Test fun engine_oracle_accepts_serialized_logical_rules_and_rejects_unknown_target() {
        val root = listOf(File(".."), File(".")).first { File(it, "core/src/main/golang/go.mod").isFile }.canonicalFile
        val base = "proxies: []\nrules:\n  - MATCH,DIRECT\n"
        val text = "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\nAND,((NETWORK,UDP),(DST-PORT,443)),REJECT\nOR,((DOMAIN,one.example),(DOMAIN,two.example)),DIRECT\nNOT,((NETWORK,UDP)),DIRECT\nMATCH,DIRECT"
        val state = RuleState(rules = RuleTextInput.parse(text))
        val yaml = RuleMapper.mergeStateIntoConfig(base, state, GeoDataUrls("", "", "", ""))
        val expected = "proxies: []\nrules:\n" + text.lines().joinToString("\n") { "  - $it" } + "\n"
        val invalid = RuleMapper.mergeStateIntoConfig(base, RuleState(rules = RuleTextInput.parse("AND,((NETWORK,UDP),(DST-PORT,443)),missing-target")), GeoDataUrls("", "", "", ""))
        val dir = Files.createTempDirectory("mikan-routing-oracle").toFile()
        try {
            File(dir, "expected.yaml").writeText(expected)
            File(dir, "actual.yaml").writeText(yaml)
            File(dir, "invalid.yaml").writeText(invalid)
            val output = File(dir, "oracle.log")
            val process = ProcessBuilder("go", "run", "-tags", "foss,with_gvisor,cmfa",
                File(root, "service/src/test/golang/rule_oracle.go").path, dir.path)
                .directory(File(root, "core/src/main/golang")).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("Routing engine oracle timed out")
            }
            assertEquals(output.readText(), 0, process.exitValue())
        } finally { dir.deleteRecursively() }
    }
}
