package com.github.kr328.clash.design.main

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.branding.BrandHolder
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.resolveThemedColor
import com.google.android.material.R as MaterialR

internal fun bindOperatorPage(
    context: Context,
    binding: DesignMainBinding,
    brandHolder: BrandHolder,
    uiStore: UiStore,
    onOpenBrandUrl: (String) -> Unit,
) {
    if (!brandHolder.isActive) return
    val brand = brandHolder.manifest

    // Logo
    val logo = binding.operatorLogo
    val logoPath = brandHolder.logoPath
    if (logoPath != null) {
        com.github.kr328.clash.design.branding.BrandLogoBinder.bind(logo, logoPath)
        logo.visibility = View.VISIBLE
    } else {
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
    }

    // Brand name (fallback to ClashFest if operator didn't supply — the page only
    // exists when brand is active, so something meaningful should always be shown).
    binding.operatorName.text = brand.name?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.launch_name_meta)

    val tagline = brand.tagline?.takeIf { it.isNotBlank() }
    if (tagline != null) {
        binding.operatorTagline.text = tagline
        binding.operatorTagline.visibility = View.VISIBLE
    } else {
        binding.operatorTagline.visibility = View.GONE
    }

    // Greeting hero line. Operators typically wire this through a panel
    // template variable so the panel substitutes the user's name / days
    // remaining / etc. into the header before sending. If the operator
    // only supplied a display name (no greeting), fall back to a built-in
    // "Hello, <name>!" so the hero block still feels personal.
    val greeting = brand.greeting?.takeIf { it.isNotBlank() }
        ?: brand.userDisplayName?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.operator_greeting_default, it)
        }
    if (greeting != null) {
        binding.operatorGreeting.text = greeting
        binding.operatorGreeting.visibility = View.VISIBLE
    } else {
        binding.operatorGreeting.visibility = View.GONE
    }

    // Renew CTA (primary action)
    val renew = brand.renewUrl?.takeIf { it.isNotBlank() }
    if (renew != null) {
        binding.operatorRenewButton.visibility = View.VISIBLE
        binding.operatorRenewButton.setOnClickListener { onOpenBrandUrl(renew) }
    } else {
        binding.operatorRenewButton.visibility = View.GONE
    }

    // Personal cabinet entry-point. Stays tonal-secondary even when Renew
    // is absent — the visual difference between Renew (filled) and
    // Cabinet (tonal) is a deliberate hierarchy cue: Renew converts the
    // user into a paying / renewing customer, Cabinet only navigates.
    // `profile-web-page-url` (common namespace, parsed for a long time but never surfaced) is
    // the fallback when the operator did not ship a dedicated X-Brand-Cabinet-URL.
    val cabinet = brand.cabinetUrl?.takeIf { it.isNotBlank() }
        ?: uiStore.profileWebPageUrl.takeIf { it.isNotBlank() }
    if (cabinet != null) {
        binding.operatorCabinetButton.visibility = View.VISIBLE
        binding.operatorCabinetButton.setOnClickListener { onOpenBrandUrl(cabinet) }
    } else {
        binding.operatorCabinetButton.visibility = View.GONE
    }

    // Grouped link rows. Each row gets a leading icon and a trailing chevron;
    // groups are separated by small headers so the user can scan the page
    // by intent (talk to humans / read docs / check status).
    val container = binding.operatorLinks
    container.removeAllViews()

    val operatorLinks = listOfNotNull(
        brand.websiteUrl?.let { OperatorLink(it, R.string.about_brand_website, R.drawable.ic_baseline_language) },
        brand.supportUrl?.let { OperatorLink(it, R.string.about_brand_support, R.drawable.ic_baseline_headset_mic) },
        brand.telegramUrl?.let { OperatorLink(it, R.string.about_brand_telegram, R.drawable.ic_baseline_campaign) },
        brand.botUrl?.let { OperatorLink(it, R.string.about_brand_bot, R.drawable.ic_baseline_subscriptions) },
    )
    val helpLinks = listOfNotNull(
        brand.helpUrl?.let { OperatorLink(it, R.string.about_brand_help, R.drawable.ic_outline_info) },
        brand.statusUrl?.let { OperatorLink(it, R.string.about_brand_status, R.drawable.ic_baseline_info) },
    )
    val legalLinks = listOfNotNull(
        brand.privacyUrl?.let { OperatorLink(it, R.string.about_brand_privacy, R.drawable.ic_outline_article) },
        brand.termsUrl?.let { OperatorLink(it, R.string.about_brand_terms, R.drawable.ic_baseline_article) },
    )

    addOperatorGroup(context, container, R.string.operator_group_contact, operatorLinks, onOpenBrandUrl)
    addOperatorGroup(context, container, R.string.operator_group_help, helpLinks, onOpenBrandUrl)
    addOperatorGroup(context, container, R.string.operator_group_legal, legalLinks, onOpenBrandUrl)
}

