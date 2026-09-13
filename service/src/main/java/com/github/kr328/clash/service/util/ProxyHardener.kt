package com.github.kr328.clash.service.util

import android.os.Build
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.service.model.ProxyHardeningMode

/**
 * Applies ClashFest hardening against the local-listener leak (a.k.a.
 * VLESS-SOCKS5 vulnerability) and direct TUN access from other apps on the
 * same device.
 *
 * On Android 10+ and [ProxyHardeningMode.Strict] (default) this completely
 * disables the SOCKS / HTTP / Mixed / Redir / TProxy listeners by forcing
 * their port to `0`. The TUN tunnel remains the only ingress, which makes it
 * impossible for unrelated apps on the device to bypass the per-app routing
 * rules by talking to `127.0.0.1:7891`.
 *
 * In [ProxyHardeningMode.Compat] the listeners are kept open but bound to
 * `127.0.0.1` and gated by a randomly generated session credential, which is
 * already what [RuntimeSocksAuth] used to do. [ProxyHardeningMode.Off]
 * disables this layer altogether.
 */
object ProxyHardener {
    private const val DISABLED_PORT = 0

    /**
     * Apply the requested [mode] to [configuration].
     * @return true if [configuration] was mutated.
     */
    /**
     * Apply the requested [mode] to [configuration].
     *
     * @param localProxy set when the user explicitly enabled the local proxy (Settings -> Network).
     *        It overrides the listener-disabling half of [ProxyHardeningMode.Strict]: the listener
     *        is pinned to the user's port and gated by a stable credential instead of being turned
     *        off. Everything else (loopback bind, allow-lan off, controller clamping) still applies,
     *        so the listener is reachable from this device only and still requires auth.
     * @return true if [configuration] was mutated.
     */
    fun applyTo(
        configuration: ConfigurationOverride,
        mode: ProxyHardeningMode,
        seedGeoMirrors: Boolean,
        localProxy: LocalProxySettings? = null,
    ): Boolean {
        var changed = false

        if (seedGeoMirrors) {
            changed = ensureGeoMirrors(configuration) || changed
        }

        val local = localProxy?.takeIf { it.enabled && it.port in 1..65535 }

        when (mode) {
            ProxyHardeningMode.Off -> Unit
            ProxyHardeningMode.Compat -> {
                changed = RuntimeSocksAuth.applyTo(configuration, local?.credential) || changed
            }
            ProxyHardeningMode.Strict -> {
                changed = RuntimeSocksAuth.applyTo(configuration, local?.credential) || changed
                if (local == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    changed = disableLocalListeners(configuration) || changed
                }
            }
        }

        if (local != null) {
            changed = pinLocalListener(configuration, local.port) || changed
        }

        return changed
    }

    /** User-facing local-proxy opt-in, resolved from ServiceStore by the caller. */
    data class LocalProxySettings(
        val enabled: Boolean,
        val port: Int,
        /** Stable `user:pass`; blank falls back to the rotating session credential. */
        val credential: String,
    )

    /**
     * Pin the mixed listener to the port shown in Settings and silence the protocol-specific ones,
     * so there is exactly one predictable `127.0.0.1:port` to hand out.
     */
    private fun pinLocalListener(configuration: ConfigurationOverride, port: Int): Boolean {
        var changed = false

        if (configuration.mixedPort != port) {
            configuration.mixedPort = port
            changed = true
        }
        if (configuration.httpPort != DISABLED_PORT) {
            configuration.httpPort = DISABLED_PORT
            changed = true
        }
        if (configuration.socksPort != DISABLED_PORT) {
            configuration.socksPort = DISABLED_PORT
            changed = true
        }
        if (configuration.redirectPort != DISABLED_PORT) {
            configuration.redirectPort = DISABLED_PORT
            changed = true
        }
        if (configuration.tproxyPort != DISABLED_PORT) {
            configuration.tproxyPort = DISABLED_PORT
            changed = true
        }

        if (changed) {
            Log.i("ProxyHardener: local proxy pinned to 127.0.0.1:$port (mixed)")
        }

        return changed
    }

    /**
     * Force every local proxy listener to be disabled. mihomo treats `0` as
     * "do not listen".
     */
    private fun disableLocalListeners(configuration: ConfigurationOverride): Boolean {
        var changed = false

        if (configuration.httpPort != DISABLED_PORT) {
            configuration.httpPort = DISABLED_PORT
            changed = true
        }
        if (configuration.socksPort != DISABLED_PORT) {
            configuration.socksPort = DISABLED_PORT
            changed = true
        }
        if (configuration.mixedPort != DISABLED_PORT) {
            configuration.mixedPort = DISABLED_PORT
            changed = true
        }
        if (configuration.redirectPort != DISABLED_PORT) {
            configuration.redirectPort = DISABLED_PORT
            changed = true
        }
        if (configuration.tproxyPort != DISABLED_PORT) {
            configuration.tproxyPort = DISABLED_PORT
            changed = true
        }

        if (changed) {
            Log.i("ProxyHardener: local listeners disabled (Strict mode)")
        }

        return changed
    }

    private fun ensureGeoMirrors(configuration: ConfigurationOverride): Boolean {
        var changed = false
        val geo = configuration.geoxurl

        // Allowlist (fail-closed): seed when absent, and rewrite any host that
        // is not a trusted mirror to the trusted primary.
        if (geo.geoip.isNullOrBlank() || !GeoMirrors.isTrusted(geo.geoip)) {
            geo.geoip = GeoMirrors.primaryGeoIpDat()
            changed = true
        }
        if (geo.geosite.isNullOrBlank() || !GeoMirrors.isTrusted(geo.geosite)) {
            geo.geosite = GeoMirrors.primaryGeoSiteDat()
            changed = true
        }
        if (geo.mmdb.isNullOrBlank() || !GeoMirrors.isTrusted(geo.mmdb)) {
            geo.mmdb = GeoMirrors.primaryGeoIpMmdb()
            changed = true
        }

        if (changed) {
            Log.i("ProxyHardener: seeded default geox-url mirrors")
        }

        return changed
    }
}
