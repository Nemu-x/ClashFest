package com.github.kr328.clash.design

import com.github.kr328.clash.design.util.capContentWidthOnTv
import com.github.kr328.clash.design.util.isTelevision
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.RuleItem
import com.github.kr328.clash.service.model.RuleProviderItem
import com.github.kr328.clash.service.model.RuleState
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

class RulesHubDesign(context: Context) : Design<RulesHubDesign.Request>(context) {
    sealed class Request {
        object Save : Request()
        object AddManual : Request()
        data class EditManual(val ruleId: String) : Request()
        data class ToggleRule(val ruleId: String, val enabled: Boolean) : Request()
        data class RestoreRule(val ruleId: String) : Request()
        data class ReorderManual(val fromId: String, val toId: String) : Request()
    }

    private val rootView: View = context.layoutInflater.inflate(R.layout.design_rules_hub, context.root, false)
    override val root: View get() = rootView

    private val recycler: RecyclerView = rootView.findViewById(R.id.rules_list)
    private val noProfileNotice: TextView = rootView.findViewById(R.id.no_profile_notice)
    private val saveBar: MaterialCardView = rootView.findViewById(R.id.save_bar)
    private val statusText: TextView = rootView.findViewById(R.id.status_text)
    private val btnSave: MaterialButton = rootView.findViewById(R.id.btn_save)

    private var workingState = RuleState()
    private var baselineState = RuleState()
    private var providerMap: Map<String, RuleProviderItem> = emptyMap()
    private var providerUpdatedAt: Map<String, Long> = emptyMap()
    private var proxyOptions: List<String> = emptyList()
    private var profileName: String = ""
    private var filter: RulesHubFilter = RulesHubFilter.ALL
    private var searchQuery: String = ""
    private var subscriptionExpanded: Boolean = false
    private var providerDefsExpanded: Boolean = true

    private val adapter = RulesHubListAdapter(
        object : RulesHubListAdapter.Callbacks {
            override fun onSearchChanged(query: String) {
                searchQuery = query
                renderList()
            }

            override fun onFilterChanged(newFilter: RulesHubFilter) {
                filter = newFilter
                renderList()
            }

            override fun onToggleSection(section: RulesHubSection) {
                when (section) {
                    RulesHubSection.SUBSCRIPTION -> subscriptionExpanded = !subscriptionExpanded
                    RulesHubSection.PROVIDER_DEFS -> providerDefsExpanded = !providerDefsExpanded
                    RulesHubSection.MANUAL -> Unit
                }
                renderList()
            }

            override fun onAddManual() {
                requests.trySend(Request.AddManual)
            }

            override fun onEditManual(ruleId: String) {
                requests.trySend(Request.EditManual(ruleId))
            }

            override fun onToggleRule(ruleId: String, enabled: Boolean) {
                requests.trySend(Request.ToggleRule(ruleId, enabled))
            }

            override fun onRestoreRule(ruleId: String) {
                requests.trySend(Request.RestoreRule(ruleId))
            }

            override fun onReorderManual(fromId: String, toId: String) {
                requests.trySend(Request.ReorderManual(fromId, toId))
            }

            override fun onSwipeToggleRule(ruleId: String) {
                swipeToggleWithUndo(ruleId)
            }

            override fun onSwipeDeleteManual(ruleId: String) {
                swipeDeleteWithUndo(ruleId)
            }
        },
    )

    private val floatingSaveBar = context.isTelevision()