private data class OperatorLink(
    val url: String,
    val labelRes: Int,
    val iconRes: Int,
)

private fun addOperatorGroup(
    context: Context,
    container: android.widget.LinearLayout,
    titleRes: Int,
    links: List<OperatorLink>,
    onOpenBrandUrl: (String) -> Unit,
) {
    if (links.isEmpty()) return
    val density = context.resources.displayMetrics.density
    fun px(dp: Int) = (dp * density).toInt()

    // Group header — Material Title-Small in colorOnSurfaceVariant
    val header = android.widget.TextView(context).apply {
        text = context.getString(titleRes)
        setTextAppearance(R.style.TextAppearance_App_LabelLarge)
        setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant))
        setPadding(px(4), px(14), px(4), px(6))
        isAllCaps = true
        letterSpacing = 0.08f
        textSize = 12f
    }
    container.addView(header)

    // Rows wrapper card so dividers render cleanly between rows
    val card = com.google.android.material.card.MaterialCardView(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        radius = px(14).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(context.resolveThemedColor(MaterialR.attr.colorSurfaceContainerHigh))
    }
    val cardInner = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }
    card.addView(cardInner)
    container.addView(card)

    links.forEachIndexed { i, link ->
        cardInner.addView(buildOperatorRow(context, link, onOpenBrandUrl))
        if (i < links.lastIndex) {
            val divider = View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, px(1),
                ).apply { setMargins(px(48), 0, 0, 0) }
                setBackgroundColor(
                    context.resolveThemedColor(MaterialR.attr.colorOutlineVariant)
                )
            }
            cardInner.addView(divider)
        }
    }
}

private fun buildOperatorRow(context: Context, link: OperatorLink, onOpenBrandUrl: (String) -> Unit): View {
    val density = context.resources.displayMetrics.density
    fun px(dp: Int) = (dp * density).toInt()

    val row = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(px(14), px(14), px(14), px(14))
        background = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(context, R.drawable.bg_proxy_node_row)
        isClickable = true
        isFocusable = true
        setOnClickListener { onOpenBrandUrl(link.url) }
    }
    // Leading icon
    val icon = android.widget.ImageView(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(px(22), px(22)).apply {
            marginEnd = px(14)
        }
        setImageResource(link.iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(
            context.resolveThemedColor(MaterialR.attr.colorPrimary)
        )
    }
    row.addView(icon)
    // Label takes remaining width
    val label = android.widget.TextView(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        )
        text = context.getString(link.labelRes)
        setTextAppearance(R.style.TextAppearance_App_BodyMedium)
        setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurface))
    }
    row.addView(label)
    // Trailing chevron
    val chevron = android.widget.ImageView(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(px(16), px(16))
        setImageResource(R.drawable.ic_baseline_expand_more)
        rotation = -90f
        imageTintList = android.content.res.ColorStateList.valueOf(
            context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant)
        )
    }
    row.addView(chevron)
    return row
}
