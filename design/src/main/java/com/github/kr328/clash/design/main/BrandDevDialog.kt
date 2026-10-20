package com.github.kr328.clash.design.main

import android.content.Context
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
import com.github.kr328.clash.common.branding.BrandManifest
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.service.branding.BrandStore
import com.github.kr328.clash.service.store.ServiceStore
import com.google.android.material.R as MaterialR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream

/**
 * Hidden dev/testing panel (About logo ×10, debug builds only): inject a full fake operator
 * [BrandManifest] into the active profile so branding can be exercised live, without a real
 * branded subscription. Every field editable; the three flags are Unset/Yes/No dropdowns;
 * Randomize fills sample values; "Mock logo" writes the app icon as the brand logo.
 * Branding is explicit opt-in — nothing applies unless **Enabled = Yes** (X-Branding-Enabled).
 * See architecture/backend/branding.md (F-BRAND-3).
 */
internal fun showBrandDevDialog(context: Context, onChanged: () -> Unit) {
    val uuid = ServiceStore(context).activeProfile
    if (uuid == null) {
        Toast.makeText(context, R.string.brand_dev_no_profile, Toast.LENGTH_LONG).show()
        return
    }
    val store = BrandStore(context)
    val cur = store.manifestFor(uuid)
    val dp = context.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    fun field(value: String?): EditText = EditText(context).apply {
        setText(value ?: ""); setSingleLine()
    }
    val boolOpts = arrayOf(R.string.brand_dev_unset, R.string.brand_dev_yes, R.string.brand_dev_no).map(context::getString).toTypedArray()
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
    fun row(label: Int, v: View, vararg args: Any) {
        container.addView(TextView(context).apply {
            text = context.getString(label, *args); textSize = 12f; setPadding(0, px(10), 0, px(2))
            setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurfaceVariant))
        })
        container.addView(v)
    }

    fun urlRow(label: Int, view: View) {
        row(R.string.brand_dev_url_format, view, context.getString(label))
    }

    val randomBtn = Button(context).apply { setText(R.string.brand_dev_randomize) }
    val mockLogoBtn = Button(context).apply { setText(R.string.brand_dev_mock_logo) }
    container.addView(randomBtn); container.addView(mockLogoBtn)

    row(R.string.brand_dev_enabled, sEnabled)
    row(R.string.name, fName); row(R.string.brand_dev_tagline, fTagline); row(R.string.brand_dev_accent, fAccent)
    row(R.string.brand_dev_logo, fLogo); row(R.string.brand_dev_logo_light, fLogoLight)
    row(R.string.brand_dev_hide_routing, sHideRouting); row(R.string.brand_dev_show_operator, sOperatorTab)
    row(R.string.brand_dev_hide_global, sHideGlobal)
    row(R.string.brand_dev_user_name, fUser); row(R.string.brand_dev_greeting, fGreeting)
    row(R.string.brand_dev_primary_group, fPrimaryGroup)
    row(R.string.brand_dev_group_layout, fGroupLayout)
    urlRow(R.string.about_brand_website, fWebsite); urlRow(R.string.about_brand_support, fSupport); urlRow(R.string.about_brand_telegram, fTelegram)
    urlRow(R.string.about_brand_bot, fBot); urlRow(R.string.about_brand_privacy, fPrivacy); urlRow(R.string.about_brand_terms, fTerms)
    urlRow(R.string.about_brand_help, fHelp); urlRow(R.string.about_brand_status, fStatus); urlRow(R.string.about_brand_renew, fRenew); urlRow(R.string.about_brand_cabinet, fCabinet)

    randomBtn.setOnClickListener {
        val palette = listOf("#7C5CFF", "#5EE6A8", "#FF5E35", "#7FB2FF", "#F2C14E", "#EC4899", "#22D3EE")
        val n = (100..999).random()
        fName.setText(context.getString(R.string.brand_dev_sample_name, n)); fTagline.setText(R.string.brand_dev_sample_tagline)
        fAccent.setText(palette.random())
        fWebsite.setText("https://example.com"); fSupport.setText("https://t.me/nemux_dev")
        fTelegram.setText("https://t.me/nemux_dev"); fBot.setText("https://t.me/some_bot")
        fPrivacy.setText("https://example.com/privacy"); fTerms.setText("https://example.com/terms")
        fHelp.setText("https://example.com/help"); fStatus.setText("https://example.com/status")
        fRenew.setText("https://example.com/renew"); fCabinet.setText("https://t.me/some_bot?startapp=demo")
        fUser.setText("dev_user_$n"); fGreeting.setText(context.getString(R.string.brand_dev_sample_greeting, 30))
        sEnabled.setSelection(1); sHideRouting.setSelection((0..2).random()); sOperatorTab.setSelection(1)
    }
    mockLogoBtn.setOnClickListener {
        runCatching {
            val bmp = context.packageManager.getApplicationIcon(context.packageName).toBitmap(px(96), px(96))
            val f = File(context.filesDir, "brand_mock_logo.png")
            FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            store.setLogoPaths(uuid, f.absolutePath, f.absolutePath)
            Toast.makeText(context, R.string.brand_dev_mock_logo_ready, Toast.LENGTH_SHORT).show()
        }
    }

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.brand_dev_title)
        .setView(ScrollView(context).apply { addView(container) })
        .setPositiveButton(R.string.yaml_preview_apply) { _, _ ->
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
            onChanged()
        }
        .setNeutralButton(R.string.about_brand_reset) { _, _ ->
            store.clearFor(uuid)
            onChanged()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
