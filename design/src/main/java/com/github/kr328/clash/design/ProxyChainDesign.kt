package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.SubscriptionChain
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class ProxyChainDesign(context: Context) : Design<ProxyChainDesign.Request>(context) {
    sealed class Request {
        data class LoadNodes(val uuid: UUID, val first: Boolean) : Request()
        object Save : Request()
        object UseNow : Request()
        object Clear : Request()
    }
    data class Selection(val firstProfile: UUID, val firstNode: String, val exitProfile: UUID, val exitNode: String)

    private val view = context.layoutInflater.inflate(R.layout.design_proxy_chain, context.root, false)
    override val root: View get() = view
    private val firstProfile: MaterialAutoCompleteTextView = view.findViewById(R.id.chain_first_profile)
    private val firstNode: MaterialAutoCompleteTextView = view.findViewById(R.id.chain_first_node)
    private val exitProfile: MaterialAutoCompleteTextView = view.findViewById(R.id.chain_exit_profile)
    private val exitNode: MaterialAutoCompleteTextView = view.findViewById(R.id.chain_exit_node)
    private val save: MaterialButton = view.findViewById(R.id.chain_save)
    private val connect: MaterialButton = view.findViewById(R.id.chain_connect)
    private val clear: MaterialButton = view.findViewById(R.id.chain_clear)
    private val status: TextView = view.findViewById(R.id.chain_status)
    private var profiles: List<Profile> = emptyList()
    private var firstId: UUID? = null
    private var exitId: UUID? = null
    private var firstNames = emptyList<String>()
    private var exitNames = emptyList<String>()
    private var savedFirst: String? = null
    private var savedExit: String? = null
    private var busy = false
    private var savedOwner: UUID? = null
    fun savedProfile(): UUID? = savedOwner

    init {
        val scroll: NestedScrollView = view.findViewById(R.id.proxy_chain_scroll)
        val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.main_padding_horizontal)
        val bottomPadding = context.resources.getDimensionPixelSize(R.dimen.main_padding_horizontal)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            scroll.setPaddingRelative(horizontalPadding + bars.left, bars.top, horizontalPadding + bars.right, bars.bottom + bottomPadding)
            insets
        }
        view.post { ViewCompat.requestApplyInsets(view) }
        firstProfile.setOnItemClickListener { _, _, position, _ -> chooseProfile(profiles[position].uuid, true) }
        exitProfile.setOnItemClickListener { _, _, position, _ -> chooseProfile(profiles[position].uuid, false) }
        firstNode.doAfterTextChanged { refreshEnabled() }
        exitNode.doAfterTextChanged { refreshEnabled() }
        save.setOnClickListener { if (!busy) { setBusy(true); requests.trySend(Request.Save) } }
        connect.setOnClickListener { if (!busy) { setBusy(true); requests.trySend(Request.UseNow) } }
        clear.setOnClickListener { if (!busy) { setBusy(true); requests.trySend(Request.Clear) } }
        refreshEnabled()
    }

    fun bindProfiles(items: List<Profile>, active: UUID?, saved: SubscriptionChain?, legacyCount: Int) {
        profiles = items
        view.findViewById<View>(R.id.chain_loading).visibility = View.GONE
        view.findViewById<View>(R.id.chain_form).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) { showStatus(R.string.chain_no_subscriptions); return }
        val labels = items.map { p -> if (items.count { it.name == p.name } > 1) "${p.name} (${items.indexOf(p) + 1})" else p.name }
        firstProfile.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, labels))
        exitProfile.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, labels))
        savedFirst = saved?.first?.proxyName
        savedExit = saved?.exit?.proxyName
        val first = items.firstOrNull { it.uuid.toString() == saved?.first?.profileId } ?: items.firstOrNull { it.uuid == active } ?: items.first()
        val exit = items.firstOrNull { it.uuid.toString() == saved?.exit?.profileId } ?: items.firstOrNull { it.uuid == active } ?: items.first()
        firstProfile.setText(labels[items.indexOf(first)], false)
        exitProfile.setText(labels[items.indexOf(exit)], false)
        chooseProfile(first.uuid, true)
        chooseProfile(exit.uuid, false)
        bindSaved(active, saved, legacyCount)
    }

    fun bindSaved(owner: UUID?, saved: SubscriptionChain?, legacyCount: Int = 0) {
        savedOwner = owner.takeIf { saved != null || legacyCount > 0 }
        val text: TextView = view.findViewById(R.id.chain_saved)
        text.text = if (saved != null) context.getString(R.string.chain_saved_route, saved.first.proxyName, saved.exit.proxyName)
            else context.getString(if (legacyCount > 0) R.string.chain_legacy_saved else R.string.chain_not_saved)
        refreshEnabled()
    }

    private fun chooseProfile(uuid: UUID, first: Boolean) {
        if (first) { firstId = uuid; firstNames = emptyList(); firstNode.setText("", false) }
        else { exitId = uuid; exitNames = emptyList(); exitNode.setText("", false) }
        refreshEnabled()
        requests.trySend(Request.LoadNodes(uuid, first))
    }

    fun bindNodes(uuid: UUID, first: Boolean, names: List<String>) {
        if ((if (first) firstId else exitId) != uuid) return
        val field = if (first) firstNode else exitNode
        if (first) firstNames = names else exitNames = names
        field.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, names))
        val remembered = if (first) savedFirst else savedExit
        if (remembered in names) field.setText(remembered, false)
        if (first) savedFirst = null else savedExit = null
        val box: TextInputLayout = view.findViewById(if (first) R.id.chain_first_node_box else R.id.chain_exit_node_box)
        box.helperText = if (names.isEmpty()) context.getString(R.string.chain_no_nodes) else null
        refreshEnabled()
    }

    fun selection(): Selection? {
        val a = firstId ?: return null
        val b = exitId ?: return null
        val first = firstNode.text.toString()
        val exit = exitNode.text.toString()
        if (first !in firstNames || exit !in exitNames || (a == b && first == exit)) return null
        return Selection(a, first, b, exit)
    }

    fun setBusy(value: Boolean) { busy = value; refreshEnabled() }
    private fun refreshEnabled() {
        firstProfile.isEnabled = !busy && profiles.isNotEmpty()
        exitProfile.isEnabled = !busy && profiles.isNotEmpty()
        firstNode.isEnabled = !busy && firstNames.isNotEmpty()
        exitNode.isEnabled = !busy && exitNames.isNotEmpty()
        save.isEnabled = !busy && selection() != null
        connect.isEnabled = !busy && selection() != null
        clear.isEnabled = !busy && savedOwner != null
    }
    fun showStatus(resource: Int) {
        view.findViewById<View>(R.id.chain_loading).visibility = View.GONE
        status.setText(resource)
        status.visibility = View.VISIBLE
    }
}
