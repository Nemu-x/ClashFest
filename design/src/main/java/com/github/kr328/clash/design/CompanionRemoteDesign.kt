package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import com.github.kr328.clash.design.adapter.RemoteProxyPickerSheet
import com.github.kr328.clash.design.databinding.DesignCompanionRemoteBinding
import com.github.kr328.clash.design.model.RemoteActionRow
import com.github.kr328.clash.design.model.RemoteConnectionRow
import com.github.kr328.clash.design.model.RemoteLink
import com.github.kr328.clash.design.model.RemoteOverview
import com.github.kr328.clash.design.model.RemoteProfileRow
import com.github.kr328.clash.design.model.RemoteProviderRow
import com.github.kr328.clash.design.model.RemoteProxyGroup
import com.github.kr328.clash.design.model.RemoteSection
import com.github.kr328.clash.design.model.RemoteSettingRow
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.FlagParser
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.R as MaterialR

/**
 * Remote control for one paired TV. Looks like Home on purpose — power orb, Rule/Global toggle,
 * active profile, Node row and the node picker sheet — but is headed by the TV's name and link
 * state, and every control sends to the TV only. Sections below (profiles, connections,
 * providers, settings, actions) appear only for capabilities the TV advertises.
 */
class CompanionRemoteDesign(context: Context, initialName: String) : Design<CompanionRemoteDesign.Request>(context) {
    sealed class Request {
        object Power : Request()
        data class SetMode(val mode: String) : Request()
        object OpenNodes : Request()
        object UpdateActiveProfile : Request()
        data class ShowSection(val section: RemoteSection) : Request()
        data class SelectProfile(val id: String) : Request()
        /** A null [id] updates every remote profile. */
        data class UpdateProfile(val id: String?) : Request()
        /** A null [id] closes every connection. */
        data class CloseConnection(val id: String?) : Request()
        data class UpdateProvider(val kind: String, val name: String) : Request()
        data class HealthCheckProvider(val name: String) : Request()
        data class SetBool(val key: String, val value: Boolean) : Request()
        data class SetEnum(val key: String, val value: String) : Request()
        data class RunAction(val id: String) : Request()
        data class SelectNode(val group: String, val node: String) : Request()
        data class PingGroup(val group: String) : Request()
        data class PingNode(val group: String, val node: String) : Request()
        object NodesClosed : Request()
        object Retry : Request()
        object Share : Request()
        data class Rename(val name: String) : Request()
        object Forget : Request()
        object OpenDevices : Request()
        object OpenRemoteSettings : Request()
    }

    private val binding = DesignCompanionRemoteBinding.inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private var tvName: String = initialName
    private var sections: List<RemoteSection> = emptyList()
    private var currentSection: RemoteSection? = null
    private var modes: List<String> = emptyList()
    private var bindingMode = false
    private var picker: RemoteProxyPickerSheet? = null
    private var link: RemoteLink = RemoteLink.Connecting
    private var canSelectNodes = false
    private var canPingNodes = false
    private var hasNodes = false
    private var canUpdateProfiles = false
    private var running = false

    val section: RemoteSection?
        get() = currentSection

    val nodePickerOpen: Boolean
        get() = picker?.isShowing == true

