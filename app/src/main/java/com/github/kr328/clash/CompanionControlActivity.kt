package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.companion.controller.CompanionDiscovery
import com.github.kr328.clash.companion.controller.ControllerStore
import com.github.kr328.clash.companion.ui.CompanionControlDesign
import kotlinx.coroutines.isActive

/** The paired TVs; tapping one opens its remote-control screen ([CompanionRemoteActivity]). */
class CompanionControlActivity : BaseActivity<CompanionControlDesign>() {
    private val store by lazy { ControllerStore(this) }
    private var discovery: CompanionDiscovery? = null
    private var shown: List<ControllerStore.PairedAgent>? = null

    override suspend fun main() {
        showList()

        // Reconnect-by-deviceId: keep stored agents' addresses fresh as they re-announce (§4.4).
        discovery = CompanionDiscovery(this) { discovered ->
            discovered.forEach { d ->
                if (store.get(d.deviceId) != null) {
                    Log.i("Companion controller: discovery refreshed ${d.name} -> ${d.host}:${d.port}")
                    store.updateAddress(d.deviceId, d.host, d.port)
                }
            }
        }.also { it.start() }

        while (isActive) {
            // Coming back from a remote screen: a TV may have been renamed or forgotten there.
            if (events.receive() == Event.ActivityStart) showList()
        }
    }

    private suspend fun showList() {
        val agents = store.list()
        if (agents == shown) return
        shown = agents
        setContentDesign(CompanionControlDesign(this, agents, ::onAgentClick))
    }

    override fun onDestroy() {
        discovery?.stop()
        super.onDestroy()
    }

    private fun onAgentClick(deviceId: String) {
        if (store.get(deviceId) == null) return
        startActivity(
            CompanionRemoteActivity::class.intent.putExtra(CompanionRemoteActivity.EXTRA_DEVICE_ID, deviceId),
        )
    }
}
