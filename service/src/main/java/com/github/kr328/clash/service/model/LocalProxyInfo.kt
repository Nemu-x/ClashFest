package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

/**
 * What the user needs to point another app (or a container) at ClashFest's local proxy.
 *
 * The listener only exists when the user explicitly opts in: on Android 10+ the default
 * [com.github.kr328.clash.service.model.ProxyHardeningMode.Strict] forces every local port to `0`
 * so other apps on the device cannot bypass the per-app routing rules by talking to `127.0.0.1`.
 * [enabled] reflects that opt-in, and [available] whether a listener is actually up right now.
 */
@Serializable
data class LocalProxyInfo(
    /** User turned the local proxy on. */
    val enabled: Boolean = false,
    /**
     * A listener is actually reachable — i.e. [enabled] and the tunnel is running. When false the
     * UI shows the credentials but tells the user to connect / enable it first.
     */
    val available: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) : Parcelable {
    /** `user:pass@host:port` — handy for clients that take a single proxy URL. */
    val socksUrl: String
        get() = if (username.isEmpty()) "socks5://$host:$port" else "socks5://$username:$password@$host:$port"

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<LocalProxyInfo> {
        override fun createFromParcel(parcel: Parcel): LocalProxyInfo {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<LocalProxyInfo?> {
            return arrayOfNulls(size)
        }
    }
}
