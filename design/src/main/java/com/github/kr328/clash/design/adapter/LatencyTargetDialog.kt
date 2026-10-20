package com.github.kr328.clash.design.adapter

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Asks for a one-shot latency target and hands back a URL ready for URLTest.
 *
 * The last entry is remembered only to prefill the field — the measurement itself is never
 * sticky, so closing the dialog leaves the next tap measuring the default target as before.
 */
internal fun showLatencyTargetDialog(
    context: Context,
    prefill: String,
    onRemember: (String) -> Unit,
    onConfirm: (String) -> Unit,
) {
    val view = context.layoutInflater.inflate(R.layout.dialog_latency_target, null, false)
    val inputLayout = view.findViewById<TextInputLayout>(R.id.latency_target_input_layout)
    val input = view.findViewById<TextInputEditText>(R.id.latency_target_input)
    input.setText(prefill)
    input.setSelection(input.text?.length ?: 0)

    val dialog = MaterialAlertDialogBuilder(context)
        .setView(view)
        .setPositiveButton(R.string.latency_target_run, null)
        .setNegativeButton(R.string.cancel, null)
        .create()

    // Validate without dismissing: setPositiveButton's own listener always closes, which would
    // throw the typed text away on a typo.
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = normalizeLatencyTarget(input.text?.toString().orEmpty())
            if (url == null) {
                inputLayout.error = context.getString(R.string.latency_target_invalid)
                return@setOnClickListener
            }
            onRemember(input.text?.toString()?.trim().orEmpty())
            dialog.dismiss()
            onConfirm(url)
        }
    }
    dialog.show()
}

/**
 * Turns what a person types into something URLTest can dial, or null if it cannot.
 *
 * People type "youtube.com", so a missing scheme is assumed to be https rather than rejected.
 * A bare host with no dot ("localhost", or a half-typed name) is refused: URLTest would hang
 * until the timeout and report every proxy as dead, which reads as a bug in the app rather
 * than a typo.
 */
internal fun normalizeLatencyTarget(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) {
        text
    } else {
        "https://$text"
    }
    val host = runCatching { java.net.URI(withScheme).host }.getOrNull()
    if (host.isNullOrBlank() || !host.contains('.')) return null
    return withScheme
}
