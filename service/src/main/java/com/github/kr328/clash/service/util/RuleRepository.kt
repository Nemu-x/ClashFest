package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.model.RuleSource
import com.github.kr328.clash.service.model.RuleState
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class RuleRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Loads the editor-facing state for a profile. The parse goes through
     * mihomo (Clash.parseProfileSnapshot) — never through Kotlin-side YAML
     * parsing — so rule strings (including AND/OR/SUB-RULE) survive intact.
     */
    fun load(uuid: UUID, profileDir: File): RuleState =
        load(uuid, Clash.parseProfileSnapshot(profileDir))

    /** Same as [load] but reuses an already-parsed snapshot (avoids a 2nd native parse). */
    fun load(uuid: UUID, snapshot: com.github.kr328.clash.core.model.ProfileSnapshot): RuleState {
        val parsed = RuleMapper.parseStateFromSnapshot(snapshot)
        val file = stateFile(uuid)
        if (file.isFile) {
            runCatching {
                val stored = json.decodeFromString(RuleState.serializer(), file.readText())
                val merged = syncProviderRules(stored, parsed)
                save(uuid, merged)
                return merged
            }
        }
        save(uuid, parsed)
        return parsed
    }

    fun save(uuid: UUID, state: RuleState) {
        val file = stateFile(uuid)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(RuleState.serializer(), state))
    }

    fun readStateJson(uuid: UUID, profileDir: File): String {
        val state = load(uuid, profileDir)
        return json.encodeToString(RuleState.serializer(), state)
    }

    fun parseStateJson(stateJson: String): RuleState {
        return json.decodeFromString(RuleState.serializer(), stateJson)
    }

    private fun stateFile(uuid: UUID): File {
        return File(context.importedDir, "$uuid/rules_state.json")
    }

    private fun syncProviderRules(stored: RuleState, incoming: RuleState): RuleState {
        // A deleted MANUAL rule is gone for good: it has no upstream subscription to
        // restore from (unlike a deleted PROVIDER rule, whose soft-delete guards against
        // a sub refresh resurrecting it). Drop such entries up front so they neither
        // resurface on the post-apply reload (the "delete does nothing" bug) nor poison a
        // later same-key re-add by copying their stale deleted=true flag onto it.
        val storedRules = stored.rules.filterNot {
            it.deleted && it.source == RuleSource.MANUAL
        }
        val byKey = storedRules.associateBy(::ruleStorageKey)
        val mergedRules = incoming.rules.mapIndexed { index, rule ->
            val key = ruleStorageKey(rule)
            val old = byKey[key]
            if (old != null) {
                rule.copy(
                    id = old.id,
                    // Trust the STORED source (it's authoritative for MANUAL vs PROVIDER
                    // after the last reconcile). The snapshot now defaults everything to
                    // PROVIDER, so without this a genuine MANUAL rule present in config
                    // would silently flip to PROVIDER on a plain editor open.
                    source = old.source,
                    enabled = old.enabled,
                    deleted = old.deleted,
                    isRestorable = old.isRestorable || rule.isRestorable,
                    order = if (old.source == RuleSource.MANUAL) old.order else index,
                )
            } else {
                rule.copy(order = index)
            }
        }.toMutableList()

        // Keep non-active rules that are absent from current YAML parse:
        // - disabled rules (so toggled OFF can be re-enabled later)
        // - deleted provider rules (restore support)
        // - manual rules that user keeps locally
        val incomingKeys = incoming.rules.map(::ruleStorageKey).toSet()
        val retained = storedRules.filter { rule ->
            val k = ruleStorageKey(rule)
            k !in incomingKeys &&
                (
                    rule.deleted ||
                        !rule.enabled ||
                        rule.source == RuleSource.MANUAL
                    )
        }
        retained.forEach { mergedRules.add(if (it.source == RuleSource.MANUAL) it else it.copy(order = mergedRules.size)) }
        return stored.copy(
            providers = incoming.providers,
            rules = mergedRules,
        )
    }
}

internal fun ruleStorageKey(rule: com.github.kr328.clash.service.model.RuleItem): String =
    if (RuleMapper.isOpaqueType(rule.type)) RuleMapper.toRuleLine(rule)
    else RuleMapper.toRuleLine(rule).uppercase()
