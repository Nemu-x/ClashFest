package com.github.kr328.clash.design.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.branding.BrandHolder
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater

/**
 * The About bottom sheet. The host owns the "already open" guard and the Home hero's reaction to the
 * sheet covering it, so those arrive as callbacks.
 */
internal fun showAboutSheet(
    context: Context,
    uiStore: UiStore,
    brandHolder: BrandHolder,
    versionName: String,
    coreVersion: String,
    initialUpdateStatus: String?,
    onCheckUpdates: (((Boolean) -> Unit, (String?) -> Unit) -> Unit)?,
    isOpen: () -> Boolean,
    tryOpen: () -> Boolean,
    onClosed: () -> Unit,
    onBeforeShow: () -> Unit,
    onDevMenu: () -> Unit,
) {
    if (isOpen()) return
    val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
        this.versionName = versionName
        this.coreVersion = coreVersion
    }
    val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
    dialog.setContentView(binding.root)
    applyBrandToAbout(context, binding, brandHolder, uiStore)

    // Hidden dev brand tester: tap the app icon ×10 (debug builds only).
    val debuggable = (context.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable) {
        var iconTaps = 0
        binding.aboutAppIcon.setOnClickListener {
            if (++iconTaps >= 10) {
                iconTaps = 0
                onDevMenu()
            }
        }
    }

    binding.aboutGithubIcon.apply {
        visibility = View.VISIBLE
        setOnClickListener {
            runCatching {
                val url = context.getString(R.string.clashfest_repo_url)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    binding.aboutTelegramIcon.apply {
        visibility = View.VISIBLE
        setOnClickListener {
            runCatching {
                val url = context.getString(R.string.clashfest_telegram_url)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    val support = uiStore.supportUrl.takeIf { it.isNotBlank() }
    if (support != null) {
        binding.aboutSupportButton.apply {
            visibility = View.VISIBLE
            text = context.getString(R.string.about_support)
            icon = null
            setOnClickListener {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(support))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    if (onCheckUpdates != null) {
        // Status line shows the current/known update state up front (so "About & updates"
        // reflects a cached update without re-checking); the button is the action.
        val statusLine = binding.aboutUpdateStatus
        fun showStatus(text: String?) {
            statusLine.text = text.orEmpty()
            statusLine.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        showStatus(initialUpdateStatus)

        binding.aboutCheckUpdatesButton.apply {
            visibility = View.VISIBLE

            fun setLoading(loading: Boolean) {
                isEnabled = !loading
                alpha = if (loading) 0.65f else 1f
                text = context.getString(
                    if (loading) R.string.about_checking_updates else R.string.about_check_updates,
                )
            }

            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                showStatus(null)
                setLoading(true)
                onCheckUpdates(::setLoading) { text -> showStatus(text) }
            }
        }
    }

    if (!tryOpen()) return
    dialog.setOnDismissListener {
        onClosed()
    }
    try {
        onBeforeShow()
        dialog.show()
    } catch (error: Throwable) {
        onClosed()
        throw error
    }
}

private fun applyBrandToAbout(
    context: Context,
    binding: DesignAboutBinding,
    brandHolder: BrandHolder,
    uiStore: UiStore,
) {
    // Layout defaults already render the unbranded About correctly:
    //   - about_brand_name = "powered by ClashFest"
    //   - about_default_subtitle = "ClashFest"
    //   - about_brand_tagline / about_brand_powered_by / brand chips /
    //     renew button / reset button all start hidden.
    // When no brand is active we leave everything as-is.
    if (!brandHolder.isActive) return

    val brand = brandHolder.manifest
    val brandName = brand.name?.takeIf { it.isNotBlank() }
    if (brandName != null) {
        binding.aboutBrandName.text = brandName
        binding.aboutDefaultSubtitle.visibility = View.GONE
        binding.aboutBrandPoweredBy.visibility = View.VISIBLE
    }
    val tagline = brand.tagline?.takeIf { it.isNotBlank() }
    if (tagline != null) {
        binding.aboutBrandTagline.text = tagline
        binding.aboutBrandTagline.visibility = View.VISIBLE
    }
    brandHolder.logoPath?.let {
        com.github.kr328.clash.design.branding.BrandLogoBinder.bind(
            binding.aboutAppIcon, it,
        )
    }

    // Per-user display name. Operators typically wire this via a panel
    // template variable (e.g. `X-Brand-User-Display-Name: {{USERNAME}}`)
    // so the panel substitutes the actual name before sending.
    val displayName = brand.userDisplayName?.takeIf { it.isNotBlank() }
    if (displayName != null) {
        binding.aboutBrandUserDisplayName.text = context.getString(
            R.string.about_brand_logged_in_as,
            displayName,
        )
        binding.aboutBrandUserDisplayName.visibility = View.VISIBLE
    }

    // Operator links chip group.
    val linksGroup = binding.aboutBrandLinks
    linksGroup.removeAllViews()
    val cabinetLink = brand.cabinetUrl?.takeIf { it.isNotBlank() }
        ?: uiStore.profileWebPageUrl.takeIf { it.isNotBlank() }
    val links = listOfNotNull(
        cabinetLink?.let { it to R.string.about_brand_cabinet },
        brand.websiteUrl?.let { it to R.string.about_brand_website },
        brand.supportUrl?.let { it to R.string.about_brand_support },
        brand.telegramUrl?.let { it to R.string.about_brand_telegram },
        brand.botUrl?.let { it to R.string.about_brand_bot },
        brand.helpUrl?.let { it to R.string.about_brand_help },
        brand.privacyUrl?.let { it to R.string.about_brand_privacy },
        brand.termsUrl?.let { it to R.string.about_brand_terms },
        brand.statusUrl?.let { it to R.string.about_brand_status },
    )
    if (links.isNotEmpty()) {
        binding.aboutBrandDivider.visibility = View.VISIBLE
        linksGroup.visibility = View.VISIBLE
        links.forEach { (url, label) ->
            val chip = com.google.android.material.chip.Chip(context).apply {
                text = context.getString(label)
                isClickable = true
                isCheckable = false
                setEnsureMinTouchTargetSize(false)
                minHeight = (28 * resources.displayMetrics.density).toInt()
                chipMinHeight = 28 * resources.displayMetrics.density
                chipStartPadding = 10 * resources.displayMetrics.density
                chipEndPadding = 10 * resources.displayMetrics.density
                textStartPadding = 0f
                textEndPadding = 0f
                setTextAppearance(R.style.TextAppearance_App_LabelSmall)
                setOnClickListener {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            linksGroup.addView(chip)
        }
    } else {
        binding.aboutBrandDivider.visibility = View.GONE
        linksGroup.visibility = View.GONE
    }

    // Renew button — separate from chip group because it's a primary CTA.
    val renew = brand.renewUrl
    if (!renew.isNullOrBlank()) {
        binding.aboutBrandRenewButton.visibility = View.VISIBLE
        binding.aboutBrandRenewButton.setOnClickListener {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(renew))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    } else {
        binding.aboutBrandRenewButton.visibility = View.GONE
    }

}
