package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.core.model.ConnectionsSnapshot
import com.github.kr328.clash.design.adapter.ConnectionsAdapter
import com.github.kr328.clash.design.databinding.DesignConnectionsBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.design.util.ConnectionFilterMode
import com.github.kr328.clash.design.util.ConnectionListRevision
import com.github.kr328.clash.design.util.filterConnections
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionsDesign(context: Context) : Design<ConnectionsDesign.Request>(context) {
    sealed class Request {
        object OpenLogcat : Request()
        object OpenRequestHistory : Request()
        data class CloseConnection(val id: String) : Request()
        object CloseAllConnections : Request()
    }

    private val binding = DesignConnectionsBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = ConnectionsAdapter(this) { connection ->
        if (connection.id.isNotBlank()) {
            requests.trySend(Request.CloseConnection(connection.id))
        }
    }
    private var lastSnapshot: ConnectionsSnapshot = ConnectionsSnapshot()
    private var searchQuery: String = ""
    private var filterMode = ConnectionFilterMode.ALL
    private var filterJob: Job? = null
    private val filterRevision = ConnectionListRevision()

    override val root: View
        get() = binding.root

    init {
        binding.self = this
        binding.header.screenTitle.text = context.getString(R.string.connections_title)
        binding.connectionsList.adapter = adapter
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                adapter.resumeBackgroundWork()
                applyFilteredList()
            }

            override fun onViewDetachedFromWindow(view: View) {
                filterRevision.next()
                filterJob?.cancel()
                filterJob = null
                adapter.cancelBackgroundWork()
            }
        })
        binding.btnConnectionsOpenLog.setOnClickListener {
            requests.trySend(Request.OpenLogcat)
        }
        binding.btnConnectionsOpenRequests.setOnClickListener {
            requests.trySend(Request.OpenRequestHistory)
        }
        binding.btnConnectionsCloseAll.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setMessage(R.string.connections_close_all_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.connections_close_all) { _, _ ->
                    requests.trySend(Request.CloseAllConnections)
                }
                .show()
        }
        binding.connectionsSearch.addTextChangedListener {
            searchQuery = it?.toString().orEmpty()
            applyFilteredList(debounce = true)
        }
        val filterModes = ConnectionFilterMode.values().toList()
        val filterLabels = listOf(
            context.getString(R.string.connections_filter_all),
            context.getString(R.string.connections_filter_tcp),
            context.getString(R.string.connections_filter_udp),
            context.getString(R.string.connections_filter_direct),
            context.getString(R.string.connections_filter_proxy),
            context.getString(R.string.connections_filter_reject),
        )
        binding.connectionsFilterDropdown.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, filterLabels),
        )
        binding.connectionsFilterDropdown.setText(filterLabels[filterModes.indexOf(filterMode)], false)
        binding.connectionsFilterDropdown.setOnItemClickListener { _, _, position, _ ->
            filterMode = filterModes.getOrElse(position) { ConnectionFilterMode.ALL }
            applyFilteredList()
        }
        renderSnapshot(ConnectionsSnapshot())
    }

    suspend fun patchSnapshot(snap: ConnectionsSnapshot) {
        withContext(Dispatchers.Main) {
            renderSnapshot(snap)
        }
    }

    private fun renderSnapshot(snap: ConnectionsSnapshot) {
        lastSnapshot = snap
        binding.connectionsActiveValue.text = snap.connections.size.toString()
        binding.connectionsUploadValue.text = snap.uploadTotal.toBytesString(context)
        binding.connectionsDownloadValue.text = snap.downloadTotal.toBytesString(context)
        binding.connectionsMemoryValue.text = snap.memory.toBytesString(context)
        binding.btnConnectionsCloseAll.isEnabled = snap.connections.isNotEmpty()
        applyFilteredList()
    }

    private fun applyFilteredList(debounce: Boolean = false) {
        val revision = filterRevision.next()
        filterJob?.cancel()
        adapter.cancelPendingUpdates()
        if (!binding.root.isAttachedToWindow) return
        val connections = lastSnapshot.connections
        val query = searchQuery
        val mode = filterMode
        filterJob = launch(Dispatchers.Main.immediate) {
            if (debounce) delay(200L)
            val list = withContext(Dispatchers.Default) {
                val jobContext = currentCoroutineContext()
                filterConnections(connections, mode, query) { jobContext.ensureActive() }
            }
            if (!filterRevision.isCurrent(revision)) return@launch
            adapter.submit(list) {
                if (!filterRevision.isCurrent(revision)) return@submit
                val empty = list.isEmpty()
                binding.connectionsEmptyHint.visibility = if (empty) View.VISIBLE else View.GONE
                binding.connectionsList.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }
}
