package com.github.kr328.clash.design

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.core.model.DesyncCapabilities
import com.github.kr328.clash.core.model.DesyncPresetCatalog
import com.github.kr328.clash.core.model.DesyncService
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.DesyncSettingsForm
import com.github.kr328.clash.design.model.DesyncStrategies
import com.github.kr328.clash.design.model.DesyncStrategyEntry
import com.github.kr328.clash.design.preference.ClickablePreference
import com.github.kr328.clash.design.preference.PreferenceScreen
import com.github.kr328.clash.design.preference.TextAdapter
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.editableTextList
import com.github.kr328.clash.design.preference.plainTips
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.switch
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.DesyncLabels
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * Desync-only profile screen. [Mode.Setup] (new profile): what desync is, services, custom domains,
 * Create. [Mode.Edit] (an existing profile): services, custom domains, the TCP strategy grouped by
 * kind with "Automatic" first, the optional UDP decoy, and what the library implements on this
 * device. [Mode.Overlay] (a subscription profile): desync on top of the subscription, an on/off
 * switch above the same sections as Edit. Edits stay in the [DesyncSettingsForm] until Create / Save.
 */
class DesyncSettingsDesign(
    context: Context,
    private val mode: Mode,
) : Design<DesyncSettingsDesign.Request>(context) {
    enum class Mode { Setup, Edit, Overlay }

    enum class Request { Submit }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val statusView = TextView(context).apply {
        visibility = View.GONE
        setTextAppearance(R.style.TextAppearance_App_BodyMedium)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
    }

    private val submitButton = MaterialButton(context).apply {
        isAllCaps = false
        setText(if (mode == Mode.Setup) R.string.desync_create else R.string.desync_save)
        setOnClickListener { requests.trySend(Request.Submit) }
    }

    private val strategyRows = mutableListOf<Pair<String, ClickablePreference>>()
    private val udpRows = mutableListOf<Pair<String, ClickablePreference>>()

    var form: DesyncSettingsForm? = null
        private set

    /** [Mode.Overlay]: whether desync on top of the subscription is on. */
    var overlayEnabled: Boolean = false

    init {
        binding.surface = surface
        binding.header.screenTitle.text = (context as? Activity)?.title?.toString().orEmpty()
        binding.content.addView(container)
    }

    /** Builds the screen for [form]; [catalog] / [capabilities] null hide the sections they feed. */
    fun bind(
        form: DesyncSettingsForm,
        services: List<DesyncService>,
        catalog: DesyncPresetCatalog?,
        capabilities: DesyncCapabilities?,
    ) {
        this.form = form
        strategyRows.clear()
        udpRows.clear()
        container.removeAllViews()

        val screen = preferenceScreen(context) {
            if (mode == Mode.Setup) {
                tips(R.string.desync_setup_intro)
            }
            if (mode == Mode.Overlay) {
                tips(R.string.desync_overlay_intro)
                val enabled = Toggle({ overlayEnabled }, { overlayEnabled = it })
                switch(value = enabled::value) {
                    title = context.getString(R.string.desync_overlay_enable)
                }
            }

            category(R.string.desync_services)
            services.forEach { service ->
                val toggle = Toggle({ form.isSelected(service.id) }, { form.setSelected(service.id, it) })
                switch(value = toggle::value) {
                    title = service.name
                }
            }

            val domains = Domains(form)
            editableTextList(
                value = domains::value,
                adapter = TextAdapter.String,
                title = R.string.desync_custom_domains,
                placeholder = R.string.desync_custom_domains_placeholder,
            )

            if (mode != Mode.Setup && catalog != null) {
                strategySection(form, catalog)
                udpSection(form, catalog)
            }

            if (mode != Mode.Setup && capabilities != null) {
                deviceSection(capabilities, catalog)
            }
        }

        container.addView(screen.root)
        container.addView(statusView)
        container.addView(
            submitButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                val m = (16 * context.resources.displayMetrics.density).toInt()
                setMargins(m, m, m, 0)
            },
        )
        renderSelection()
    }

    // "Automatic" is a fallback group per route over catalog.autoTcp, checked by the engine; with
    // fewer than two candidates on this device it is the catalog's defaultTcp.
    private fun PreferenceScreen.strategySection(form: DesyncSettingsForm, catalog: DesyncPresetCatalog) {
        category(R.string.desync_strategy)
        tips(R.string.desync_strategy_intro)

        val default = DesyncStrategies.entryFor(catalog, catalog.defaultTcp)
        strategyRows += "" to row(
            title = context.getString(R.string.desync_strategy_automatic),
            summary = if (catalog.autoTcp.size >= 2) {
                context.getString(R.string.desync_strategy_automatic_multi_summary, catalog.autoTcp.size)
            } else {
                context.getString(
                    R.string.desync_strategy_automatic_summary,
                    DesyncLabels.strategyName(context, default),
                )
            },
        ) {
            form.strategyPresetId = ""
            renderSelection()
        }

        DesyncStrategies.group(catalog.tcp).forEach { (kind, entries) ->
            category(DesyncLabels.kindTitle(kind))
            entries.forEach { entry ->
                strategyRows += entry.preset.id to entryRow(entry) {
                    form.strategyPresetId = entry.preset.id
                    renderSelection()
                }
            }
        }
    }

    private fun PreferenceScreen.udpSection(form: DesyncSettingsForm, catalog: DesyncPresetCatalog) {
        if (catalog.udp.isEmpty()) return
        category(R.string.desync_udp)
        udpRows += "" to row(context.getString(R.string.desync_udp_off), null) {
            form.udpPresetId = ""
            renderSelection()
        }
        DesyncStrategies.group(catalog.udp, udp = true).forEach { (_, entries) ->
            entries.forEach { entry ->
                udpRows += entry.preset.id to entryRow(entry) {
                    form.udpPresetId = entry.preset.id
                    renderSelection()
                }
            }
        }
    }

    private fun PreferenceScreen.deviceSection(caps: DesyncCapabilities, catalog: DesyncPresetCatalog?) {
        category(R.string.desync_device)
        tips(R.string.desync_device_intro)
        val lines = DesyncLabels.capabilityLines(context, caps).toMutableList()
        catalog?.skipped?.size?.takeIf { it > 0 }?.let {
            lines += context.getString(R.string.desync_skipped_count, it)
        }
        caps.libraryVersion.takeIf { it.isNotBlank() }?.let {
            lines += context.getString(R.string.desync_library_version, it)
        }
        plainTips(lines.joinToString("\n"))
    }

    private fun PreferenceScreen.entryRow(entry: DesyncStrategyEntry, onClick: () -> Unit): ClickablePreference =
        row(DesyncLabels.strategyName(context, entry), DesyncLabels.strategyDetail(entry), onClick)

    private fun PreferenceScreen.row(title: String, summary: String?, onClick: () -> Unit): ClickablePreference =
        clickable(title = R.string.desync_strategy, icon = R.drawable.ic_baseline_check) {
            this.title = title
            this.summary = summary
            clicked(onClick)
        }

    /** Check mark on the chosen TCP and UDP rows; the icon slot stays so the rows line up. */
    private fun renderSelection() {
        val f = form ?: return
        val check = context.getDrawableCompat(R.drawable.ic_baseline_check)
        strategyRows.forEach { (id, row) -> row.icon = if (id == f.strategyPresetId) check else null }
        udpRows.forEach { (id, row) -> row.icon = if (id == f.udpPresetId) check else null }
    }

    fun setBusy(busy: Boolean) {
        submitButton.isEnabled = !busy
    }

    fun showMessage(message: String?, isError: Boolean = true) {
        if (message.isNullOrBlank()) {
            statusView.visibility = View.GONE
            return
        }
        statusView.text = message
        statusView.setTextColor(
            MaterialColors.getColor(
                statusView,
                if (isError) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary,
            ),
        )
        statusView.visibility = View.VISIBLE
    }

    fun showMessage(@StringRes message: Int, isError: Boolean = true) =
        showMessage(context.getString(message), isError)

    private class Toggle(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {
        var value: Boolean
            get() = read()
            set(v) = write(v)
    }

    private class Domains(private val form: DesyncSettingsForm) {
        var value: List<String>?
            get() = form.customDomains.takeIf { it.isNotEmpty() }
            set(v) {
                form.customDomains = v.orEmpty()
            }
    }
}
