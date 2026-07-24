package com.github.kr328.clash.util

import android.widget.LinearLayout
import com.github.kr328.clash.BaseActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.button.MaterialButton

/**
 * Preset picker for App Routing: one button per regional bypass preset,
 * sorted by installed-match count. Tapping a preset applies it additively;
 * dismiss by swipe/outside tap.
 */
fun BaseActivity<*>.showBypassPresetSheet(
    design: Design<*>,
    presets: List<Pair<BypassPreset, Int>>,
    onApply: (BypassPreset) -> Unit,
) {
    val dialog = AppBottomSheetDialog(design.context, fitContentHeight = true)
    val view = design.context.layoutInflater.inflate(R.layout.bottom_sheet_bypass_preset, null)
    dialog.setContentView(view)

    val list = view.findViewById<LinearLayout>(R.id.preset_list)
    presets.forEach { (preset, count) ->
        val button = design.context.layoutInflater
            .inflate(R.layout.item_bypass_preset_button, list, false) as MaterialButton
        button.text = getString(
            DesignR.string.bypass_preset_option,
            BypassPresets.displayTitle(this, preset),
            count,
        )
        button.setOnClickListener {
            dialog.dismiss()
            onApply(preset)
        }
        list.addView(button)
    }

    dialog.show()
}
