package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignRoutingHubBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class RoutingHubDesign(context: Context) : Design<RoutingHubDesign.Request>(context) {
    enum class Request {
        OpenRules,
        OpenPerAppRouting,
        OpenProxyChain,
        AddRule,
        OpenProviders,
        OpenEffectiveRules,
        Refresh,
    }

    private val binding = DesignRoutingHubBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.header.screenTitle.text = context.getString(R.string.nav_routing)

        binding.cardRules.setOnClickListener { requests.trySend(Request.OpenRules) }
        binding.cardProxyChain.setOnClickListener { requests.trySend(Request.OpenProxyChain) }
        binding.cardPerApp.setOnClickListener { requests.trySend(Request.OpenPerAppRouting) }
        binding.routingAdd.setOnClickListener { requests.trySend(Request.AddRule) }
        binding.cardProviders.setOnClickListener { requests.trySend(Request.OpenProviders) }
        binding.cardEffective.setOnClickListener { requests.trySend(Request.OpenEffectiveRules) }
        binding.routingCounts.setOnClickListener { requests.trySend(Request.Refresh) }
    }

    fun patchSummary(profile: String?, state: com.github.kr328.clash.service.model.RuleState?, error: Boolean = false) {
        val loading = profile != null && state == null && !error
        binding.routingLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.routingCounts.visibility = if (loading) View.GONE else View.VISIBLE
        binding.routingProfile.text = profile ?: context.getString(R.string.routing_no_subscription)
        binding.routingAdd.isEnabled = profile != null && state != null
        binding.routingCounts.text = when {
            error -> context.getString(R.string.routing_load_error)
            profile == null -> context.getString(R.string.routing_no_subscription_hint)
            state == null -> context.getString(R.string.routing_open_to_load)
            else -> context.getString(R.string.routing_counts_fmt,
                state.rules.count { it.enabled && !it.deleted && it.source == com.github.kr328.clash.service.model.RuleSource.MANUAL },
                state.rules.count { it.enabled && !it.deleted && it.source == com.github.kr328.clash.service.model.RuleSource.PROVIDER },
                state.providers.count { it.enabled })
        }
        binding.routingCounts.isClickable = error
        binding.routingCounts.isFocusable = error
    }
}
