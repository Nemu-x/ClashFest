package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.RuleItem
import com.github.kr328.clash.service.model.RuleSource

/** Plain rule lines; no YAML document or subscription fields are accepted here. */
object RuleTextInput {
    const val MAX_LENGTH = 65536
    const val MAX_RULES = 1000
    const val DISABLED_PREFIX = "# disabled: "

    fun parse(text: String): List<RuleItem> {
        require(text.length <= MAX_LENGTH) { "Rule text exceeds 64 KiB" }
        val rules = mutableListOf<RuleItem>()
        text.lineSequence().forEachIndexed { index, input ->
            val trimmed = input.trim()
            val disabled = trimmed.startsWith(DISABLED_PREFIX)
            if (trimmed.isEmpty() || (trimmed.startsWith('#') && !disabled)) return@forEachIndexed
            val line = if (disabled) trimmed.removePrefix(DISABLED_PREFIX) else trimmed.removePrefix("- ").trim()
            require(line.none { it.isISOControl() }) { "Line ${index + 1}: control character" }
            val type = line.substringBefore(',').trim().uppercase()
            require(type.matches(Regex("[A-Z][A-Z0-9-]*"))) { "Line ${index + 1}: invalid rule type" }
            var depth = 0
            val structural = RuleMapper.isOpaqueType(type)
            val parts = mutableListOf<String>()
            var start = 0
            line.forEachIndexed { offset, char ->
                when (char) {
                    '(' -> if (structural) depth++
                    ')' -> if (structural) depth--
                    ',' -> if (depth == 0) {
                        parts += line.substring(start, offset).trim()
                        start = offset + 1
                    }
                }
                require(depth >= 0) { "Line ${index + 1}: unbalanced parentheses" }
            }
            require(depth == 0) { "Line ${index + 1}: unbalanced parentheses" }
            parts += line.substring(start).trim()
            require(parts.size >= if (type == "MATCH") 2 else 3) { "Line ${index + 1}: missing value or target" }
            require(parts.none { it.isEmpty() }) { "Line ${index + 1}: empty field" }
            if (type == "MATCH") require(parts.size == 2) { "Line ${index + 1}: invalid MATCH" }
            require(rules.size < MAX_RULES) { "At most $MAX_RULES rules" }
            val rule = requireNotNull(RuleMapper.parseRuleLine(line, rules.size))
            rules += rule.copy(source = RuleSource.MANUAL, enabled = !disabled, isRestorable = false)
        }
        return rules
    }

    fun format(rules: List<RuleItem>): String = rules.filter { !it.deleted }.joinToString("\n") {
        (if (it.enabled) "" else DISABLED_PREFIX) + RuleMapper.toRuleLine(it)
    }
}
