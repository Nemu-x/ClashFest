package com.github.kr328.clash.design.main

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.core.view.doOnLayout
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.ParsedFlag
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.subscriptionStatus
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.R as MaterialR

/** SVG → [ImageView.setImageBitmap] at the view's pixel width; emoji/globe fallbacks otherwise. */
internal fun bindMainNodeFlag(context: Context, binding: DesignMainBinding, flag: ParsedFlag?) {
    val image = binding.mainActiveNodeFlag
    val emoji = binding.mainActiveNodeFlagEmoji

    fun applyGlobe() {
        emoji.visibility = View.GONE
        image.visibility = View.VISIBLE
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (9 * context.resources.displayMetrics.density).toInt()
        image.setPadding(pad, pad, pad, pad)
        image.setImageResource(R.drawable.ic_baseline_language)
        image.imageTintList = ColorStateList.valueOf(
            context.resolveThemedColor(MaterialR.attr.colorPrimary),
        )
    }

    if (flag == null) {
        applyGlobe()
        return
    }

    fun applyFlag(sizePx: Int) {
        if (sizePx <= 0) return
        val bitmap = FlagDrawableLoader.loadBitmap(context, flag.code, sizePx)
        if (bitmap != null) {
            image.setPadding(0, 0, 0, 0)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.imageTintList = null
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            emoji.visibility = View.GONE
        } else {
            image.visibility = View.GONE
            emoji.text = flag.emoji
            emoji.visibility = View.VISIBLE
        }
    }

    if (image.width > 0) {
        applyFlag(image.width)
    } else {
        image.doOnLayout { applyFlag(it.width.coerceAtLeast(1)) }
    }
}

internal fun profileUsageLabel(context: Context, uiStore: UiStore, profile: Profile): String {
    if (!profile.imported || profile.pending) return context.getString(R.string.subscription_not_ready)
    val headerUsage = if (profile.type == Profile.Type.Url) {
        com.github.kr328.clash.common.util.SubscriptionUsage.parse(
            uiStore.subscriptionUserinfoFor(profile.uuid)
        )
    } else null
    val status = subscriptionStatus(profile.upload, profile.download, profile.total, profile.expire, headerUsage)
    val traffic = status.remainingBytes?.let {
        context.getString(R.string.main_subscription_traffic_left, it.toBytesString(context))
    } ?: context.getString(R.string.main_subscription_unlimited)
    if (status.expireMs <= 0L) return traffic
    val remaining = status.expireMs - System.currentTimeMillis()
    val hours = remaining.coerceAtLeast(0) / 3_600_000L
    val expiry = when {
        remaining <= 0 -> context.getString(R.string.proxy_sheet_expiry_expired)
        hours >= 24 -> context.getString(R.string.proxy_sheet_expiry_days_hours, hours / 24, hours % 24)
        hours > 0 -> context.getString(R.string.proxy_sheet_expiry_hours, hours)
        else -> context.getString(R.string.main_subscription_less_than_hour)
    }
    return "$expiry\n$traffic"
}
