package com.github.kr328.clash

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.ProxyChainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.util.SubscriptionChain
import com.github.kr328.clash.util.applyYamlPreviewDirect
import com.github.kr328.clash.util.closeConnectionsAfterUserProxySwitchIfEnabled
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID

class ProxyChainActivity : BaseActivity<ProxyChainDesign>() {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
    private suspend fun saved(uuid: UUID): SubscriptionChain? = withProfile { readSubscriptionChain(uuid) }
        ?.let { json.decodeFromString(SubscriptionChain.serializer(), it) }

    override suspend fun main() {
        val screen = ProxyChainDesign(this)
        setContentDesign(screen)
        try {
            val profiles = withProfile { queryAll() }.filter { it.imported && !it.pending }
            val active = withProfile { queryActive() }
            val chain = active?.let { saved(it.uuid) }
            val legacy = if (active != null && chain == null) withProfile { listProxyDialerChains(active.uuid) }.size else 0
            screen.bindProfiles(profiles, active?.uuid, chain, legacy)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { screen.showStatus(R.string.chain_error); return }
        while (isActive) {
            val request = screen.requests.receive()
            try {
                when (request) {
                    is ProxyChainDesign.Request.LoadNodes -> screen.bindNodes(request.uuid, request.first,
                        withProfile { readSubscriptionChainNodes(request.uuid) })
                    ProxyChainDesign.Request.Save -> saveChain(screen, false)
                    ProxyChainDesign.Request.UseNow -> saveChain(screen, true)
                    ProxyChainDesign.Request.Clear -> clearChain(screen)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request is ProxyChainDesign.Request.LoadNodes) screen.bindNodes(request.uuid, request.first, emptyList())
                screen.showStatus(R.string.chain_error)
            } finally { screen.setBusy(false) }
        }
    }

    private suspend fun saveChain(screen: ProxyChainDesign, connect: Boolean) {
        val choice = screen.selection() ?: return screen.showStatus(R.string.chain_pick_both)
        val profile = withProfile { queryByUUID(choice.exitProfile) } ?: return screen.showStatus(R.string.chain_error)
        val preview = withProfile { previewSubscriptionChain(choice.exitProfile, choice.firstProfile, choice.firstNode, choice.exitProfile, choice.exitNode) }
        if (!applyYamlPreviewDirect(preview)) return screen.showStatus(R.string.chain_error)
        val chain = saved(choice.exitProfile) ?: return screen.showStatus(R.string.chain_error)
        withProfile {
            rememberProxySelection(choice.exitProfile, "GLOBAL", chain.exitAlias)
            setActive(profile)
        }
        uiStore.tunnelModePreference = TunnelState.Mode.Global.name
        screen.bindSaved(choice.exitProfile, chain)
        if (!connect) { screen.showStatus(R.string.chain_saved_offline); return }
        if (!clashRunning) {
            val permission = startClashService()
            if (permission != null) {
                val result = startActivityForResult(ActivityResultContracts.StartActivityForResult(), permission)
                if (result.resultCode != RESULT_OK) return screen.showStatus(R.string.vpn_permission_denied)
                startClashService()
            }
        }
        withTimeout(20_000L) {
            while (true) {
                val exists = runCatching { withClash { queryProxyGroup("GLOBAL", uiStore.proxySort).proxies.any { it.name == chain.exitAlias } } }.getOrDefault(false)
                if (exists) break
                delay(200L)
            }
        }
        val selected = withClash {
            val override = queryOverride(Clash.OverrideSlot.Session)
            override.mode = TunnelState.Mode.Global
            patchOverride(Clash.OverrideSlot.Session, override)
            patchSelector("GLOBAL", chain.exitAlias)
        }
        check(selected)
        closeConnectionsAfterUserProxySwitchIfEnabled { message, duration -> screen.showToast(message, duration) }
        screen.showStatus(R.string.chain_connected)
    }

    private suspend fun clearChain(screen: ProxyChainDesign) {
        val uuid = screen.savedProfile() ?: return
        val chain = saved(uuid)
        val preview = withProfile { if (chain != null) previewRemoveSubscriptionChain(uuid) else previewClearAllProxyDialerChains(uuid) }
        if (!applyYamlPreviewDirect(preview)) return screen.showStatus(R.string.chain_error)
        uiStore.tunnelModePreference = TunnelState.Mode.Rule.name
        if (clashRunning) withClash {
            val override = queryOverride(Clash.OverrideSlot.Session)
            override.mode = TunnelState.Mode.Rule
            patchOverride(Clash.OverrideSlot.Session, override)
        }
        screen.bindSaved(null, null)
        screen.showStatus(R.string.chain_removed)
    }
}