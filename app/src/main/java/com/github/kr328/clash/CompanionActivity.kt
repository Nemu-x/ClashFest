package com.github.kr328.clash

import android.widget.ImageView
import com.github.kr328.clash.companion.CompanionPin
import com.github.kr328.clash.companion.CompanionStore
import com.github.kr328.clash.companion.QrCode
import com.github.kr328.clash.companion.agent.CompanionAgent
import com.github.kr328.clash.companion.agent.CompanionGatewayService
import com.github.kr328.clash.companion.agent.PairingStore
import com.github.kr328.clash.companion.controller.CompanionClient
import com.github.kr328.clash.companion.controller.CompanionError
import com.github.kr328.clash.companion.controller.ControllerStore
import com.github.kr328.clash.companion.protocol.PairingPayload
import com.github.kr328.clash.companion.protocol.Ids
import com.github.kr328.clash.companion.ui.CompanionDesign
import com.github.kr328.clash.companion.ui.CompanionTvDesign
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.TvFocusHighlight
import com.github.kr328.clash.design.util.isTelevision
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.qr.QrScanResult
import com.github.kr328.clash.qr.ScanQrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class CompanionActivity : BaseActivity<Design<CompanionDesign.Request>>() {
    private val store by lazy { CompanionStore(this) }
    private val pairingStore by lazy { PairingStore(this) }

    private val scanLauncher = registerForActivityResult(ScanQrCode(), ::onScanResult)

    override suspend fun main() {
        val tv = if (isTelevision()) {
            CompanionTvDesign(this, store.displayName, ::onTvAgentToggle, ::confirmTvUnpair)
        } else {
            null
        }
        val design = tv ?: CompanionDesign(this, store, ::onAgentToggle)
        setContentDesign(design)

        // The toggle persists ON across restarts, but the service doesn't auto-survive a process
        // restart — reconcile on open so "Show QR/PIN" actually has a running gateway.
        if (store.agentEnabled && !CompanionAgent.isReady()) {
            CompanionGatewayService.start(this)
        }
        tv?.let { launch { keepTvScreenCurrent(it) } }

        while (isActive) {
            val request = design.requests.receive()
            when (request) {
                CompanionDesign.Request.ShowQr -> showQrDialog()
                CompanionDesign.Request.ShowPin -> showPinDialog()
                CompanionDesign.Request.ManagePairings -> showPairingsDialog()
                CompanionDesign.Request.ScanToPair -> scanLauncher.launch(null)
                CompanionDesign.Request.OpenControl ->
                    startActivity(CompanionControlActivity::class.intent)
            }
        }
    }

    /**
     * Returns true when the gateway is up and pairing payloads can be produced. If the toggle is
     * on but the service isn't running yet (e.g. just opened the screen), starts it and asks the
     * user to retry; if the toggle is off, prompts to enable.
     */
    private fun ensureAgentReady(): Boolean {
        if (CompanionAgent.isReady()) return true
        if (store.agentEnabled) {
            CompanionGatewayService.start(this)
            launch { design?.showToast(R.string.companion_starting, ToastDuration.Long) }
        } else {
            launch { design?.showToast(R.string.companion_enable_first, ToastDuration.Long) }
        }
        return false
    }

    private fun onAgentToggle(enabled: Boolean) {
        if (enabled) {
            CompanionGatewayService.start(this)
        } else {
            CompanionGatewayService.stop(this)
        }
    }

    /**
     * TV: the token behind the QR on screen, replaced once it is used or revoked. It outlives the
     * visit (process-wide) so reopening the screen shows the same unused token instead of minting
     * a new one each time and leaving the old ones in [PairingStore].
     */
    private var tvToken: String?
        get() = sharedTvToken
        set(value) { sharedTvToken = value }
    private var tvQrKey: String? = null

    /**
     * TV: the QR, the toggle and the paired list follow the gateway while the screen is open (it
     * takes a moment to start, and a phone pairing from the sofa should show up without leaving).
     */
    private suspend fun keepTvScreenCurrent(tv: CompanionTvDesign) {
        while (isActive) {
            refreshTv(tv)
            delay(1_500)
        }
    }

    private fun refreshTv(tv: CompanionTvDesign) {
        val pairings = pairingStore.list()
        // The QR's token was scanned and used: the next phone gets a fresh one.
        val used = tvToken?.let(Ids::tokenHash)?.let { hash ->
            pairings.none { it.tokenHash == hash } ||
                pairings.any { it.tokenHash == hash && (it.controllerId != null || it.label != null) }
        } == true
        if (used) tvToken = null

        tv.setAgentEnabled(store.agentEnabled)
        tvQrState(tv)?.let(tv::setQr)

        // A token nobody has used yet (a QR shown but never scanned) is not a controller.
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        tv.setPairings(
            pairings.filter { it.controllerId != null || it.label != null }.map {
                CompanionTvDesign.Paired(
                    tokenHash = it.tokenHash,
                    label = it.label ?: getString(R.string.companion_pairing_unnamed),
                    pairedAt = df.format(Date(it.pairedAt)),
                )
            },
        )
    }

    /** The QR to show, or null when what is on screen is still right. */
    private fun tvQrState(tv: CompanionTvDesign): CompanionTvDesign.QrState? {
        if (!store.agentEnabled) return tvQrOnce("off") { CompanionTvDesign.QrState.Off }
        if (!CompanionAgent.isReady()) return tvQrOnce("starting") { CompanionTvDesign.QrState.Starting }
        val ip = CompanionAgent.lanAddress() ?: return tvQrOnce("offline") { CompanionTvDesign.QrState.NoNetwork }
        val token = tvToken ?: pairingStore.issueToken().also { tvToken = it }
        val port = CompanionAgent.port
        return tvQrOnce("$ip:$port:${Ids.tokenHash(token)}") {
            val payload = PairingPayload(
                ip = ip,
                port = port,
                id = store.deviceId,
                fp = CompanionAgent.fingerprint!!,
                token = token,
                name = store.displayName,
                app = CompanionStore.APP_ID,
            ).toUri()
            // A full quiet zone (4 modules) inside the white card: phones lock on from the sofa.
            CompanionTvDesign.QrState.Ready(QrCode.encode(payload, tv.qrSizePx, margin = 4), "$ip:$port")
        }
    }

    private inline fun tvQrOnce(key: String, state: () -> CompanionTvDesign.QrState): CompanionTvDesign.QrState? {
        if (key == tvQrKey) return null
        tvQrKey = key
        return state()
    }

    private fun onTvAgentToggle(enabled: Boolean) {
        store.agentEnabled = enabled
        onAgentToggle(enabled)
        (design as? CompanionTvDesign)?.let(::refreshTv)
    }

    private fun confirmTvUnpair(item: CompanionTvDesign.Paired) {
        confirmRevoke(item.tokenHash, item.label) {
            (design as? CompanionTvDesign)?.let(::refreshTv)
        }
    }

    private fun showQrDialog() {
        if (!ensureAgentReady()) return
        val ip = CompanionAgent.lanAddress()
        if (ip == null) {
            launch { design?.showToast(R.string.companion_no_lan, ToastDuration.Long) }
            return
        }

        val port = CompanionAgent.port
        val payload = PairingPayload(
            ip = ip,
            port = port,
            id = store.deviceId,
            fp = CompanionAgent.fingerprint!!,
            token = pairingStore.issueToken(),
            name = store.displayName,
            app = CompanionStore.APP_ID,
        ).toUri()

        val image = ImageView(this).apply {
            val size = (resources.displayMetrics.density * 280).toInt()
            setImageBitmap(QrCode.encode(payload, size))
            val pad = (resources.displayMetrics.density * 24).toInt()
            setPadding(pad, pad, pad, pad)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.companion_show_qr_title)
            .setMessage(getString(R.string.companion_qr_dialog_message) + "\n\n$ip:$port")
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** PIN pairing for camera-less controllers (e.g. SlothClash on a PC). */
    private fun showPinDialog() {
        if (!ensureAgentReady()) return
        val pin = CompanionPin.generate()
        val ip = CompanionAgent.lanAddress()
        val density = resources.displayMetrics.density
        val pad = (density * 24).toInt()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        container.addView(
            android.widget.TextView(this).apply {
                text = getString(R.string.companion_pin_dialog_message)
                textSize = 14f
                alpha = 0.7f
                gravity = android.view.Gravity.CENTER
            },
        )
        // The code itself: large, bold, accent-coloured, spaced — readable across the room on a TV.
        container.addView(
            android.widget.TextView(this).apply {
                text = pin
                textSize = 40f
                setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                letterSpacing = 0.4f // space the digits without spaces (keeps them on one line)
                maxLines = 1
                gravity = android.view.Gravity.CENTER
                setTextColor(resolveThemedColor(androidx.appcompat.R.attr.colorPrimary))
                setPadding(0, (density * 16).toInt(), 0, (density * 12).toInt())
            },
        )
        if (ip != null) {
            container.addView(
                android.widget.TextView(this).apply {
                    text = "$ip:${CompanionAgent.port}"
                    textSize = 13f
                    alpha = 0.6f
                    gravity = android.view.Gravity.CENTER
                },
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.companion_show_pin_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .window?.let(TvFocusHighlight::install)
    }

    private fun showPairingsDialog() {
        val pairings = pairingStore.list()
        if (pairings.isEmpty()) {
            launch { design?.showToast(R.string.companion_no_pairings, ToastDuration.Long) }
            return
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = pairings.map { p ->
            (p.label ?: getString(R.string.companion_pairing_unnamed)) + " · " + df.format(Date(p.pairedAt))
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.companion_manage_pairings_title)
            .setItems(labels) { _, which ->
                confirmRevoke(pairings[which].tokenHash, labels[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRevoke(tokenHash: String, label: String, onRevoked: () -> Unit = {}) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.companion_revoke_title)
            .setMessage(getString(R.string.companion_revoke_message, label))
            .setPositiveButton(R.string.companion_revoke_confirm) { _, _ ->
                pairingStore.revoke(tokenHash)
                onRevoked()
                launch { design?.showToast(R.string.companion_revoked, ToastDuration.Short) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            // TV: the ring on the dialog's buttons, and focus on one from the start.
            .window?.let(TvFocusHighlight::install)
    }

    private fun onScanResult(result: QrScanResult) {
        if (result !is QrScanResult.Success) return
        val raw = result.content
        val payload = PairingPayload.parse(raw)
        if (payload == null) {
            launch { design?.showToast(R.string.companion_pair_invalid, ToastDuration.Long) }
            return
        }
        val agent = ControllerStore.PairedAgent(
            deviceId = payload.id,
            name = payload.name ?: payload.ip,
            app = payload.app ?: "",
            fp = payload.fp,
            token = payload.token,
            host = payload.ip,
            port = payload.port,
        )
        ControllerStore(this).put(agent)
        // One-scan flow: right after pairing, offer to send the current subscription to the
        // just-paired device, so "scan QR -> sub is on the TV" is a single gesture.
        launch {
            val active = withProfile { queryActive() }
            val shareable = active?.takeIf { it.type == Profile.Type.Url && it.source.isNotBlank() }
            // The operator's share-links lock covers sending the subscription to a TV too.
            val locked = shareable != null && ServiceStore(this@CompanionActivity).subscriptionShareLinksLockedFor(shareable.uuid)
            promptAfterPair(agent, shareable.takeUnless { locked }, locked)
        }
    }

    private fun promptAfterPair(agent: ControllerStore.PairedAgent, shareable: Profile?, locked: Boolean) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.companion_pair_success, agent.name))
        if (locked) {
            builder
                .setMessage(R.string.remote_share_locked)
                .setPositiveButton(android.R.string.ok, null)
        } else if (shareable != null) {
            builder
                .setMessage(getString(R.string.companion_share_after_pair, shareable.name))
                .setPositiveButton(R.string.companion_action_share) { _, _ ->
                    shareSubscriptionTo(agent, shareable)
                }
                .setNegativeButton(R.string.companion_not_now, null)
        } else {
            builder
                .setMessage(R.string.companion_no_shareable)
                .setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun shareSubscriptionTo(agent: ControllerStore.PairedAgent, profile: Profile) =
        launch(Dispatchers.IO) {
            try {
                CompanionClient(agent, store.deviceId, store.displayName)
                    .subscription(profile.source, profile.name)
                design?.showToast(R.string.companion_shared, ToastDuration.Short)
            } catch (e: CompanionError) {
                design?.showToast(e.message ?: getString(R.string.companion_request_failed), ToastDuration.Long)
            } catch (e: Exception) {
                design?.showToast(R.string.companion_unreachable, ToastDuration.Long)
            }
        }

    private companion object {
        @Volatile
        var sharedTvToken: String? = null
    }
}
