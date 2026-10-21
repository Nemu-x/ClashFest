package com.github.kr328.clash.design.dialog

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.TvFocusHighlight
import com.github.kr328.clash.design.util.resolveThemedResourceId
import com.google.android.material.color.MaterialColors

/**
 * Full-height panel that slides in from the right edge: the TV presentation of content that is a
 * bottom sheet on the phone (a bottom sheet on a 540dp-tall TV shows only a handful of rows). Uses
 * the same dialog theme and surface colour as [AppBottomSheetDialog], with the inner (start) edge
 * rounded. BACK or a click outside closes it.
 */
class TvSidePanelDialog(
    context: Context,
    private val widthFraction: Float = 0.5f,
) : AppCompatDialog(
    context,
    context.resolveThemedResourceId(com.google.android.material.R.attr.bottomSheetDialogTheme),
) {
    private val hostAccent: Int = MaterialColors.getColor(
        context, com.google.android.material.R.attr.colorPrimary, Color.TRANSPARENT,
    )

    init {
        // Same as BottomSheetDialog: the sheet dialog theme keeps a title unless it is dropped here.
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isTrueBlack = night && UiStore(context).trueBlack
        if (isTrueBlack) {
            getContext().theme.applyStyle(R.style.ThemeOverlay_ClashFest_TrueBlack, true)
        }

        super.onCreate(savedInstanceState)
        setCancelable(true)
        setCanceledOnTouchOutside(true)

        val surface = if (isTrueBlack) {
            ContextCompat.getColor(context, R.color.theme_true_black_bg)
        } else {
            MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorSurface, Color.BLACK)
        }
        val radius = 28f * context.resources.displayMetrics.density

        window!!.apply {
            isAllowForceDarkCompat = false
            setGravity(Gravity.END or Gravity.FILL_VERTICAL)
            setLayout(
                (context.resources.displayMetrics.widthPixels * widthFraction).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(surface)
                    // TL, TR, BR, BL: round only the edge that faces the screen.
                    cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                    if (!isTrueBlack && hostAccent != Color.TRANSPARENT) {
                        setStroke(
                            (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                            ColorUtils.setAlphaComponent(hostAccent, 0x33),
                        )
                    }
                },
            )
            setWindowAnimations(R.style.TvSidePanelAnimation)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
            // A search field in the panel must not pop the keyboard just because it got focus.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            TvFocusHighlight.install(this)
        }
    }
}
