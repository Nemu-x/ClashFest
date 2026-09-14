package com.github.kr328.clash.service.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.util.UUID

/**
 * Operator chrome for notifications about a subscription: accent colour and cached logo, only
 * when that subscription opted into branding (`X-Branding-Enabled`). Shared by the VPN status
 * notification and the profile-update result notifications.
 */
object BrandNotificationChrome {
    data class Chrome(val name: String?, val accentColor: Int?, val logoPath: String?) {
        fun logo(): Bitmap? = logoPath
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
    }

    fun forProfile(context: Context, uuid: UUID?): Chrome {
        if (uuid == null) return Chrome(null, null, null)
        val store = BrandStore(context)
        if (!store.isActiveFor(uuid)) return Chrome(null, null, null)
        val manifest = store.manifestFor(uuid)
        return Chrome(
            name = manifest.name?.takeIf { it.isNotBlank() },
            accentColor = manifest.accentColor?.let { hex -> runCatching { Color.parseColor(hex) }.getOrNull() },
            // The dark-theme logo is the primary one operators always ship.
            logoPath = store.logoPathFor(uuid, darkTheme = true),
        )
    }

    /** Where "Renew" / "Support" should lead for this subscription, best source first. */
    fun actionUrl(context: Context, uuid: UUID?, fromHeaders: String?): String? {
        val manifest = uuid?.let { BrandStore(context).manifestFor(it) }
        return listOfNotNull(
            manifest?.renewUrl,
            fromHeaders,
            manifest?.supportUrl,
            manifest?.cabinetUrl,
        ).firstOrNull { it.isNotBlank() }
    }
}
