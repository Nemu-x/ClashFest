package com.github.kr328.clash.design

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.View
import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.annotation.StringRes
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import android.widget.TextView
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.ProxyHardeningMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.TunStackResolver
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NetworkSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    running: Boolean,
) : Design<NetworkSettingsDesign.Request>(context) {
    enum class Request {
        Unused
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.header.screenTitle.text = (context as? Activity)?.title?.toString().orEmpty()

        val screen = preferenceScreen(context) {
            val vpnDependencies: MutableList<Preference> = mutableListOf()

            val vpn = switch(
                value = uiStore::enableVpn,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.route_system_traffic,
                summary = R.string.routing_via_vpn_service
            ) {
                listener = OnChangedListener {
                    vpnDependencies.forEach {
                        it.enabled = uiStore.enableVpn
                    }
                }
            }

            category(R.string.security_options)

            selectableList(
                value = srvStore::proxyHardeningMode,
                values = ProxyHardeningMode.values(),
                valuesText = arrayOf(
                    R.string.proxy_hardening_strict,
                    R.string.proxy_hardening_compat,
                    R.string.proxy_hardening_off,
                ),
                title = R.string.proxy_hardening_mode,
            )

            switch(
                value = srvStore::seedDefaultGeoMirrors,
                title = R.string.seed_default_geo_mirrors,
                summary = R.string.seed_default_geo_mirrors_summary,
            )

            switch(
                value = srvStore::keepConnectionsOnOldProxy,
                title = R.string.keep_connections_on_old_proxy,
                summary = R.string.keep_connections_on_old_proxy_summary,
            )

            // Read live by NetworkObserveModule on every network event — no VPN restart needed,
            // hence not part of vpnDependencies.
            switch(
                value = srvStore::networkSwitchReaction,
                title = R.string.network_switch_reaction,
                summary = R.string.network_switch_reaction_summary,
            )

            category(R.string.local_proxy)

            switch(
                value = srvStore::localProxyEnabled,
                title = R.string.local_proxy_enable,
                summary = R.string.local_proxy_enable_summary,
            ) {
                vpnDependencies.add(this)
                // Strict means "no local listeners at all", so leaving the picker on Strict while a
                // listener is actually open would be a lie. Move it to Compat on enable and back on
                // disable; an explicit Off is the user's call and stays untouched.
                listener = OnChangedListener {
                    val mode = srvStore.proxyHardeningMode
                    if (srvStore.localProxyEnabled) {
                        if (mode == ProxyHardeningMode.Strict) {
                            srvStore.proxyHardeningMode = ProxyHardeningMode.Compat
                        }
                    } else if (mode == ProxyHardeningMode.Compat) {
                        srvStore.proxyHardeningMode = ProxyHardeningMode.Strict
                    }
                }
            }

            // Pinned by us rather than inherited from the subscription, so the address shown here is
            // always the real one — the whole point is giving the user something to paste.
            val localProxyPortNullable = object {
                var value: Int?
                    get() = srvStore.localProxyPort
                    set(v) {
                        srvStore.localProxyPort = (v ?: DEFAULT_LOCAL_PROXY_PORT)
                            .coerceIn(1, 65535)
                    }
            }
            editableText(
                value = localProxyPortNullable::value,
                adapter = NullableTextAdapter.Port,
                title = R.string.local_proxy_port,
                configure = vpnDependencies::add,
            )

            val credentials = clickable(
                title = R.string.local_proxy_credentials,
                summary = R.string.local_proxy_credentials_summary,
            ) {
                clicked { revealLocalProxyCredentials(context, srvStore) }
            }
            credentials.summary = localProxySummary(context, srvStore)

            category(R.string.vpn_service_options)

            switch(
                value = srvStore::bypassPrivateNetwork,
                title = R.string.bypass_private_network,
                summary = R.string.bypass_private_network_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::dnsHijacking,
                title = R.string.dns_hijacking,
                summary = R.string.dns_hijacking_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::allowIpv6,
                title = R.string.allow_ipv6,
                summary = R.string.allow_ipv6_summary,
                configure = vpnDependencies::add,
            )

            if (Build.VERSION.SDK_INT >= 29) {
                switch(
                    value = srvStore::systemProxy,
                    title = R.string.system_proxy,
                    summary = R.string.system_proxy_summary,
                    configure = vpnDependencies::add,
                )
            }

            val stackPref = selectableList(
                value = srvStore::tunStackMode,
                values = arrayOf(
                    "auto",
                    "system",
                    "gvisor",
                    "mixed"
                ),
                valuesText = arrayOf(
                    R.string.tun_stack_auto,
                    R.string.tun_stack_system,
                    R.string.tun_stack_gvisor,
                    R.string.tun_stack_mixed
                ),
                title = R.string.tun_stack_mode,
                configure = vpnDependencies::add,
            )
            // Surface the effective stack so the picker isn't misleading: an operator `X-Network-Stack`
            // header locks the stack over the user's pick ("Set by operator: …"), and Auto resolves to
            // the subscription's declared stack ("Auto: …").
            launch {
                val setting = srvStore.tunStackMode
                val (operatorLock, effective) = withContext(Dispatchers.IO) {
                    val uuid = srvStore.activeProfile
                    val operator = uuid?.let { srvStore.subscriptionNetworkStackFor(it) }
                    val cfg = uuid?.let {
                        runCatching {
                            File(context.importedDir.resolve(it.toString()), "config.yaml").readText()
                        }.getOrNull()
                    }
                    operator?.trim()?.lowercase() to TunStackResolver.resolve(setting, operator, cfg)
                }
                when {
                    operatorLock in setOf("system", "gvisor", "mixed") && operatorLock != setting ->
                        stackPref.summary = context.getString(R.string.tun_stack_operator_fmt, effective)
                    setting == TunStackResolver.AUTO ->
                        stackPref.summary = context.getString(R.string.tun_stack_auto_fmt, effective)
                }
            }

            if (running) {
                vpn.enabled = false

                vpnDependencies.forEach {
                    it.enabled = false
                }
            } else {
                vpn.listener?.onChanged()
            }
        }

        binding.content.addView(screen.root)

        if (running) {
            // The "options unavailable" snackbar is pinned to the bottom and would otherwise cover
            // the last preference (the stack row). Pad the scroll content so that row can scroll
            // clear of it.
            val extra = (88 * context.resources.displayMetrics.density).toInt()
            binding.content.setPadding(0, 0, 0, extra)
            launch {
                showToast(R.string.options_unavailable, ToastDuration.Indefinite)
            }
        }
    }

    private fun localProxySummary(context: Context, srvStore: ServiceStore): CharSequence =
        if (srvStore.localProxyEnabled) {
            "$LOOPBACK:${srvStore.localProxyPort}"
        } else {
            context.getString(R.string.local_proxy_credentials_summary)
        }

    /**
     * Reveal the credentials behind a biometric / device-credential prompt.
     *
     * The secret only grants access to this device's own loopback listener, so the prompt is
     * shoulder-surfing and screen-sharing hygiene rather than a real security boundary. When the
     * device has nothing enrolled we show the credentials anyway instead of locking the user out
     * of their own proxy.
     */
    private fun revealLocalProxyCredentials(context: Context, srvStore: ServiceStore) {
        val activity = context as? FragmentActivity
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = BiometricManager.from(context)
            .canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

        if (activity == null || !canAuthenticate) {
            showLocalProxyCredentials(context, srvStore)
            return
        }

        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    showLocalProxyCredentials(context, srvStore)
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.local_proxy_credentials))
                .setSubtitle(context.getString(R.string.local_proxy_reveal_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun showLocalProxyCredentials(context: Context, srvStore: ServiceStore) {
        val credential = srvStore.localProxyCredential
        if (!srvStore.localProxyEnabled || credential.isBlank()) {
            // Minted by the service on the first load after the toggle, so it can legitimately be
            // missing until the user connects once.
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.local_proxy_credentials)
                .setMessage(R.string.local_proxy_not_ready)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val user = credential.substringBefore(':', "")
        val pass = credential.substringAfter(':', "")
        val port = srvStore.localProxyPort
        val url = "socks5://$user:$pass@$LOOPBACK:$port"

        fun copy(@StringRes label: Int, value: String) {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(context.getString(label), value))
            Toast.makeText(
                context,
                context.getString(R.string.local_proxy_copied_fmt, context.getString(label)),
                Toast.LENGTH_SHORT,
            ).show()
        }

        // Every value gets its own selectable row and its own copy button: some clients take a
        // single socks5:// URL (the button below), others want the fields entered one at a time,
        // and digging them out of one text blob is miserable.
        val view = context.layoutInflater.inflate(R.layout.dialog_local_proxy, null, false)

        fun bind(valueId: Int, copyId: Int, @StringRes label: Int, value: String) {
            view.findViewById<TextView>(valueId).text = value
            view.findViewById<View>(copyId).setOnClickListener { copy(label, value) }
        }
        bind(R.id.valueHost, R.id.copyHost, R.string.local_proxy_field_host, LOOPBACK)
        bind(R.id.valuePort, R.id.copyPort, R.string.local_proxy_field_port, port.toString())
        bind(R.id.valueUsername, R.id.copyUsername, R.string.local_proxy_field_username, user)
        bind(R.id.valuePassword, R.id.copyPassword, R.string.local_proxy_field_password, pass)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.local_proxy_credentials)
            .setView(view)
            .setPositiveButton(R.string.local_proxy_copy_url) { _, _ ->
                copy(R.string.local_proxy_credentials, url)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_LOCAL_PROXY_PORT = 7890
    }
}