    init {
        root.capContentWidthOnTv()
        if (floatingSaveBar) {
            val density = context.resources.displayMetrics.density
            saveBar.radius = 16 * density
            saveBar.cardElevation = 4 * density
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPaddingRelative(bars.left, bars.top, bars.right, 0)
            val density = context.resources.displayMetrics.density
            val gap = (8 * density).toInt()
            if (floatingSaveBar) {
                // TV: the bar floats as a card inset to the content column (16dp, like the rows),
                // instead of a square slab cut off by the capped column's edges.
                val m = (16 * density).toInt()
                (saveBar.layoutParams as ViewGroup.MarginLayoutParams).setMargins(m, 0, m, bars.bottom + m)
                saveBar.requestLayout()
            } else {
                val barPad = bars.bottom + gap
                saveBar.setPadding(saveBar.paddingLeft, saveBar.paddingTop, saveBar.paddingRight, barPad)
            }
            // Pad the list by the pinned bar's REAL height (measured after it
            // applies its own bottom inset) so it never covers the last rows or
            // a section's expand control. (The old `88` was raw px, ~30dp.)
            saveBar.post {
                val below = (saveBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
                recycler.setPadding(
                    recycler.paddingLeft,
                    0,
                    recycler.paddingRight,
                    saveBar.height + below + gap,
                )
            }
            insets
        }
        rootView.post { ViewCompat.requestApplyInsets(rootView) }

        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter
        recycler.setHasFixedSize(false)
        adapter.attachItemTouchHelper(recycler)
        if (floatingSaveBar) {
            // TV: coming back UP to the header (search, filters) left the list half-scrolled, the
            // title off screen and the focused field's ring cut at the top edge. Show it whole.
            recycler.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                val item = newFocus?.let { recycler.findContainingItemView(it) } ?: return@addOnGlobalFocusChangeListener
                if (recycler.getChildAdapterPosition(item) == 0) {
                    (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
                }
            }
        }

        btnSave.setOnClickListener { requests.trySend(Request.Save) }
    }

    fun showNoProfile() {
        noProfileNotice.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        saveBar.visibility = View.GONE
    }

    fun bind(
        profile: String,
        state: RuleState,
        policies: List<String>,
        expandProviders: Boolean = false,
        updatedAt: Map<String, Long> = emptyMap(),
    ) {
        noProfileNotice.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        saveBar.visibility = View.VISIBLE

        profileName = profile
        workingState = state
        baselineState = state
        proxyOptions = policies
        providerMap = state.providers.associateBy(RuleProviderItem::name)
        providerUpdatedAt = updatedAt
        if (expandProviders) {
            subscriptionExpanded = true
            providerDefsExpanded = true
        }
        renderList()
    }

    fun readState(): RuleState {
        val (manual, provider) = RulesHubRowBuilder.partitionRules(workingState.rules)
        return workingState.copy(
            rules = RulesHubRowBuilder.mergeOrderedRules(manual, provider),
        )
    }

    fun knownPolicies(): Set<String> = RulesHubRowBuilder.knownPolicies(proxyOptions)
    fun policyOptions(): List<String> = proxyOptions

    fun mutateRule(id: String, transform: (RuleItem) -> RuleItem) {
        workingState = workingState.copy(
            rules = workingState.rules.map { if (it.id == id) transform(it) else it },
        )
        renderList()
    }

    fun addManualRule(rule: RuleItem) {
        val (manual, provider) = RulesHubRowBuilder.partitionRules(workingState.rules)
        workingState = workingState.copy(
            rules = RulesHubRowBuilder.mergeOrderedRules(listOf(rule) + manual, provider),
        )
        renderList()
    }

    fun updateManualRule(id: String, result: RuleEditResult) {
        mutateRule(id) { old ->
            old.copy(
                type = result.type,
                value = result.value,
                policy = result.policy,
                enabled = result.enabled,
                deleted = result.deleted,
            )
        }
    }

    fun deleteManualRule(id: String) {
        mutateRule(id) { it.copy(deleted = true, enabled = false) }
    }

    fun reorderManualById(fromId: String, toId: String) {
        val (manual, provider) = RulesHubRowBuilder.partitionRules(workingState.rules)
        val reordered = RulesHubRowBuilder.reorderManualById(manual, fromId, toId)
        workingState = workingState.copy(
            rules = RulesHubRowBuilder.mergeOrderedRules(reordered, provider),
        )
        renderList()
    }

    fun replaceState(state: RuleState) {
        workingState = state
        providerMap = state.providers.associateBy(RuleProviderItem::name)
        renderList()
    }

    // TV: disabling the focused Save button pushes focus up to the search field; hand it back
    // once the save finishes so closing the preview lands on Save rules again.
    private var refocusSave = false

    fun setSaveBusy(busy: Boolean) {
        if (floatingSaveBar && busy && btnSave.isFocused) refocusSave = true
        btnSave.isEnabled = !busy
        if (!busy && refocusSave) {
            refocusSave = false
            btnSave.requestFocus()
        }
    }

    fun showStatus(message: String?, isError: Boolean) {
        if (message.isNullOrBlank()) {
            statusText.visibility = View.GONE
            return
        }
        statusText.visibility = View.VISIBLE
        statusText.text = message
        statusText.setTextColor(
            MaterialColors.getColor(
                statusText,
                if (isError) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorPrimary,
            ),
        )
    }

    fun findRule(id: String): RuleItem? = workingState.rules.firstOrNull { it.id == id }

    fun diffSummary(): String? = RulesHubStateDiff.formatSummary(context, baselineState, readState())

    private fun swipeToggleWithUndo(ruleId: String) {
        val rule = findRule(ruleId) ?: return
        if (rule.deleted) return
        val prev = rule.enabled
        mutateRule(ruleId) { it.copy(enabled = !it.enabled) }
        launch {
            val msgRes = if (prev) {
                R.string.rules_hub_rule_disabled_undo
            } else {
                R.string.rules_hub_rule_enabled_undo
            }
            showToast(msgRes, ToastDuration.Long) {
                setAction(R.string.undo) {
                    mutateRule(ruleId) { it.copy(enabled = prev) }
                }
            }
        }
    }

    private fun swipeDeleteWithUndo(ruleId: String) {
        val rule = findRule(ruleId) ?: return
        if (rule.deleted || rule.source != com.github.kr328.clash.service.model.RuleSource.MANUAL) return
        val snapshot = rule
        deleteManualRule(ruleId)
        launch {
            showToast(R.string.rules_hub_rule_deleted_undo, ToastDuration.Long) {
                setAction(R.string.undo) {
                    mutateRule(ruleId) {
                        snapshot.copy(deleted = false, enabled = snapshot.enabled)
                    }
                }
            }
        }
    }

    private fun renderList() {
        val items = RulesHubListBuilder.build(
            context = context,
            state = workingState,
            filter = filter,
            searchQuery = searchQuery,
            providerMap = providerMap,
            knownPolicies = knownPolicies(),
            profileName = profileName,
            subscriptionExpanded = subscriptionExpanded,
            providerDefsExpanded = providerDefsExpanded,
            providerUpdatedAt = providerUpdatedAt,
        )
        adapter.submit(items, filter, searchQuery)
        updateDiffStatus()
    }

    private fun updateDiffStatus() {
        showStatus(diffSummary(), isError = false)
    }
}
