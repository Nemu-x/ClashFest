@file:UseSerializers(UUIDSerializer::class)

package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import com.github.kr328.clash.service.util.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.*

@Serializable
data class Profile(
    val uuid: UUID,
    val name: String,
    val type: Type,
    val source: String,
    val active: Boolean,
    val interval: Long,
    val upload: Long,
    var download: Long,
    val total: Long,
    val expire: Long,


    val updatedAt: Long,
    val imported: Boolean,
    val pending: Boolean,
    /** age identity (AGE-SECRET-KEY-…) for age-encrypted subscriptions; null = plain. */
    val ageSecretKey: String? = null,
    /**
     * [DesyncProfileSettings] JSON of a "Desync only" profile (a [Type.File] profile whose
     * config.yaml is generated from these settings, never fetched); null for every other profile.
     */
    val desyncSettings: String? = null,
) : Parcelable {
    val isDesyncOnly: Boolean
        get() = desyncSettings != null

    enum class Type {
        File, Url, External
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Profile> {
        override fun createFromParcel(parcel: Parcel): Profile {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<Profile?> {
            return arrayOfNulls(size)
        }
    }
}