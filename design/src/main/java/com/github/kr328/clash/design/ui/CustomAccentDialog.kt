package com.github.kr328.clash.design.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.resolveThemedColor

internal fun Context.showCustomAccentDialog(accent: Int?, onSelected: (Int) -> Unit) {
    val context = this
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    val hsv = FloatArray(3)
    Color.colorToHSV(accent ?: context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary), hsv)

    val svView = com.github.kr328.clash.design.view.SaturationValueView(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
        setColor(hsv[0], hsv[1], hsv[2])
    }
    val hueView = com.github.kr328.clash.design.view.HueBarView(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(26)).apply { topMargin = dp(16) }
        hue = hsv[0]
    }
    val previewBg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
    val preview = View(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44))
        background = previewBg
    }
    // Tap-to-edit hex field: dragging the square/bar rewrites it; typing a valid #RRGGBB drives the
    // square/bar back. A guard flag breaks the drag<->text feedback loop.
    val hexEdit = android.widget.EditText(context).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
        textSize = 18f
        setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
        setSingleLine()
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(android.text.InputFilter.LengthFilter(7))
    }

    var syncingFromViews = false
    fun currentColor(): Int =
        Color.HSVToColor(floatArrayOf(hueView.hue, svView.saturation, svView.value)) or (0xFF shl 24)
    fun refresh() {
        val c = currentColor()
        previewBg.setColor(c)
        syncingFromViews = true
        hexEdit.setText(String.format("#%06X", 0xFFFFFF and c))
        syncingFromViews = false
    }
    svView.onChanged = { _, _ -> refresh() }
    hueView.onChanged = { h -> svView.hue = h; refresh() }
    hexEdit.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (syncingFromViews) return
            val raw = s?.toString()?.trim().orEmpty()
            val normalized = if (raw.startsWith("#")) raw else "#$raw"
            if (normalized.length != 7) return
            val parsed = runCatching { Color.parseColor(normalized) }.getOrNull() ?: return
            val hsvOut = FloatArray(3)
            Color.colorToHSV(parsed, hsvOut)
            hueView.hue = hsvOut[0]
            svView.setColor(hsvOut[0], hsvOut[1], hsvOut[2])
            previewBg.setColor(parsed or (0xFF shl 24))
        }
    })
    refresh()

    val previewRow = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        addView(preview)
        addView(hexEdit)
    }
    val content = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(svView)
        addView(hueView)
        addView(previewRow)
    }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(R.string.theme_custom_accent_dialog_title)
        .setView(content)
        .setPositiveButton(android.R.string.ok) { _, _ -> onSelected(currentColor()) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
