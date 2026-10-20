package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.AdapterProfileBinding
import com.github.kr328.clash.design.databinding.BottomSheetProxyGroupsBinding
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.ParsedFlag
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.color.MaterialColors

internal fun applyActiveVisuals(holder: ProfileAdapter.ProfileHolder, profile: Profile) {
    val chip = holder.binding.activeStatusChip
    val context = chip.context
    holder.binding.profileCard.strokeWidth = context.dp(1)
    if (profile.active) {
        chip.visibility = View.VISIBLE
        chip.text = context.getString(R.string.profile_active_status)
        chip.setBackgroundResource(R.drawable.bg_m3_status_chip)
        chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimaryContainer))
    } else {
        chip.visibility = View.GONE
        chip.text = context.getString(R.string.profile_inactive_status)
        chip.setBackgroundResource(R.drawable.bg_m3_status_chip_neutral)
        chip.setTextColor(MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
}

internal fun bindUsageAndProgress(holder: ProfileAdapter.ProfileHolder, profile: Profile) {
    val binding = holder.binding
    val used = profile.upload + profile.download
    val showTraffic =
        profile.imported &&
            !profile.pending &&
            (used > 0L || profile.total >= 2L)
    if (showTraffic) {
        binding.usageSummary.visibility = View.VISIBLE
        binding.usageSummary.text = formatProfileUsageLine(binding.root.context, profile)
    } else {
        binding.usageSummary.visibility = View.GONE
    }
    if (profile.imported && !profile.pending && profile.total >= 2L) {
        binding.usageProgress.visibility = View.VISIBLE
        binding.usageProgress.max = 1000
        val total = profile.total.coerceAtLeast(1L)
        val frac = (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        binding.usageProgress.progress = (frac * 1000).toInt()
    } else {
        binding.usageProgress.visibility = View.GONE
    }
}

internal fun bindProfileFlag(
    binding: AdapterProfileBinding,
    context: Context,
    flag: ParsedFlag?,
) {
    if (flag != null) {
        binding.profileFlagCard.visibility = View.VISIBLE
        binding.profileIcon.visibility = View.GONE
        binding.profileIconEmoji.visibility = View.GONE
        val sizePx = context.dp(40)
        val bitmap = FlagDrawableLoader.loadBitmap(context, flag.code, sizePx)
        if (bitmap != null) {
            binding.profileFlagImage.setImageBitmap(bitmap)
            binding.profileFlagImage.visibility = View.VISIBLE
            binding.profileFlag.visibility = View.GONE
        } else {
            binding.profileFlagImage.visibility = View.GONE
            binding.profileFlag.text = flag.emoji
            binding.profileFlag.visibility = View.VISIBLE
        }
    } else {
        binding.profileFlagCard.visibility = View.GONE
        binding.profileFlagImage.visibility = View.GONE
        binding.profileFlag.visibility = View.GONE
        binding.profileIcon.visibility = View.VISIBLE
        binding.profileIconEmoji.visibility = View.GONE
    }
}

internal fun bindSubscriptionExpiry(
    sheet: BottomSheetProxyGroupsBinding,
    profile: Profile,
    context: Context,
) {
    val text = formatExpiryLeft(profile.expire, context)
    if (text == null) {
        sheet.proxySheetSubExpiry.visibility = View.GONE
    } else {
        sheet.proxySheetSubExpiry.visibility = View.VISIBLE
        sheet.proxySheetSubExpiry.text = text
    }
}
