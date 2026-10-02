package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.github.kr328.clash.common.util.SubscriptionDeviceHeaders
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.SubscriptionIdentityDesign
import com.github.kr328.clash.design.ui.ToastDuration
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class SubscriptionIdentityActivity : BaseActivity<SubscriptionIdentityDesign>() {
    override suspend fun main() {
        val design = SubscriptionIdentityDesign(this)
        val requestHeaders = SubscriptionDeviceHeaders.headerMap(this)
        val hwid = requestHeaders[SubscriptionDeviceHeaders.HEADER_HWID].orEmpty()
        val schemes = buildSupportedSchemeText()
        val diagnostics = buildHwidDiagnosticsText(requestHeaders)

        design.setHwid(hwid)
        design.setSchemes(schemes)
        design.setHwidDiagnostics(diagnostics)
        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                design.requests.onReceive { request ->
                    when (request) {
                        SubscriptionIdentityDesign.Request.CopyHwid -> {
                            copyToClipboard(getString(R.string.subscription_hwid_title), hwid)
                            design.showToast(R.string.copied, ToastDuration.Short)
                        }

                        SubscriptionIdentityDesign.Request.CopySchemes -> {
                            copyToClipboard(getString(R.string.subscription_scheme_title), schemes)
                            design.showToast(R.string.copied, ToastDuration.Short)
                        }

                        SubscriptionIdentityDesign.Request.CopyHwidDiagnostics -> {
                            copyToClipboard(getString(R.string.subscription_hwid_diagnostics_title), diagnostics)
                            design.showToast(R.string.copied, ToastDuration.Short)
                        }

                        is SubscriptionIdentityDesign.Request.OpenUrl -> {
                            runCatching {
                                startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(request.url),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(
            ClipData.newPlainText(label, text),
        )
    }

    private fun buildSupportedSchemeText(): String {
        return "clashfest://installconfig?url=<encoded-url>"
    }

    private fun buildHwidDiagnosticsText(requestHeaders: Map<String, String>): String {
        fun parseBool(value: String): Boolean? = when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

        val active = parseBool(uiStore.subscriptionHwidActive)
        val notSupported = parseBool(uiStore.subscriptionHwidNotSupported)
        val maxReached = parseBool(uiStore.subscriptionHwidMaxDevicesReached)
        val limit = parseBool(uiStore.subscriptionHwidLimit)

        val serverSummary = when {
            active == true && notSupported == true ->
                getString(R.string.subscription_hwid_panel_unsupported)
            active == true && (maxReached == true || limit == true) ->
                getString(R.string.subscription_hwid_panel_limit)
            active == true ->
                getString(R.string.subscription_hwid_panel_accepted)
            active == false ->
                getString(R.string.subscription_hwid_panel_disabled)
            else ->
                getString(R.string.subscription_hwid_panel_unknown)
        }

        return buildString {
            appendLine(serverSummary)
            appendLine()
            appendLine(getString(R.string.subscription_hwid_request_headers))
            appendLine(getString(R.string.diagnostics_header_format, "x-hwid", requestHeaders["x-hwid"].orEmpty().ifBlank { getString(R.string.diagnostics_missing) }))
            appendLine(getString(R.string.diagnostics_header_format, "x-device-os", requestHeaders["x-device-os"].orEmpty().ifBlank { getString(R.string.diagnostics_missing) }))
            appendLine(getString(R.string.diagnostics_header_format, "x-ver-os", requestHeaders["x-ver-os"].orEmpty().ifBlank { getString(R.string.diagnostics_missing) }))
            appendLine(getString(R.string.diagnostics_header_format, "x-device-model", requestHeaders["x-device-model"].orEmpty().ifBlank { getString(R.string.diagnostics_missing) }))
            appendLine(getString(R.string.diagnostics_header_format, "x-app-version", requestHeaders["x-app-version"].orEmpty().ifBlank { getString(R.string.diagnostics_missing) }))
            appendLine()
            appendLine(getString(R.string.subscription_hwid_panel_headers))
            appendLine(getString(R.string.diagnostics_header_format, "x-hwid-active", uiStore.subscriptionHwidActive.ifBlank { getString(R.string.diagnostics_unknown) }))
            appendLine(getString(R.string.diagnostics_header_format, "x-hwid-not-supported", uiStore.subscriptionHwidNotSupported.ifBlank { getString(R.string.diagnostics_unknown) }))
            appendLine(getString(R.string.diagnostics_header_format, "x-hwid-max-devices-reached", uiStore.subscriptionHwidMaxDevicesReached.ifBlank { getString(R.string.diagnostics_unknown) }))
            append(getString(R.string.diagnostics_header_format, "x-hwid-limit", uiStore.subscriptionHwidLimit.ifBlank { getString(R.string.diagnostics_unknown) }))
        }
    }
}