    init {
        binding.surface = surface
        binding.remoteTitle.text = context.getString(R.string.remote_tv_title, tvName)
        binding.remotePower.setOnClickListener { requests.trySend(Request.Power) }
        binding.remoteRetry.setOnClickListener { requests.trySend(Request.Retry) }
        binding.remoteMenu.setOnClickListener { showMenu(it) }
        binding.remoteNodeRow.setOnClickListener { if (hasNodes) requests.trySend(Request.OpenNodes) }
        binding.remoteProfileUpdate.setOnClickListener { requests.trySend(Request.UpdateActiveProfile) }
        binding.remoteProfileRow.setOnClickListener {
            if (RemoteSection.Profiles in sections) selectSection(RemoteSection.Profiles)
        }
        binding.remoteModeSegment.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || bindingMode) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.remote_mode_rule -> MODE_RULE
                R.id.remote_mode_global -> MODE_GLOBAL
                R.id.remote_mode_direct -> MODE_DIRECT
                else -> return@addOnButtonCheckedListener
            }
            requests.trySend(Request.SetMode(mode))
        }
        applyPower(false)
        applyLink()
    }

    /** What the TV advertises; hides every control whose capability is absent (§8.3). */
    suspend fun setCapabilities(
        power: Boolean,
        mode: Boolean,
        nodes: Boolean,
        select: Boolean,
        ping: Boolean,
        profileUpdate: Boolean,
        traffic: Boolean,
        sections: List<RemoteSection>,
    ) = withContext(Dispatchers.Main) {
        binding.remotePower.isEnabled = power
        binding.remoteModeRow.visibility = if (mode) View.VISIBLE else View.GONE
        binding.remoteModeDivider.visibility = if (mode) View.VISIBLE else View.GONE
        hasNodes = nodes
        canSelectNodes = select
        canPingNodes = ping
        canUpdateProfiles = profileUpdate
        binding.remoteNodeChevron.visibility = if (nodes) View.VISIBLE else View.INVISIBLE
        binding.remoteProfileChevron.visibility =
            if (RemoteSection.Profiles in sections) View.VISIBLE else View.GONE
        if (!traffic) binding.remoteSpeed.visibility = View.GONE
        if (this@CompanionRemoteDesign.sections != sections) {
            this@CompanionRemoteDesign.sections = sections
            rebuildSectionChips()
        }
    }

    suspend fun setLink(state: RemoteLink) = withContext(Dispatchers.Main) {
        link = state
        applyLink()
        picker?.setLive(state == RemoteLink.Live)
    }

    suspend fun setName(name: String) = withContext(Dispatchers.Main) {
        tvName = name
        binding.remoteTitle.text = context.getString(R.string.remote_tv_title, name)
    }

    suspend fun setOverview(overview: RemoteOverview) = withContext(Dispatchers.Main) {
        applyPower(overview.power)
        modes = overview.modes
        bindingMode = true
        binding.remoteModeRule.visibility = if (MODE_RULE in modes) View.VISIBLE else View.GONE
        binding.remoteModeGlobal.visibility = if (MODE_GLOBAL in modes) View.VISIBLE else View.GONE
        binding.remoteModeDirect.visibility = if (MODE_DIRECT in modes) View.VISIBLE else View.GONE
        when (overview.mode) {
            MODE_RULE -> binding.remoteModeSegment.check(R.id.remote_mode_rule)
            MODE_GLOBAL -> binding.remoteModeSegment.check(R.id.remote_mode_global)
            MODE_DIRECT -> binding.remoteModeSegment.check(R.id.remote_mode_direct)
            else -> binding.remoteModeSegment.clearChecked()
        }
        bindingMode = false

        binding.remoteProfileValue.text = overview.profileName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.not_selected)
        binding.remoteProfileMeta.text = overview.profileMeta.orEmpty()
        binding.remoteProfileMeta.visibility = if (overview.profileMeta.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.remoteProfileUpdate.visibility =
            if (overview.profileUpdatable && canUpdateProfiles) View.VISIBLE else View.GONE

        bindNode(overview.node)
    }

    /** Live ↓/↑ speed, like Home's; null hides it. */
    suspend fun setSpeed(down: String?, up: String?) = withContext(Dispatchers.Main) {
        if (down == null || up == null || !running) {
            binding.remoteSpeed.visibility = View.GONE
        } else {
            binding.remoteSpeed.text = context.getString(R.string.main_speed_fmt, down, up)
            binding.remoteSpeed.visibility = View.VISIBLE
        }
    }

    // --- sections ------------------------------------------------------------------------------

    suspend fun setProfiles(rows: List<RemoteProfileRow>) = withContext(Dispatchers.Main) {
        if (currentSection != RemoteSection.Profiles) return@withContext
        val updatableCount = rows.count { it.updatable }
        sectionBar(
            context.getString(R.string.remote_profiles_count, rows.size),
            if (canUpdateProfiles && updatableCount > 0) context.getString(R.string.remote_update_all) else null,
        ) { requests.trySend(Request.UpdateProfile(null)) }
        fillRows(rows, R.string.remote_profiles_empty) { row, item ->
            row.title.text = item.name
            row.subtitle(item.meta)
            row.check.visibility = if (item.active) View.VISIBLE else View.GONE
            row.action(row.action1, if (canUpdateProfiles && item.updatable) R.drawable.ic_baseline_sync else null, R.string.profile_force_update) {
                requests.trySend(Request.UpdateProfile(item.id))
            }
            row.root.setOnClickListener { if (!item.active) requests.trySend(Request.SelectProfile(item.id)) }
        }
    }

    suspend fun setConnections(rows: List<RemoteConnectionRow>, totals: String) = withContext(Dispatchers.Main) {
        if (currentSection != RemoteSection.Connections) return@withContext
        sectionBar(
            context.getString(R.string.remote_connections_count, rows.size) +
                if (totals.isNotEmpty()) " · $totals" else "",
            if (rows.isNotEmpty()) context.getString(R.string.remote_close_all) else null,
        ) { confirmCloseAll() }
        val shown = rows.take(MAX_CONNECTION_ROWS)
        fillRows(shown, R.string.remote_connections_empty) { row, item ->
            row.title.text = item.title
            row.subtitle(listOf(item.network.uppercase(), item.subtitle).filter { it.isNotBlank() }.joinToString(" · "))
            row.action(row.action1, R.drawable.ic_baseline_close, R.string.remote_close_connection) {
                requests.trySend(Request.CloseConnection(item.id))
            }
            row.root.isClickable = false
        }
    }

    suspend fun setProviders(rows: List<RemoteProviderRow>) = withContext(Dispatchers.Main) {
        if (currentSection != RemoteSection.Providers) return@withContext
        sectionBar(context.getString(R.string.remote_providers_count, rows.size), null) {}
        fillRows(rows, R.string.remote_providers_empty) { row, item ->
            row.title.text = item.name
            row.subtitle(item.meta)
            row.action(row.action1, if (item.healthCheckable) R.drawable.ic_speedometer_ping else null, R.string.remote_health_check) {
                requests.trySend(Request.HealthCheckProvider(item.name))
            }
            row.action(row.action2, if (item.updatable) R.drawable.ic_baseline_sync else null, R.string.remote_update_provider) {
                requests.trySend(Request.UpdateProvider(item.kind, item.name))
            }
            row.root.isClickable = false
        }
    }

    suspend fun setSettings(rows: List<RemoteSettingRow>) = withContext(Dispatchers.Main) {
        if (currentSection != RemoteSection.Settings) return@withContext
        sectionBar(context.getString(R.string.remote_settings_hint), null) {}
        fillRows(rows, R.string.remote_settings_empty) { row, item ->
            row.title.text = item.label
            val checked = item.checked
            if (checked != null) {
                row.switch.visibility = View.VISIBLE
                row.switch.setOnCheckedChangeListener(null)
                row.switch.isChecked = checked
                row.switch.setOnCheckedChangeListener { _, value -> requests.trySend(Request.SetBool(item.key, value)) }
                row.root.setOnClickListener { row.switch.toggle() }
            } else {
                row.value.visibility = View.VISIBLE
                row.value.text = item.value.orEmpty()
                row.root.setOnClickListener { pickEnum(item) }
            }
        }
    }

    suspend fun setActions(rows: List<RemoteActionRow>) = withContext(Dispatchers.Main) {
        if (currentSection != RemoteSection.Actions) return@withContext
        sectionBar(context.getString(R.string.remote_actions_hint), null) {}
        fillRows(rows, R.string.remote_actions_empty) { row, item ->
            row.title.text = item.label
            row.action(row.action1, R.drawable.ic_baseline_flash_on, R.string.remote_run_action) {
                requests.trySend(Request.RunAction(item.id))
            }
            row.root.setOnClickListener { requests.trySend(Request.RunAction(item.id)) }
        }
    }

    /** The current section could not be loaded; [message] says why. */
    suspend fun setSectionError(section: RemoteSection, message: String) = withContext(Dispatchers.Main) {
        if (currentSection != section) return@withContext
        binding.remoteSectionList.removeAllViews()
        binding.remoteSectionCard.visibility = View.GONE
        binding.remoteSectionEmpty.text = message
        binding.remoteSectionEmpty.visibility = View.VISIBLE
    }

    // --- node picker ---------------------------------------------------------------------------

    suspend fun openNodePicker(groups: List<RemoteProxyGroup>, focusNode: String?) = withContext(Dispatchers.Main) {
        picker?.dismiss()
        picker = RemoteProxyPickerSheet(
            context = context,
            tvName = tvName,
            canSelect = canSelectNodes,
            canPing = canPingNodes,
            onSelect = { g, n -> requests.trySend(Request.SelectNode(g, n)) },
            onPingGroup = { g -> requests.trySend(Request.PingGroup(g)) },
            onPingNode = { g, n -> requests.trySend(Request.PingNode(g, n)) },
        ).also {
            it.onDismiss = {
                if (picker === it) picker = null
                requests.trySend(Request.NodesClosed)
            }
            it.show(groups, focusNode, link == RemoteLink.Live)
        }
    }

    suspend fun updateNodePicker(groups: List<RemoteProxyGroup>) = withContext(Dispatchers.Main) {
        picker?.takeIf { it.isShowing }?.update(groups)
    }

    suspend fun groupPingFinished(group: String) = withContext(Dispatchers.Main) {
        picker?.groupPingFinished(group)
    }

    suspend fun nodePingFinished(group: String, node: String) = withContext(Dispatchers.Main) {
        picker?.nodePingFinished(group, node)
    }

    suspend fun closeNodePicker() = withContext(Dispatchers.Main) {
        picker?.dismiss()
        picker = null
    }

    // --- internals -----------------------------------------------------------------------------

    private fun applyLink() {
        val (textRes, live) = when (link) {
            RemoteLink.Connecting -> R.string.remote_link_connecting to false
            RemoteLink.Live -> R.string.remote_link_live to true
            RemoteLink.Reconnecting -> R.string.remote_link_reconnecting to false
            RemoteLink.Unreachable -> R.string.remote_link_unreachable to false
        }
        binding.remoteLink.text = context.getString(textRes)
        val tint = context.resolveThemedColor(
            when (link) {
                RemoteLink.Live -> MaterialR.attr.colorPrimary
                RemoteLink.Unreachable -> MaterialR.attr.colorError
                else -> MaterialR.attr.colorOnSurfaceVariant
            },
        )
        binding.remoteLink.setTextColor(tint)
        binding.remoteLinkDot.backgroundTintList = ColorStateList.valueOf(tint)
        val unreachable = link == RemoteLink.Unreachable
        binding.remoteError.visibility = if (unreachable) View.VISIBLE else View.GONE
        binding.remoteContent.visibility = if (unreachable) View.GONE else View.VISIBLE
        binding.remoteContent.alpha = if (live || link == RemoteLink.Connecting) 1f else 0.6f
    }

    /** Home's power-orb colours (MainDesignPower.applyPowerVisuals) without the ambient animation. */
    private fun applyPower(on: Boolean) {
        running = on
        val button = binding.remotePower
        val bg = context.resolveThemedColor(if (on) MaterialR.attr.colorPrimary else MaterialR.attr.colorSurfaceContainerHighest)
        val icon = context.resolveThemedColor(if (on) MaterialR.attr.colorOnPrimary else MaterialR.attr.colorOnSurfaceVariant)
        button.backgroundTintList = ColorStateList.valueOf(bg)
        button.iconTint = ColorStateList.valueOf(icon)
        button.elevation = (if (on) 10f else 7f) * context.resources.displayMetrics.density
        button.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
        button.strokeColor = ColorStateList.valueOf(ColorUtils.blendARGB(bg, Color.WHITE, if (on) 0.36f else 0.10f))
        binding.remotePowerSheen.alpha = if (on) 1f else 0.22f
        binding.remotePowerHalo.alpha = if (on) 0.34f else 0.12f
        binding.remotePowerHalo.backgroundTintList = ColorStateList.valueOf(
            if (on) bg else context.resolveThemedColor(MaterialR.attr.colorSurfaceVariant),
        )
        binding.remoteStatusLabel.text = context.getString(if (on) R.string.main_status_connected else R.string.main_status_stopped)
        binding.remoteStatusLabel.setBackgroundResource(if (on) R.drawable.bg_m3_status_chip else R.drawable.bg_m3_status_chip_neutral)
        binding.remoteStatusLabel.setTextColor(
            context.resolveThemedColor(if (on) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurfaceVariant),
        )
        button.contentDescription = context.getString(if (on) R.string.remote_power_off else R.string.remote_power_on)
        if (!on) binding.remoteSpeed.visibility = View.GONE
    }

    private fun bindNode(node: String?) {
        val flag = FlagParser.parse(node)
        val clean = node?.let { n ->
            flag?.let { n.removePrefix(it.emoji).trimStart(' ', '|', '-', '_', '.', ':').ifBlank { n } } ?: n
        }
        binding.remoteNodeValue.text = clean?.takeIf { it.isNotBlank() } ?: context.getString(R.string.main_node_choose)
        val image = binding.remoteNodeFlag
        val emoji = binding.remoteNodeFlagEmoji
        if (flag == null) {
            emoji.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (9 * context.resources.displayMetrics.density).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.setImageResource(R.drawable.ic_baseline_language)
            image.imageTintList = ColorStateList.valueOf(context.resolveThemedColor(MaterialR.attr.colorPrimary))
            return
        }
        fun apply(sizePx: Int) {
            val bitmap = FlagDrawableLoader.loadBitmap(context, flag.code, sizePx.coerceAtLeast(1))
            if (bitmap != null) {
                image.setPadding(0, 0, 0, 0)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.imageTintList = null
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                emoji.visibility = View.GONE
            } else {
                image.visibility = View.GONE
                emoji.text = flag.emoji
                emoji.visibility = View.VISIBLE
            }
        }
        // The flag view starts GONE in the layout, and a GONE view is never laid out, so
        // doOnLayout would never fire and the flag stayed blank. Make it take space first.
        image.visibility = View.VISIBLE
        emoji.visibility = View.GONE
        if (image.width > 0) apply(image.width) else image.doOnLayout { apply(it.width) }
    }

    private fun rebuildSectionChips() {
        val group = binding.remoteSections
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val visible = sections.isNotEmpty()
        binding.remoteSectionsScroll.visibility = if (visible) View.VISIBLE else View.GONE
        binding.remoteSectionBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.remoteSectionCard.visibility = if (visible) View.VISIBLE else View.GONE
        binding.remoteSectionEmpty.visibility = View.GONE
        if (!visible) {
            currentSection = null
            return
        }
        sections.forEach { s ->
            val chip = Chip(context).apply {
                setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, MaterialR.style.Widget_Material3_Chip_Filter))
                id = View.generateViewId()
                tag = s
                text = context.getString(sectionLabel(s))
                isCheckable = true
                isCheckedIconVisible = false
            }
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            val s = ids.firstOrNull()?.let { g.findViewById<View>(it)?.tag as? RemoteSection } ?: return@setOnCheckedStateChangeListener
            if (s != currentSection) showSection(s)
        }
        val initial = currentSection?.takeIf { it in sections } ?: sections.first()
        // Fresh chips: force a reload of the section even when it is the one already shown.
        currentSection = null
        selectSection(initial)
    }

    private fun selectSection(s: RemoteSection) {
        val group = binding.remoteSections
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            if (chip.tag == s) {
                if (!chip.isChecked) chip.isChecked = true else if (currentSection != s) showSection(s)
                return
            }
        }
    }

    private fun showSection(s: RemoteSection) {
        currentSection = s
        binding.remoteSectionTitle.text = context.getString(R.string.remote_loading)
        binding.remoteSectionAction.visibility = View.GONE
        binding.remoteSectionList.removeAllViews()
        binding.remoteSectionCard.visibility = View.GONE
        binding.remoteSectionEmpty.visibility = View.GONE
        requests.trySend(Request.ShowSection(s))
    }

    private fun sectionLabel(s: RemoteSection): Int = when (s) {
        RemoteSection.Profiles -> R.string.remote_section_profiles
        RemoteSection.Connections -> R.string.remote_section_connections
        RemoteSection.Providers -> R.string.remote_section_providers
        RemoteSection.Settings -> R.string.remote_section_settings
        RemoteSection.Actions -> R.string.remote_section_actions
    }

    private fun sectionBar(title: String, action: String?, onAction: () -> Unit) {
        binding.remoteSectionTitle.text = title
        if (action == null) {
            binding.remoteSectionAction.visibility = View.GONE
        } else {
            binding.remoteSectionAction.visibility = View.VISIBLE
            binding.remoteSectionAction.text = action
            binding.remoteSectionAction.setOnClickListener { onAction() }
        }
    }

    private class Row(val root: View) {
        val title: TextView = root.findViewById(R.id.remote_row_title)
        private val subtitleView: TextView = root.findViewById(R.id.remote_row_subtitle)
        val value: TextView = root.findViewById(R.id.remote_row_value)
        val check: View = root.findViewById(R.id.remote_row_check)
        val switch: MaterialSwitch = root.findViewById(R.id.remote_row_switch)
        val action1: ImageButton = root.findViewById(R.id.remote_row_action1)
        val action2: ImageButton = root.findViewById(R.id.remote_row_action2)

        fun subtitle(text: String?) {
            subtitleView.text = text.orEmpty()
            subtitleView.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        fun action(button: ImageButton, icon: Int?, description: Int, onClick: () -> Unit) {
            if (icon == null) {
                button.visibility = View.GONE
                return
            }
            button.visibility = View.VISIBLE
            button.setImageResource(icon)
            button.contentDescription = button.context.getString(description)
            button.setOnClickListener { onClick() }
        }
    }

    private fun <T> fillRows(items: List<T>, emptyRes: Int, bind: (Row, T) -> Unit) {
        val list = binding.remoteSectionList
        list.removeAllViews()
        if (items.isEmpty()) {
            binding.remoteSectionCard.visibility = View.GONE
            binding.remoteSectionEmpty.text = context.getString(emptyRes)
            binding.remoteSectionEmpty.visibility = View.VISIBLE
            return
        }
        binding.remoteSectionEmpty.visibility = View.GONE
        binding.remoteSectionCard.visibility = View.VISIBLE
        items.forEachIndexed { i, item ->
            if (i > 0) {
                list.addView(
                    com.google.android.material.divider.MaterialDivider(context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            val m = (16 * context.resources.displayMetrics.density).toInt()
                            marginStart = m
                            marginEnd = m
                        }
                    },
                )
            }
            val view = context.layoutInflater.inflate(R.layout.item_remote_row, list, false)
            bind(Row(view), item)
            list.addView(view)
        }
    }

    private fun pickEnum(item: RemoteSettingRow) {
        val options = item.options
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(context)
            .setTitle(item.label)
            .setSingleChoiceItems(options.toTypedArray(), options.indexOf(item.value)) { dialog, which ->
                dialog.dismiss()
                val picked = options[which]
                if (picked != item.value) requests.trySend(Request.SetEnum(item.key, picked))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmCloseAll() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.remote_close_all)
            .setMessage(context.getString(R.string.remote_close_all_message, tvName))
            .setPositiveButton(R.string.remote_close_all) { _, _ -> requests.trySend(Request.CloseConnection(null)) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, MENU_SHARE, 0, R.string.companion_action_share)
            menu.add(0, MENU_RENAME, 1, R.string.companion_action_rename)
            menu.add(0, MENU_FORGET, 2, R.string.remote_forget)
            menu.add(1, MENU_DEVICES, 3, R.string.remote_other_devices)
            menu.add(1, MENU_REMOTE_SETTINGS, 4, R.string.remote_control_settings)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SHARE -> requests.trySend(Request.Share)
                    MENU_RENAME -> promptRename()
                    MENU_FORGET -> confirmForget()
                    MENU_DEVICES -> requests.trySend(Request.OpenDevices)
                    MENU_REMOTE_SETTINGS -> requests.trySend(Request.OpenRemoteSettings)
                }
                true
            }
        }.show()
    }

    private fun promptRename() {
        val input = EditText(context).apply {
            setText(tvName)
            setSelection(text.length)
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.companion_action_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) requests.trySend(Request.Rename(name))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.remote_forget)
            .setMessage(context.getString(R.string.remote_forget_message, tvName))
            .setPositiveButton(R.string.remote_forget) { _, _ -> requests.trySend(Request.Forget) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MODE_RULE = "rule"
        const val MODE_GLOBAL = "global"
        const val MODE_DIRECT = "direct"
        const val MENU_SHARE = 1
        const val MENU_RENAME = 2
        const val MENU_FORGET = 3
        const val MENU_DEVICES = 4
        const val MENU_REMOTE_SETTINGS = 5

        /** Plain rows, not a recycler: beyond this a phone screen is not the place to read them. */
        const val MAX_CONNECTION_ROWS = 200
    }
}
