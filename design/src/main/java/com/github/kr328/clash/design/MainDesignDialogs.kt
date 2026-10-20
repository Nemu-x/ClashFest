package com.github.kr328.clash.design

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.kr328.clash.common.branding.BrandManifest
import com.github.kr328.clash.service.branding.BrandStore
import com.github.kr328.clash.service.store.ServiceStore
import java.io.File
import java.io.FileOutputStream
import com.google.android.material.R as MaterialR
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.databinding.BottomSheetMainModeBinding
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.MainDesign.Request

/*
 * Dialogs and sheets launched from [MainDesign]: About-sheet branding, the debug brand tester
 * and the mode sheet.
 */

/**
 * Hidden dev/testing panel (About logo ×10, debug builds only): inject a full fake operator
 * [BrandManifest] into the active profile so branding can be exercised live, without a real
 * branded subscription. Every field editable; the three flags are Unset/Yes/No dropdowns;
 * Randomize fills sample values; "Mock logo" writes the app icon as the brand logo.
 * Branding is explicit opt-in — nothing applies unless **Enabled = Yes** (X-Branding-Enabled).
 * See architecture/backend/branding.md (F-BRAND-3).
 */
internal fun MainDesign.showBrandDevDialog() {
    val uuid = ServiceStore(context).activeProfile
    if (uuid == null) {
        Toast.makeText(context, "No active profile — select a subscription first", Toast.LENGTH_LONG).show()
        return
    }
    val store = BrandStore(context)
    val cur = store.manifestFor(uuid)
    val dp = context.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    fun field(value: String?): EditText = EditText(context).apply {
        setText(value ?: ""); setSingleLine()
    }
    val boolOpts = arrayOf("Unset", "Yes", "No")
    fun boolSpinner(v: Boolean?): Spinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, boolOpts)
        setSelection(when (v) { true -> 1; false -> 2; else -> 0 })
    }
    fun Spinner.boolValue(): Boolean? = when (selectedItemPosition) { 1 -> true; 2 -> false; else -> null }

    val fName = field(cur.name); val fTagline = field(cur.tagline); val fAccent = field(cur.accentColor)
    val fLogo = field(cur.logoUrl); val fLogoLight = field(cur.logoLightUrl)
    val fWebsite = field(cur.websiteUrl); val fSupport = field(cur.supportUrl)
    val fTelegram = field(cur.telegramUrl); val fBot = field(cur.botUrl)
    val fPrivacy = field(cur.privacyUrl); val fTerms = field(cur.termsUrl)
    val fHelp = field(cur.helpUrl); val fStatus = field(cur.statusUrl)
    val fRenew = field(cur.renewUrl); val fCabinet = field(cur.cabinetUrl)
    val fUser = field(cur.userDisplayName); val fGreeting = field(cur.greeting)
    val fPrimaryGroup = field(cur.primaryProxyGroup)
    val fGroupLayout = field(cur.proxyGroupLayout)
    val sEnabled = boolSpinner(cur.enabled ?: true)      // default Yes so Apply themes immediately
    val sHideRouting = boolSpinner(cur.hideRouting)
    val sHideGlobal = boolSpinner(cur.hideGlobalMode)
    val sOperatorTab = boolSpinner(cur.showOperatorTab)

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(4), px(20), 0)
    }
    fun row(l: String, v: View) {
        container.addView(TextView(context).apply {
            text = l; textSize = 12f; setPadding(0, px(10), 0, px(2))
            setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant))
        })
        container.addView(v)
    }

    val randomBtn = Button(context).apply { text = "🎲 Randomize" }
    val mockLogoBtn = Button(context).apply { text = "🖼 Mock logo (app icon)" }
    container.addView(randomBtn); container.addView(mockLogoBtn)

    row("Enabled (master switch)", sEnabled)
    row("Name", fName); row("Tagline", fTagline); row("Accent hex (#RRGGBB)", fAccent)
    row("Logo URL", fLogo); row("Logo Light URL", fLogoLight)
    row("Hide Routing tab", sHideRouting); row("Show Operator tab", sOperatorTab)
    row("Hide Global mode (policy — works even with Enabled=No)", sHideGlobal)
    row("User display name", fUser); row("Greeting", fGreeting)
    row("Primary proxy group (policy)", fPrimaryGroup)
    row("Proxy group layout: tabs / dropdown (policy)", fGroupLayout)
    row("Website URL", fWebsite); row("Support URL", fSupport); row("Telegram URL", fTelegram)
    row("Bot URL", fBot); row("Privacy URL", fPrivacy); row("Terms URL", fTerms)
    row("Help URL", fHelp); row("Status URL", fStatus); row("Renew URL", fRenew); row("Cabinet URL", fCabinet)

    randomBtn.setOnClickListener {
        val palette = listOf("#7C5CFF", "#5EE6A8", "#FF5E35", "#7FB2FF", "#F2C14E", "#EC4899", "#22D3EE")
        val n = (100..999).random()
        fName.setText("Operator $n"); fTagline.setText("Fast & secure, always on")
        fAccent.setText(palette.random())
        fWebsite.setText("https://example.com"); fSupport.setText("https://t.me/nemux_dev")
        fTelegram.setText("https://t.me/nemux_dev"); fBot.setText("https://t.me/some_bot")
        fPrivacy.setText("https://example.com/privacy"); fTerms.setText("https://example.com/terms")
        fHelp.setText("https://example.com/help"); fStatus.setText("https://example.com/status")
        fRenew.setText("https://example.com/renew"); fCabinet.setText("https://t.me/some_bot?startapp=demo")
        fUser.setText("dev_user_$n"); fGreeting.setText("Welcome back — 30 days left")
        sEnabled.setSelection(1); sHideRouting.setSelection((0..2).random()); sOperatorTab.setSelection(1)
    }
    mockLogoBtn.setOnClickListener {
        runCatching {
            val bmp = context.packageManager.getApplicationIcon(context.packageName).toBitmap(px(96), px(96))
            val f = File(context.filesDir, "brand_mock_logo.png")
            FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            store.setLogoPaths(uuid, f.absolutePath, f.absolutePath)
            Toast.makeText(context, "Mock logo set — press Apply", Toast.LENGTH_SHORT).show()
        }
    }

    MaterialAlertDialogBuilder(context)
        .setTitle("Brand tester (dev)")
        .setView(ScrollView(context).apply { addView(container) })
        .setPositiveButton("Apply") { _, _ ->
            fun s(e: EditText) = e.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            store.setManifest(
                uuid,
                BrandManifest(
                    name = s(fName), tagline = s(fTagline), accentColor = s(fAccent),
                    logoUrl = s(fLogo), logoLightUrl = s(fLogoLight),
                    websiteUrl = s(fWebsite), supportUrl = s(fSupport), telegramUrl = s(fTelegram),
                    botUrl = s(fBot), privacyUrl = s(fPrivacy), termsUrl = s(fTerms),
                    helpUrl = s(fHelp), statusUrl = s(fStatus), renewUrl = s(fRenew),
                    cabinetUrl = s(fCabinet), userDisplayName = s(fUser), greeting = s(fGreeting),
                    hideRouting = sHideRouting.boolValue(), showOperatorTab = sOperatorTab.boolValue(),
                    hideGlobalMode = sHideGlobal.boolValue(),
                    lockConfigScript = cur.lockConfigScript,
                    primaryProxyGroup = s(fPrimaryGroup),
                    proxyGroupLayout = com.github.kr328.clash.common.branding.BrandValidation
                        .parseProxyGroupLayout(s(fGroupLayout)),
                    enabled = sEnabled.boolValue(),
                ),
            )
            // Kick a dashboard refresh: fetch() re-reads the manifest and the accent
            // staleness check resolves a soft recreate — same convergence path a real
            // operator brand change takes (never Activity.recreate on Main).
            profileExpandChanged.trySend(Unit)
        }
        .setNeutralButton("Clear brand") { _, _ ->
            store.clearFor(uuid)
            profileExpandChanged.trySend(Unit)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun MainDesign.applyBrandToAbout(binding: DesignAboutBinding) {
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

private fun MainDesign.showModeSheet() {
    val sheet = BottomSheetMainModeBinding.inflate(context.layoutInflater)
    val dialog = AppBottomSheetDialog(context, fitContentHeight = true)
    val isGlobal = currentModeSegment == TunnelState.Mode.Global

    sheet.modeRuleSelected.visibility = if (isGlobal) View.INVISIBLE else View.VISIBLE
    sheet.modeGlobalSelected.visibility = if (isGlobal) View.VISIBLE else View.INVISIBLE
    sheet.modeRuleRow.setOnClickListener {
        requests.trySend(Request.PatchModeRule)
        dialog.dismiss()
    }
    sheet.modeGlobalRow.setOnClickListener {
        requests.trySend(Request.PatchModeGlobal)
        dialog.dismiss()
    }

    dialog.setContentView(sheet.root)
    dialog.show()
}
