package com.github.kr328.clash.design

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.github.kr328.clash.design.ui.showCustomAccentDialog
import com.github.kr328.clash.design.branding.BrandThemeApplier
import com.github.kr328.clash.design.databinding.DesignThemeSettingsBinding
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.HomeBackgroundStyle
import com.github.kr328.clash.design.model.ThemePalette
import com.github.kr328.clash.design.model.ThemeFontWeight
import com.github.kr328.clash.design.model.ThemeTextScale
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.view.ThemePaletteView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.cancel

class ThemeSettingsDesign(
    context: Context,
    private val uiStore: UiStore,
    private val syncNightMode: () -> Unit,
) : Design<ThemeSettingsDesign.Request>(context) {
    enum class Request {
        /** Recreate themed screens, using MainActivity's existing soft refresh. */
        ReCreateOtherActivities,
        /** Full staggered recreate including Theme (font scale / reset need attachBaseContext). */
        ReCreateAllActivities,
    }

    private val binding = DesignThemeSettingsBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private val paletteCards = mutableMapOf<ThemePalette, MaterialCardView>()
    private val paletteColors = mutableMapOf<ThemePalette, IntArray>()
    private var customAccentCard: MaterialCardView? = null
    private var customAccentLabel: android.widget.TextView? = null
    private val recreateHandler = Handler(Looper.getMainLooper())
    private var pendingRecreateIncludesHost = false
    private val recreateRunnable = Runnable {
        val includeHost = pendingRecreateIncludesHost
        pendingRecreateIncludesHost = false
        requests.trySend(
            if (includeHost) Request.ReCreateAllActivities
            else Request.ReCreateOtherActivities,
        )
    }

    init {
        binding.surface = surface
        binding.header.screenTitle.text = context.getString(R.string.theme_settings)

        setupThemeMode()
        setupDynamicColors()
        setupPalettes()
        setupTrueBlack()
        setupTextScale()
        setupFontWeight()
        setupHomeBackground()
        setupReset()
    }

    fun dispose() {
        recreateHandler.removeCallbacks(recreateRunnable)
        cancel()
    }

    private fun recreateAll(full: Boolean = false) {
        pendingRecreateIncludesHost = pendingRecreateIncludesHost || full
        recreateHandler.removeCallbacks(recreateRunnable)
        recreateHandler.postDelayed(recreateRunnable, 240L)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun setupThemeMode() {
        binding.themeModeGroup.check(
            when (uiStore.darkMode) {
                DarkMode.Auto -> R.id.theme_mode_auto
                DarkMode.ForceLight -> R.id.theme_mode_light
                DarkMode.ForceDark -> R.id.theme_mode_dark
            }
        )
        binding.themeModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            uiStore.darkMode = when (checkedId) {
                R.id.theme_mode_light -> DarkMode.ForceLight
                R.id.theme_mode_dark -> DarkMode.ForceDark
                else -> DarkMode.Auto
            }
            // Day/night change: drive AppCompat night mode -> the real Configuration flips ->
            // AppCompat recreates every activity cleanly with values-night resolved correctly.
            syncNightMode()
        }
    }

    private fun setupDynamicColors() {
        // Material You pulls colors from the wallpaper — only meaningful on Android 12+ where dynamic
        // color exists. On older devices the toggle is a no-op, so hide it entirely rather than offer
        // a control that does nothing.
        if (!com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) {
            binding.dynamicColorSwitch.visibility = android.view.View.GONE
            return
        }
        binding.dynamicColorSwitch.isChecked = uiStore.dynamicColors
        binding.dynamicColorSwitch.setOnCheckedChangeListener { _, checked ->
            uiStore.dynamicColors = checked
            if (checked) {
                uiStore.customAccent = null
                if (slothActive) {
                    uiStore.homeBackgroundStyle = HomeBackgroundStyle.Preview
                    binding.homeBackgroundGroup.check(R.id.home_background_preview)
                }
            }
            updatePaletteEnabled()
            updatePaletteSelection()
            recreateAll()
        }
    }

    private fun setupPalettes() {
        ThemePalette.values().forEach { palette ->
            paletteColors[palette] = previewColors(palette)
            val card = createPaletteCard(palette)
            paletteCards[palette] = card
            binding.themePaletteGrid.addView(card)
        }
        // Append a "+" custom-accent tile as the last cell of the palette grid.
        customAccentCard = createCustomAccentCard().also { binding.themePaletteGrid.addView(it) }
        updatePaletteSelection()
        updatePaletteEnabled()
    }

    private fun createPaletteCard(palette: ThemePalette): MaterialCardView {
        val margin = dp(6)
        val size = dp(104)
        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = size
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(margin, margin, margin, margin)
        }

        return MaterialCardView(context).apply {
            layoutParams = params
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setCardBackgroundColor(paletteColors.getValue(palette)[2])
            strokeColor = context.resolvePaletteStroke(palette, palette == uiStore.themePalette)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(palette.labelRes)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                addView(ThemePaletteView(context).apply {
                    colors = paletteColors.getValue(palette)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })
                addView(TextView(context).apply {
                    setText(palette.labelRes)
                    androidx.core.widget.TextViewCompat.setTextAppearance(this, R.style.TextAppearance_App_LabelMedium)
                    setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            })

            setOnClickListener {
                if (uiStore.dynamicColors) {
                    uiStore.dynamicColors = false
                    binding.dynamicColorSwitch.isChecked = false
                }
                // Picking a palette exits the Sloth skin so the palette applies.
                if (slothActive) {
                    uiStore.homeBackgroundStyle = HomeBackgroundStyle.Preview
                    binding.homeBackgroundGroup.check(R.id.home_background_preview)
                }
                // Picking a preset clears any custom accent (they're mutually exclusive).
                uiStore.customAccent = null
                uiStore.themePalette = palette
                updatePaletteEnabled()
                updatePaletteSelection()
                recreateAll()
            }
        }
    }

    private fun updatePaletteSelection() {
        // A custom accent overrides presets, so no preset reads as selected while one is set.
        val presetActive = !customAccentActive && !slothActive &&
            !wallpaperActive && !brandOwnsColors
        paletteCards.forEach { (palette, card) ->
            val checked = presetActive && palette == uiStore.themePalette
            card.strokeWidth = dp(if (checked) 2 else 1)
            card.strokeColor = context.resolvePaletteStroke(palette, checked)
            card.isSelected = checked
            ((card.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? ThemePaletteView)?.checked = checked
        }
        updateCustomAccentCard()
    }

    private fun updatePaletteEnabled() {
        // Sloth skin and dynamic colors both own the palette, so dim the grid
        // to signal it has no effect while either is active.
        val overridden = wallpaperActive || slothActive || brandOwnsColors
        paletteCards.values.forEach {
            it.isEnabled = true
            it.alpha = if (overridden) 0.8f else 1.0f
        }
        binding.paletteStatus.text = when {
            brandOwnsColors -> context.getString(R.string.theme_colors_brand)
            slothActive -> context.getString(R.string.theme_colors_skin)
            customAccentActive -> context.getString(R.string.theme_colors_custom)
            wallpaperActive -> context.getString(R.string.theme_colors_wallpaper)
            else -> context.getString(R.string.theme_colors_preset, context.getString(uiStore.themePalette.labelRes))
        }
        binding.trueBlackSwitch.isEnabled = !slothActive
        for (i in 0 until binding.fontWeightGroup.childCount) {
            binding.fontWeightGroup.getChildAt(i).isEnabled = !slothActive
        }
    }

    private val wallpaperActive: Boolean
        get() = uiStore.dynamicColors && uiStore.customAccent == null &&
            com.google.android.material.color.DynamicColors.isDynamicColorAvailable()

    private val customAccentActive: Boolean
        get() = uiStore.customAccent != null &&
            com.google.android.material.color.DynamicColors.isDynamicColorAvailable()

    private val brandOwnsColors: Boolean
        get() {
            val uuid = com.github.kr328.clash.service.store.ServiceStore(context).activeProfile
            val brand = com.github.kr328.clash.service.branding.BrandStore(context)
            return uuid != null && brand.isActiveFor(uuid) &&
                BrandThemeApplier.applicableAccent(brand.manifestFor(uuid).accentColor).isNotEmpty()
        }

    // --- Custom accent: a "+" tile appended to the palette grid. Tapping it lets the user pick any
    //     color, which seeds the full M3 palette via the same harmoniser as the operator brand.
    //     Overrides preset palettes / Material You; the operator brand still wins. ---
    private fun createCustomAccentCard(): MaterialCardView {
        val margin = dp(6)
        val size = dp(104)
        return MaterialCardView(context).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = size
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(margin, margin, margin, margin)
            }
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.theme_custom_accent_pick)
            visibility = if (com.google.android.material.color.DynamicColors.isDynamicColorAvailable()) View.VISIBLE else View.GONE
            addView(
                android.widget.TextView(context).apply {
                    customAccentLabel = this
                    text = "+"
                    textSize = 26f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            setOnClickListener { context.showCustomAccentDialog(uiStore.customAccent, ::selectAccent) }
        }
    }

    private fun updateCustomAccentCard() {
        val card = customAccentCard ?: return
        val accent = uiStore.customAccent
        if (accent != null && !slothActive && !brandOwnsColors && !wallpaperActive) {
            // Filled with the chosen color + a selection ring; a check reads on top for contrast.
            card.setCardBackgroundColor(accent)
            card.strokeWidth = dp(2)
            card.strokeColor = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
            customAccentLabel?.apply {
                text = "✓"
                setTextColor(onColorFor(accent))
            }
            card.isSelected = true
            card.contentDescription = context.getString(R.string.theme_custom_accent_saved, String.format("#%06X", accent and 0xFFFFFF))
        } else {
            card.setCardBackgroundColor(context.resolveThemedColor(com.google.android.material.R.attr.colorSurfaceContainerHighest))
            card.strokeWidth = dp(1)
            card.strokeColor = context.resolveThemedColor(com.google.android.material.R.attr.colorOutline)
            customAccentLabel?.apply {
                text = "+"
                setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            card.isSelected = false
            card.contentDescription = context.getString(R.string.theme_custom_accent_pick)
        }
    }

    /** Black or white, whichever contrasts better on [bg]. */
    private fun onColorFor(bg: Int): Int {
        return if (ColorUtils.calculateContrast(Color.BLACK, bg) >= ColorUtils.calculateContrast(Color.WHITE, bg)) Color.BLACK else Color.WHITE
    }

    private fun selectAccent(color: Int) {
        // A custom accent owns the palette, so leave Material You / Sloth (they'd override it).
        if (uiStore.dynamicColors) {
            uiStore.dynamicColors = false
            binding.dynamicColorSwitch.isChecked = false
        }
        if (slothActive) {
            uiStore.homeBackgroundStyle = HomeBackgroundStyle.Preview
            binding.homeBackgroundGroup.check(R.id.home_background_preview)
        }
        uiStore.customAccent = color
        updatePaletteSelection()
        updatePaletteEnabled()
        recreateAll()
    }

    private fun setupTrueBlack() {
        binding.trueBlackSwitch.isChecked = uiStore.trueBlack
        binding.trueBlackSwitch.setOnCheckedChangeListener { _, checked ->
            uiStore.trueBlack = checked
            recreateAll()
        }
    }

    private fun setupTextScale() {
        binding.textScaleGroup.check(
            when (uiStore.themeTextScale) {
                ThemeTextScale.Small -> R.id.text_scale_small
                ThemeTextScale.Default -> R.id.text_scale_default
                ThemeTextScale.Large -> R.id.text_scale_large
                ThemeTextScale.ExtraLarge -> R.id.text_scale_extra
            }
        )
        binding.textScaleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            uiStore.themeTextScale = when (checkedId) {
                R.id.text_scale_small -> ThemeTextScale.Small
                R.id.text_scale_large -> ThemeTextScale.Large
                R.id.text_scale_extra -> ThemeTextScale.ExtraLarge
                else -> ThemeTextScale.Default
            }
            recreateAll(full = true)
        }
    }

    private fun setupFontWeight() {
        binding.fontWeightGroup.check(
            when (uiStore.themeFontWeight) {
                ThemeFontWeight.Default -> R.id.font_weight_default
                ThemeFontWeight.Medium -> R.id.font_weight_medium
                ThemeFontWeight.SemiBold -> R.id.font_weight_semibold
                ThemeFontWeight.Bold -> R.id.font_weight_bold
            }
        )
        binding.fontWeightGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            uiStore.themeFontWeight = when (checkedId) {
                R.id.font_weight_medium -> ThemeFontWeight.Medium
                R.id.font_weight_semibold -> ThemeFontWeight.SemiBold
                R.id.font_weight_bold -> ThemeFontWeight.Bold
                else -> ThemeFontWeight.Default
            }
            recreateAll()
        }
    }

    private fun setupHomeBackground() {
        binding.homeBackgroundGroup.check(
            when (uiStore.homeBackgroundStyle) {
                HomeBackgroundStyle.MaterialYou -> R.id.home_background_material
                HomeBackgroundStyle.Preview -> R.id.home_background_preview
                HomeBackgroundStyle.Sloth -> R.id.home_background_sloth
            }
        )
        binding.homeBackgroundGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            uiStore.homeBackgroundStyle = when (checkedId) {
                R.id.home_background_material -> HomeBackgroundStyle.MaterialYou
                R.id.home_background_sloth -> HomeBackgroundStyle.Sloth
                else -> HomeBackgroundStyle.Preview
            }
            updatePaletteEnabled()
            updatePaletteSelection()
            recreateAll()
        }
    }

    private val slothActive: Boolean
        get() = uiStore.homeBackgroundStyle == HomeBackgroundStyle.Sloth

    private fun setupReset() {
        binding.resetThemeButton.setOnClickListener {
            uiStore.darkMode = DarkMode.Auto
            uiStore.dynamicColors = false
            uiStore.themePalette = ThemePalette.Clash
            uiStore.customAccent = null
            uiStore.trueBlack = false
            uiStore.themeTextScale = ThemeTextScale.Default
            uiStore.themeFontWeight = ThemeFontWeight.Default
            uiStore.homeBackgroundStyle = HomeBackgroundStyle.Preview
            // Reset also changes day/night (-> Auto), so sync night mode; recreateAll re-themes the
            // rest and re-runs attachBaseContext for the text-scale change.
            syncNightMode()
            recreateAll(full = true)
        }
    }

    private val ThemePalette.labelRes: Int
        get() = when (this) {
            ThemePalette.Clash -> R.string.theme_palette_clash
            ThemePalette.Blue -> R.string.theme_palette_blue
            ThemePalette.Violet -> R.string.theme_palette_violet
            ThemePalette.Rose -> R.string.theme_palette_rose
            ThemePalette.Amber -> R.string.theme_palette_amber
            ThemePalette.Mint -> R.string.theme_palette_mint
            ThemePalette.Graphite -> R.string.theme_palette_graphite
        }

    private fun previewColors(palette: ThemePalette): IntArray {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val preview = ContextThemeWrapper(context, if (night) R.style.AppThemeDark else R.style.AppThemeLight)
        BrandThemeApplier.paletteOverlay(palette, night)?.let { preview.theme.applyStyle(it, true) }
        if (night && uiStore.trueBlack) preview.theme.applyStyle(R.style.ThemeOverlay_ClashFest_TrueBlack, true)
        return intArrayOf(preview.resolveThemedColor(com.google.android.material.R.attr.colorPrimary),
            preview.resolveThemedColor(com.google.android.material.R.attr.colorPrimaryContainer),
            preview.resolveThemedColor(com.google.android.material.R.attr.colorSurfaceContainerLow),
            preview.resolveThemedColor(com.google.android.material.R.attr.colorSecondaryContainer))
    }

    private fun Context.resolvePaletteStroke(palette: ThemePalette, checked: Boolean): Int {
        return if (checked) paletteColors.getValue(palette)[0]
            else resolveThemedColor(com.google.android.material.R.attr.colorOutlineVariant)
    }
}
