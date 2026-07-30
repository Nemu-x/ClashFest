package com.github.kr328.clash.design.model

/**
 * User-selected weight for the app's UI font (#195 — "the current font is too thin for me").
 *
 * All three bundled faces are variable fonts, so a heavier weight costs no extra assets: the
 * font-family resources behind [Medium]/[SemiBold]/[Bold] point at the same `.ttf` and only differ
 * in `fontVariationSettings`. [Default] applies no overlay at all, which keeps the theme exactly as
 * it ships for everyone who never touches this.
 *
 * `fontVariationSettings` needs API 26. Below that the resource still resolves — the attribute is
 * ignored — so old devices render the regular weight instead of crashing. The setting simply has no
 * visible effect there.
 *
 * The SlothClash skin is deliberately excluded (it owns its own look, same way it bypasses palette,
 * dynamic color and true-black), so this only ever overrides the Manrope-based default themes.
 */
enum class ThemeFontWeight(val weight: Int) {
    Default(400),
    Medium(500),
    SemiBold(600),
    Bold(700),
}
