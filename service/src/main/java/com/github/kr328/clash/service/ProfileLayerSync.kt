package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProfileSnapshot
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ProfileMigration
import com.github.kr328.clash.service.util.UserLayerStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.RuleApplyService
import java.io.File
import java.util.*

/**
 * Owns the overlay base bootstrap for [ProfileManager]: makes sure a profile's
 * `subscription.yaml` base exists and runs the one-time legacy migration into the user layer
 * before an editor records its intent.
 */
internal class ProfileLayerSync(
    private val context: Context,
    private val store: ServiceStore,
    private val ruleApplyService: RuleApplyService,
    private val userLayerStore: UserLayerStore,
) {
    /** Geo-data URLs resolved from current settings (rule rendering / composition need them). */
    /**
     * Ensure the `subscription.yaml` base exists and run the one-time legacy migration (baked edits
     * in `config.yaml` → `user_layer.json`) on first contact. E-01/E-05: the editors now write their
     * intent to the user layer directly, so we no longer re-extract the whole layer from config.yaml
     * on every edit — the layer, not config.yaml, is the source of truth. (Name kept for its call
     * sites; effectively "ensure base + migrate once".)
     */
    internal fun syncLayerFromConfig(uuid: UUID) {
        val dir = File(context.importedDir, uuid.toString())
        if (!File(dir, "config.yaml").isFile) return
        val parse: (File) -> com.github.kr328.clash.core.model.ProfileSnapshot? =
            { d -> runCatching { Clash.parseProfileSnapshot(d) }.getOrNull() }
        ProfileMigration.migrateIfNeeded(
            profileDir = dir,
            uuid = uuid,
            store = userLayerStore,
            rulesStateJson = runCatching { ruleApplyService.readStateJson(uuid) }.getOrNull(),
            dnsHostsManaged = store.isDnsHostsManaged(uuid),
            tunnelsManaged = store.isTunnelsManaged(uuid),
            parseSnapshot = parse,
        )
    }
}
