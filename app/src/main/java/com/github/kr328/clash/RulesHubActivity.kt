package com.github.kr328.clash

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import androidx.core.view.WindowCompat
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProfileSnapshot
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.RuleEditSheet
import com.github.kr328.clash.design.RulesHubDesign
import com.github.kr328.clash.service.model.RuleItem
import com.github.kr328.clash.service.model.RuleEditorBundle
import com.github.kr328.clash.service.model.RuleState
import com.github.kr328.clash.service.model.RuleSource
import com.github.kr328.clash.service.util.RuleTextInput
import com.github.kr328.clash.service.util.RuleMapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.github.kr328.clash.service.util.ProxyGroupsYamlPreview
import com.github.kr328.clash.service.util.RuleValidator
import com.github.kr328.clash.util.showRuleStatePreview
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

class RulesHubActivity : BaseActivity<RulesHubDesign>() {
    companion object {
        const val EXTRA_EXPAND_PROVIDERS = "expand_providers"
        const val EXTRA_ADD_RULE = "add_rule"
        const val EXTRA_EXPAND_RULES = "expand_rules"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var uuid: UUID? = null
    private var profileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override suspend fun main() {
        val design = RulesHubDesign(this)
        setContentDesign(design)

        val expandProviders = intent.getBooleanExtra(EXTRA_EXPAND_PROVIDERS, false)
        val profileUuid = intent.uuid ?: withProfile { queryActive()?.uuid }
        if (profileUuid == null) {
            withContext(Dispatchers.Main) { design.showNoProfile() }
        } else {
            uuid = profileUuid
            val profile = withProfile { queryByUUID(profileUuid) }
            profileName = profile?.name.orEmpty()
            val loaded = loadInto(design, expandProviders)
            if (loaded && intent.getBooleanExtra(EXTRA_ADD_RULE, false)) {
                withContext(Dispatchers.Main) { showRuleEditSheet(design, null) }
                intent.removeExtra(EXTRA_ADD_RULE)
            }
        }

        while (isActive) {
            select<Unit> {
                design.requests.onReceive { req ->
                    val id = uuid ?: return@onReceive
                    when (req) {
                        RulesHubDesign.Request.Save -> launch { onSave(design, id) }
                        RulesHubDesign.Request.AddManual -> withContext(Dispatchers.Main) {
                            showRuleEditSheet(design, rule = null)
                        }
                        RulesHubDesign.Request.ImportRules -> withContext(Dispatchers.Main) { showRuleTextEditor(design, false) }
                        RulesHubDesign.Request.EditSource -> withContext(Dispatchers.Main) { showRuleTextEditor(design, true) }
                        RulesHubDesign.Request.Reload -> launch { loadInto(design, expandProviders) }
                        is RulesHubDesign.Request.EditManual -> withContext(Dispatchers.Main) {
                            val rule = design.findRule(req.ruleId) ?: return@withContext
                            showRuleEditSheet(design, rule = rule)
                        }
                        is RulesHubDesign.Request.ToggleRule -> withContext(Dispatchers.Main) {
                            design.mutateRule(req.ruleId) { it.copy(enabled = req.enabled) }
                        }
                        is RulesHubDesign.Request.RestoreRule -> withContext(Dispatchers.Main) {
                            design.mutateRule(req.ruleId) { it.copy(deleted = false, enabled = true) }
                        }
                        is RulesHubDesign.Request.ReorderManual -> withContext(Dispatchers.Main) {
                            design.reorderManualById(req.fromId, req.toId)
                        }
                    }
                }
            }
        }
    }

    private fun showRuleTextEditor(design: RulesHubDesign, replace: Boolean) {
        val state = design.readState()
        val manual = state.rules.filter { it.source == RuleSource.MANUAL }
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.routing_rule_lines)
            helperText = getString(R.string.routing_text_hint)
        }
        val input = TextInputEditText(field.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP
            if (replace) setText(RuleTextInput.format(manual))
        }
        field.addView(input)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(field)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (replace) R.string.routing_source_title else R.string.routing_paste_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (replace) R.string.save else R.string.rules_hub_add_rule, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching {
                    val rules = RuleTextInput.parse(input.text.toString())
                    if (!replace) require(rules.isNotEmpty())
                    val previous = manual.groupBy { RuleMapper.toRuleLine(it) }.mapValues { it.value.toMutableList() }
                    val edited = rules.map { new ->
                        val old = previous[RuleMapper.toRuleLine(new)]?.firstOrNull { !it.deleted }
                        if (old != null) {
                            previous[RuleMapper.toRuleLine(new)]?.remove(old)
                            new.copy(id = old.id)
                        } else new
                    }
                    val candidate = if (replace) state.copy(rules = edited + state.rules.filter { it.source != RuleSource.MANUAL })
                        else state.copy(rules = rules + state.rules)
                    RuleValidator.validate(candidate, design.policyOptions().toSet())
                    if (replace) design.replaceState(candidate) else design.addManualRules(rules)
                }
                if (result.isSuccess) dialog.dismiss()
                else field.error = getString(R.string.routing_invalid_text)
            }
        }
        dialog.show()
    }

    private suspend fun loadInto(design: RulesHubDesign, expandProviders: Boolean): Boolean {
        val id = uuid ?: return false
        // One snapshot parse for both the state and the policy picker (the config
        // is parsed natively, so doing it once instead of twice halves open time).
        val bundleJson = runCatching { withProfile { readRuleEditorBundle(id) } }.getOrNull()
        val bundle = bundleJson
            ?.let { runCatching { json.decodeFromString(RuleEditorBundle.serializer(), it) }.getOrNull() }
        if (bundle == null) {
            withContext(Dispatchers.Main) { design.showLoadFailure() }
            return false
        }
        // Last successful fetch per rule provider, as the running core reports it
        // (mihomo's Fetcher.UpdatedAt). Only the live core knows it, so with the
        // VPN stopped the rows show the interval alone.
        val providerUpdatedAt: Map<String, Long> = if (clashRunning) {
            runCatching { withClash { queryProviders() } }.getOrNull()
                ?.filter { it.type == Provider.Type.Rule && it.updatedAt > 0 }
                ?.associate { it.name to it.updatedAt }
                .orEmpty()
        } else {
            emptyMap()
        }
        withContext(Dispatchers.Main) {
            design.bind(profileName, bundle.state, bundle.policies, expandProviders, providerUpdatedAt)
            if (intent.getBooleanExtra(EXTRA_EXPAND_RULES, false)) design.expandSubscriptionRules()
        }
        return true
    }

    private fun showRuleEditSheet(design: RulesHubDesign, rule: RuleItem?) {
        val sheet = RuleEditSheet(
            context = this,
            policyOptions = design.policyOptions(),
            knownPolicies = design.knownPolicies(),
            onConfirm = { result ->
                if (rule == null) {
                    design.addManualRule(
                        RuleEditSheet.newManualRule(
                            result = result,
                            order = 0,
                            id = UUID.randomUUID().toString(),
                        ),
                    )
                } else {
                    design.updateManualRule(rule.id, result)
                }
            },
            onDelete = rule?.let { existing -> { design.deleteManualRule(existing.id) } },
            onPickApp = { selected -> launch { showInstalledAppPicker(design, selected) } },
        )
        if (rule == null) sheet.showAdd() else sheet.showEdit(rule)
    }

    private var appPickerBusy = false
    private suspend fun showInstalledAppPicker(design: RulesHubDesign, selected: (String) -> Unit) {
        if (appPickerBusy) return
        appPickerBusy = true
        try {
            val apps = withContext(Dispatchers.IO) {
                packageManager.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
                    .filter { it.packageName != packageName && it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
                    .mapNotNull { pkg -> pkg.applicationInfo?.let { info -> pkg.packageName to packageManager.getApplicationLabel(info).toString() } }
                    .sortedBy { it.second.lowercase() }
            }
            if (apps.isEmpty()) {
                design.showStatus(getString(R.string.routing_apps_empty), true)
                appPickerBusy = false
                return
            }
            val labels = apps.map { "${it.second} · ${it.first}" }
            val field = TextInputLayout(this).apply { hint = getString(R.string.routing_find_app) }
            val input = android.widget.AutoCompleteTextView(this).apply {
                threshold = 0
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setAdapter(android.widget.ArrayAdapter(this@RulesHubActivity, android.R.layout.simple_dropdown_item_1line, labels))
            }
            field.addView(input)
            val container = LinearLayout(this).apply {
                val padding = (20 * resources.displayMetrics.density).toInt()
                setPadding(padding, 0, padding, padding)
                addView(field)
            }
            val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.routing_pick_app)
                .setView(container).setNegativeButton(R.string.cancel, null).create()
            input.setOnItemClickListener { parent, _, position, _ ->
                val label = parent.getItemAtPosition(position).toString()
                apps.getOrNull(labels.indexOf(label))?.let { selected(it.first) }
                dialog.dismiss()
            }
            input.setOnFocusChangeListener { _, focused -> if (focused) input.post { input.showDropDown() } }
            dialog.setOnDismissListener { appPickerBusy = false }
            dialog.show()
            input.requestFocus()
        } catch (e: kotlinx.coroutines.CancellationException) {
            appPickerBusy = false
            throw e
        } catch (e: Exception) {
            appPickerBusy = false
            design.showStatus(getString(R.string.routing_apps_empty), true)
        }
    }

    private suspend fun onSave(design: RulesHubDesign, id: UUID) {
        val state = withContext(Dispatchers.Main) { design.readState() }
        val proxyGroups = design.policyOptions().toSet()
        val validationError = runCatching {
            RuleValidator.validate(state, proxyGroups)
        }.exceptionOrNull()?.message
        if (validationError != null) {
            withContext(Dispatchers.Main) {
                design.showStatus(validationError, true)
            }
            return
        }

        val diffSummary = withContext(Dispatchers.Main) { design.diffSummary() }
        withContext(Dispatchers.Main) {
            design.showStatus(diffSummary, isError = false)
            design.setSaveBusy(true)
        }
        try {
            val stateJson = json.encodeToString(RuleState.serializer(), state)
            val currentYaml = withProfile { readImportedConfigYaml(id) }.orEmpty()
            val proposedYaml = withProfile { previewRuleStateYaml(id, stateJson) }
            withContext(Dispatchers.Main) {
                showRuleStatePreview(id, stateJson, currentYaml, proposedYaml, diffSummary) {
                    withContext(Dispatchers.Main) {
                        design.showStatus(getString(R.string.rules_hub_saved), false)
                    }
                    loadInto(design, expandProviders = false)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { design.setSaveBusy(false) }
        }
    }
}
