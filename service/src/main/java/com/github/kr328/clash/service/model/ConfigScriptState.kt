package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.model.ConfigScriptError
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

/**
 * The profile's user JS script as the editor sees it.
 *
 * [locked] is the operator policy (`X-Brand-Lock-Config-Script`): the editor shows the stored
 * script read-only and explains why, rather than pretending the feature does not exist — a user
 * who wrote a script before their operator locked it should be able to see what stopped running.
 */
@Serializable
data class ConfigScriptState(
    val source: String = "",
    val enabled: Boolean = true,
    val locked: Boolean = false,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) =
        Parcelizer.encodeToParcel(serializer(), parcel, this)

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConfigScriptState> {
        override fun createFromParcel(parcel: Parcel): ConfigScriptState =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<ConfigScriptState?> = arrayOfNulls(size)

        /**
         * The starting point a new script gets, so nobody has to go looking up the contract. It is
         * deliberately a working no-op: save it as-is and nothing changes.
         */
        val TEMPLATE = """
            // Runs over the whole config before it reaches the engine.
            // Standard contract: receive the config, return it modified.
            function main(config) {
              // config.dns.enable = true
              // config.rules.unshift("DOMAIN-SUFFIX,example.com,DIRECT")
              return config
            }
        """.trimIndent()
    }
}

/**
 * Why a script could not be applied. [error] carries the stable cause; [message] is the engine's
 * raw text, kept for the log and for the "details" affordance — the UI's own wording comes from
 * [error].
 */
@Serializable
data class ConfigScriptFailure(
    val error: ConfigScriptError,
    val message: String = "",
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) =
        Parcelizer.encodeToParcel(serializer(), parcel, this)

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConfigScriptFailure> {
        override fun createFromParcel(parcel: Parcel): ConfigScriptFailure =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<ConfigScriptFailure?> = arrayOfNulls(size)
    }
}
