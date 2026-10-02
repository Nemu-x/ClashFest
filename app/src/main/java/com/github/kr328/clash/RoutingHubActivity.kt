package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.design.RoutingHubDesign
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class RoutingHubActivity : BaseActivity<RoutingHubDesign>() {
    override suspend fun main() {
        val design = RoutingHubDesign(this)

        setContentDesign(design)
        refreshSummary(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { launch { refreshSummary(design) } }
                design.requests.onReceive {
                    when (it) {
                        RoutingHubDesign.Request.OpenRules ->
                            launch { openRulesHub() }
                        RoutingHubDesign.Request.OpenPerAppRouting ->
                            startActivity(AccessControlActivity::class.intent)
                        RoutingHubDesign.Request.OpenProxyChain ->
                            startActivity(ProxyChainActivity::class.intent)
                        RoutingHubDesign.Request.AddRule -> launch { openRulesHub(addRule = true) }
                        RoutingHubDesign.Request.OpenProviders -> launch { openRulesHub(expandProviders = true) }
                        RoutingHubDesign.Request.OpenEffectiveRules -> launch { openRulesHub(expandRules = true) }
                        RoutingHubDesign.Request.Refresh -> launch { refreshSummary(design) }
                    }
                }
            }
        }
    }

    private suspend fun openRulesHub(addRule: Boolean = false, expandProviders: Boolean = false, expandRules: Boolean = false) {
        val uuid = withProfile { queryActive()?.takeIf { it.imported }?.uuid }
        val hubIntent = (uuid?.let { RulesHubActivity::class.intent.setUUID(it) }
            ?: RulesHubActivity::class.intent)
        startActivity(hubIntent.putExtra(RulesHubActivity.EXTRA_ADD_RULE, addRule)
            .putExtra(RulesHubActivity.EXTRA_EXPAND_PROVIDERS, expandProviders)
            .putExtra(RulesHubActivity.EXTRA_EXPAND_RULES, expandRules))
    }

    private var summaryGeneration = 0
    private suspend fun refreshSummary(design: RoutingHubDesign) {
        val generation = ++summaryGeneration
        val profile = withProfile { queryActive()?.takeIf { it.imported } }
        if (generation != summaryGeneration) return
        design.patchSummary(profile?.name, null)
        if (profile == null) return
        val result = runCatching {
            val text = withProfile { readRuleEditorBundle(profile.uuid) } ?: error("Missing rule state")
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
                com.github.kr328.clash.service.model.RuleEditorBundle.serializer(), text).state
        }
        if (generation == summaryGeneration) design.patchSummary(profile.name, result.getOrNull(), result.isFailure)
    }
}
