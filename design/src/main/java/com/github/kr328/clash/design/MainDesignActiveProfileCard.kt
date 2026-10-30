package com.github.kr328.clash.design

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import androidx.core.view.doOnLayout
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.FlagParser
import com.github.kr328.clash.design.util.ParsedFlag
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.design.R
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.model.Profile

/*
 * Home active-profile card for [MainDesign]: profile/node summary, usage and expiry labels, the
 * node flag, support-link resolution and the announcement read-state hash.
 */

internal fun MainDesign.announcementHash(
    message: String,
    url: String?,
    supportUrl: String?,
    source: String?,
): String {
    val payload = "$message\u0000${url.orEmpty()}\u0000${supportUrl.orEmpty()}\u0000${source.orEmpty()}"
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(payload.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        val hex = "0123456789abcdef"
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(hex[v ushr 4]); append(hex[v and 0x0F])
        }
    }
}

internal fun MainDesign.renderActiveProfileCard(profile: Profile?) {
    val p = profile
    val desyncOnly = p?.isDesyncOnly == true
    // Also when a Desync-only profile meets a Global mode set elsewhere (saved preference, paired
    // controller): reconcile flips it back to Rule.
    if (desyncOnly != activeProfileDesyncOnly || (desyncOnly && currentModeSegment == TunnelState.Mode.Global)) {
        activeProfileDesyncOnly = desyncOnly
        if (!desyncOnly) desyncStatusLine = null
        reconcileGlobalModeForBrand()
    }
    binding.mainActiveProfileValue.text = p?.name.orEmpty().ifBlank { context.getString(R.string.not_selected) }
    binding.mainActiveProfileMeta.text = when {
        p == null -> ""
        desyncOnly -> context.getString(R.string.desync_only_badge)
        else -> listOfNotNull("${profileMetaLabel(p)} • ${usageLabel(p)}", desyncOverlayLine).joinToString("\n")
    }
    binding.mainActiveProfileMeta.visibility = if (p != null) View.VISIBLE else View.GONE
    // Desync only: "update" regenerates the profile from its settings.
    val showUpdate = p?.imported == true && (p.type != Profile.Type.File || desyncOnly)
    binding.mainActiveProfileUpdate.visibility = if (showUpdate) View.VISIBLE else View.GONE
    val showSupport = !resolveSupportUrl().isNullOrBlank()
    binding.mainActiveProfileSupport.visibility = if (showSupport) View.VISIBLE else View.GONE
    if (p != null && desyncOnly) {
        renderDesyncStrategyRow(p)
        return
    }
    binding.mainActiveNodeTitle.setText(R.string.main_node_title)
    // Redesign 1.0: Node row shows the active node as a circular flag icon (like the picker) +
    // the clean name, and opens the proxy picker for the active profile (loads groups first).
    val nodeName = p?.let { profileAdapter.activeNodeDisplayName(it) }
    val nodeFlag = FlagParser.parse(nodeName)
    bindMainNodeFlag(nodeFlag)
    val cleanName = nodeName?.let { n ->
        nodeFlag?.let { n.removePrefix(it.emoji).trimStart(' ', '|', '-', '_', '.', ':').ifBlank { n } } ?: n
    }
    binding.mainActiveNodeValue.text = cleanName ?: context.getString(R.string.main_node_choose)
    binding.mainActiveNodeRow.setOnClickListener { p?.let { openNodePicker(it) } }
}

/** Desync only: the node row shows the strategy and opens the profile's Desync settings. */
private fun MainDesign.renderDesyncStrategyRow(profile: Profile) {
    binding.mainActiveNodeTitle.setText(R.string.desync_strategy)
    val image = binding.mainActiveNodeFlag
    binding.mainActiveNodeFlagEmoji.visibility = View.GONE
    image.visibility = View.VISIBLE
    image.scaleType = ImageView.ScaleType.FIT_CENTER
    val pad = (9 * context.resources.displayMetrics.density).toInt()
    image.setPadding(pad, pad, pad, pad)
    image.setImageResource(R.drawable.ic_baseline_bolt)
    image.imageTintList = ColorStateList.valueOf(
        context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary),
    )
    binding.mainActiveNodeValue.text = desyncStatusLine ?: context.getString(R.string.desync_settings_summary)
    binding.mainActiveNodeRow.setOnClickListener { onOpenDesyncSettings?.invoke(profile) }
}

/** SVG → [ImageView.setImageBitmap] at the view's pixel width; emoji/globe fallbacks otherwise. */
private fun MainDesign.bindMainNodeFlag(flag: ParsedFlag?) {
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
            context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary),
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

internal fun MainDesign.resolveSupportUrl(): String? =
    activeAnnouncementSupportUrl?.takeIf { it.isNotBlank() }
        ?: uiStore.supportUrl.takeIf { it.isNotBlank() }

private fun MainDesign.profileMetaLabel(profile: Profile): String {
    val base = profileTypeLabel(profile)
    val daysLeft = daysLeftValue(profile) ?: return base
    return "$base • ${context.getString(R.string.sub_announcement_days_left)} $daysLeft"
}

private fun MainDesign.profileTypeLabel(profile: Profile): String = when (profile.type) {
    Profile.Type.Url -> context.getString(R.string.url)
    Profile.Type.File -> context.getString(R.string.file)
    Profile.Type.External -> context.getString(R.string.external)
}

private fun MainDesign.daysLeftValue(profile: Profile): Int? {
    val expireAt = profile.expire.takeIf { it > 0L } ?: return null
    val now = System.currentTimeMillis()
    if (expireAt <= now) return 0

    val millisLeft = expireAt - now
    return ((millisLeft + 86_400_000L - 1L) / 86_400_000L).toInt().coerceAtLeast(1)
}

private fun MainDesign.usageLabel(profile: Profile): String {
    val headerUsage = if (profile.type == Profile.Type.Url) {
        com.github.kr328.clash.common.util.SubscriptionUsage.parse(
            uiStore.subscriptionUserinfo.takeIf { it.isNotBlank() }
        )
    } else null
    val used = headerUsage?.used ?: (profile.upload + profile.download)
    val usedText = used.toBytesString()
    val total = headerUsage?.total ?: profile.total
    if (total < 2L) return "$usedText / ${context.getString(R.string.sub_announcement_unlimited)}"
    return "$usedText / ${total.toBytesString()}"
}
