package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class ProxyTransportInfo(
    val network: String = "",
    val tls: Boolean = false,
    val reality: Boolean = false,
    /** Raw mihomo proxy `type` (e.g. "vless", "ss") — lets the picker show the protocol
     *  badge offline, when there's no live engine to report the type. Empty = unknown. */
    val type: String = "",
    /** A non-empty `finalmask` block (Xray masks under the transport, core patch 0041). Says the
     *  masks are configured, not that the engine could use them: an unusable block is logged
     *  by the core and fails the node's dials. */
    val finalmask: Boolean = false,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProxyTransportInfo> {
        override fun createFromParcel(parcel: Parcel): ProxyTransportInfo {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ProxyTransportInfo?> {
            return arrayOfNulls(size)
        }
    }
}
