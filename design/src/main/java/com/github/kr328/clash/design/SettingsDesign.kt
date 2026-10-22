package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.design.databinding.DesignSettingsBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class SettingsDesign(context: Context) : Design<SettingsDesign.Request>(context) {
    enum class Request {
        StartApp,
        StartCompanion,
        StartGeo,
        StartNetwork,
        StartMetaFeatures,
        StartDnsHosts,
        StartTunnels,
        StartOverride,
    }

    private val binding = DesignSettingsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val ui = UiStore(context)

    init {
        binding.self = this
        binding.header.screenTitle.text = context.getString(R.string.main_advanced_settings)
        // TV: the same TV glyph as the Home "Remote control" row (this device is the TV here).
        if (context.isTelevision()) {
            binding.companionIcon.setImageResource(R.drawable.ic_baseline_tv)
            // On the TV this entry is the agent side: the same wording as the Home row.
            binding.companionSummary.setText(R.string.companion_tv_home_summary)
        }
        applyExperimentalVisibility()
    }

    /**
     * Re-read the experimental/expert gates and show/hide their entries. Called on
     * init AND whenever the screen returns to the foreground, so toggling a switch in
     * App settings reflects here without leaving and re-entering Advanced.
     */
    fun applyExperimentalVisibility() {
        // TV: these are YAML editors, offered only with "Advanced tools on TV" on.
        val tools = !context.isTelevision() || ui.tvAdvancedTools
        binding.cardDnsHosts.visibility =
            if (tools && ui.dnsHostsEnabled) View.VISIBLE else View.GONE
        binding.cardTunnels.visibility =
            if (tools && ui.tunnelsEnabled) View.VISIBLE else View.GONE
        binding.cardOverride.visibility =
            if (tools && ui.expertEnabled) View.VISIBLE else View.GONE
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
