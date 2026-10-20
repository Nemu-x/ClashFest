package com.github.kr328.clash.design

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import com.google.android.material.R as MaterialR
import com.github.kr328.clash.design.util.shouldAnimatePowerAmbient
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.MainDesign.Request
import com.github.kr328.clash.design.MainDesign.MainTab

/*
 * Power-button visuals for [MainDesign]: state-dependent colours/elevation, the ambient breath and
 * sweep animation driver, and the power card's attach/touch wiring.
 */

/** ~30fps gate for the ambient power animations (33ms between rendered frames). */
private const val AMBIENT_MIN_FRAME_NS = 33_000_000L

/** Active operator accent (validated + contrast-OK) or null. Used by applyPowerVisuals. */
private fun MainDesign.brandAccentColor(): Int? {
    val hex = brandHolder.manifest.accentColor?.takeIf { it.isNotBlank() } ?: return null
    val parsed = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() ?: return null
    val surface = context.resolveThemedColor(MaterialR.attr.colorSurface)
    return if (com.github.kr328.clash.common.branding.BrandValidation
            .hasMinContrast(parsed, surface)
    ) parsed else null
}

internal fun MainDesign.applyPowerVisuals() {
    val button = binding.mainPowerCard
    val running = clashRunningState
    val starting = tunnelStartingState && !running
    val (bgAttr, iconAttr, elevationDp) = when {
        running -> Triple(
            com.google.android.material.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorOnPrimary,
            10f,
        )
        starting -> Triple(
            com.google.android.material.R.attr.colorPrimaryContainer,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            8f,
        )
        else -> Triple(
            // Raised "block" surface (brightest container) reads as a floating element
            // against the ambient canvas — depth, not a flat Material fill. Redesign 1.0.
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            7f,
        )
    }
    // Operator brand accent applies ONLY in the running state — that's the
    // slot `colorPrimary` filled in the default theme. Idle / starting
    // states read default M3 surface attrs. Since we no longer run a
    // dynamic-color harmoniser (which used to derive ALL surface tones
    // from the brand seed and tint the off-state), surface attrs stay at
    // their built-in neutral M3 values automatically — no workaround needed.
    val accentBg = brandAccentColor() ?: context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    val bgColor = if (running) accentBg else context.resolveThemedColor(bgAttr)
    button.backgroundTintList = ColorStateList.valueOf(bgColor)
    button.iconTint = ColorStateList.valueOf(context.resolveThemedColor(iconAttr))
    button.setTextColor(context.resolveThemedColor(iconAttr))
    button.elevation = elevationDp * context.resources.displayMetrics.density
    // Lux bezel: a lit rim on the button edge — a lighter blend of the fill. Bright and
    // present when running (glowing orb), whisper-subtle when off (calm disc).
    val bezelColor = ColorUtils.blendARGB(bgColor, Color.WHITE, if (running) 0.36f else 0.10f)
    button.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
    button.setStrokeColor(ColorStateList.valueOf(bezelColor))
    // Dimensional sheen belongs to the CONNECTED state — off is a calm, near-flat obsidian
    // disc; on is a lit 3D orb. So the top-light is understated idle, full when running.
    binding.powerSheen.alpha = when {
        running -> 1.0f
        starting -> 0.6f
        else -> 0.22f
    }
    // Dome vignette (volumetric fill) + soft shimmer are CONNECTED-only.
    val overlayAlpha = if (running) 1.0f else 0.0f
    binding.powerDome.animate().alpha(overlayAlpha).setDuration(240L).start()
    binding.powerSweep.backgroundTintList = null
    binding.powerSweep.animate().alpha(overlayAlpha).setDuration(240L).start()
    // Live speed shows only while connected (redesign 1.0, #101).
    binding.mainSpeed.visibility = if (running) View.VISIBLE else View.GONE

    val targetAlpha = when {
        running -> 0.34f
        starting -> 0.24f
        else -> 0.12f
    }
    val targetScale = when {
        running -> 1.08f
        starting -> 1.04f
        else -> 0.96f
    }
    // Halo: when running, the breath loop owns the alpha channel — we
    // only ramp scale here. When idle/connecting, breath is dormant so
    // we drive alpha normally (ramp to targetAlpha).
    binding.powerHalo.animate().cancel()
    val haloAnim = binding.powerHalo.animate()
        .scaleX(targetScale)
        .scaleY(targetScale)
        .setDuration(260L)
    if (!running) {
        haloAnim.alpha(targetAlpha)
    }
    haloAnim.start()

    // Lumen single-glow: the outer ring is retired (kept in the view tree at alpha 0). The
    // halo is the ONE soft glow — when running it tints to the active accent for the
    // "lamp is on" feel; idle stays neutral.
    binding.powerRingOuter.animate().cancel()
    binding.powerRingOuter.alpha = 0f

    val glowTint = if (running) {
        accentBg
    } else {
        context.resolveThemedColor(com.google.android.material.R.attr.colorSurfaceVariant)
    }
    binding.powerHalo.backgroundTintList = ColorStateList.valueOf(glowTint)
    // The faint idle ambient ring (inner) reads neutral, never accent.
    binding.powerRingInner.backgroundTintList = ColorStateList.valueOf(
        context.resolveThemedColor(com.google.android.material.R.attr.colorOutline),
    )

    // Status pill: accent-tinted background + on-primary text when running,
    // neutral chip otherwise. Subtle but immediately readable as "active".
    val statusBgRes = if (running) R.drawable.bg_m3_status_chip else R.drawable.bg_m3_status_chip_neutral
    binding.mainStatusLabel.setBackgroundResource(statusBgRes)
    val statusTextColor = if (running) {
        context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
    } else {
        context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    }
    binding.mainStatusLabel.setTextColor(statusTextColor)

    updateBreathAnimator(running, starting)
}

/**
 * (Re)starts the power-button + concentric-ring breath loop when the
 * tunnel is running. Each ring uses an increasing [startDelay] so the
 * pulse visually ripples outward from the button rather than every
 * layer beating in lockstep. In idle / connecting we cancel everything
 * and pin properties back to their static values so nothing wobbles.
 *
 * Animators are recreated rather than mutated so each new state change
 * starts at a known frame instead of jumping from a mid-cycle value.
 */
internal fun MainDesign.updateBreathAnimator(requestedRunning: Boolean, starting: Boolean) {
    val running = shouldAnimatePowerAmbient(
        running = requestedRunning,
        attached = binding.mainPowerCard.isAttachedToWindow,
        activityVisible = hostLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true,
        homeVisible = currentTab == MainTab.Home,
        aboutOpen = clickGuard.isOpen(Request.OpenAbout),
        animationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled(),
    )
    // Skip rebuild when the state hasn't actually changed — keeps the
    // breath loop running smoothly through unrelated applyPowerVisuals
    // calls (brand refresh, mode change, etc).
    if (lastBreathRunning == running && powerBreathAnimator?.isRunning == true) {
        return
    }
    lastBreathRunning = running

    powerBreathAnimator?.cancel()
    powerHaloBreathAnimator?.cancel()
    powerRingInnerBreathAnimator?.cancel()
    powerRingOuterBreathAnimator?.cancel()
    powerSweepAnimator?.cancel()
    powerBreathAnimator = null
    powerHaloBreathAnimator = null
    powerRingInnerBreathAnimator = null
    powerRingOuterBreathAnimator = null
    powerSweepAnimator = null

    val button = binding.mainPowerCard
    val halo = binding.powerHalo
    val innerRing = binding.powerRingInner
    val outerRing = binding.powerRingOuter

    // Outer ring is retired in the single-glow design — always dark.
    outerRing.animate().cancel()
    outerRing.alpha = 0f

    if (!running) {
        // Idle / connecting: no ripple. A faint neutral ambient ring sits under the raised
        // button (Lumen); the halo glow is dim (its alpha is driven in applyPowerVisuals).
        button.scaleX = 1.0f
        button.scaleY = 1.0f
        innerRing.animate().cancel()
        innerRing.alpha = 0.18f
        return
    }

    // Running: ONE soft glow (Lumen). No ring ripple — the halo breathes slowly for a calm
    // "lamp is on" feel and the button gives a gentle scale. Rings stay dark. A longer period
    // (2.6s) reads premium/unhurried vs the old 2s ripple.
    val period = 2600L
    button.scaleX = 1.0f
    button.scaleY = 1.0f
    innerRing.animate().cancel()
    innerRing.alpha = 0.0f
    halo.alpha = 0.20f

    // One driver ticks the clock; the gated listener derives button scale (breath, 2.6s
    // REVERSE accel-decel), halo alpha (same, +120ms phase) and sweep rotation (4.6s linear)
    // from elapsed time — reproducing the three former animators without their per-vsync cost.
    val breath = AccelerateDecelerateInterpolator()
    val sweep = binding.powerSweep
    ambientStartNs = System.nanoTime()
    lastAmbientFrameNs = 0L
    powerBreathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = period
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            if (now - lastAmbientFrameNs < AMBIENT_MIN_FRAME_NS) return@addUpdateListener
            lastAmbientFrameNs = now
            val elapsedMs = (now - ambientStartNs) / 1_000_000L
            // REVERSE triangle over 2*period, accel-decel on each half — matches ofFloat REVERSE.
            fun breathe(ms: Long): Float {
                if (ms < 0) return 0f
                val cyc = ms % (2 * period)
                val tri = if (cyc < period) cyc.toFloat() / period else (2 * period - cyc).toFloat() / period
                return breath.getInterpolation(tri)
            }
            val s = 1.0f + 0.04f * breathe(elapsedMs)
            button.scaleX = s
            button.scaleY = s
            halo.alpha = 0.20f + 0.20f * breathe(elapsedMs - 120L)
            sweep.rotation = (elapsedMs % 4600L).toFloat() / 4600f * 360f
        }
        start()
    }
}

internal fun MainDesign.setupPowerCard() {
    val card = binding.mainPowerCard
    card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            hostLifecycle?.addObserver(powerLifecycleObserver)
            updateBreathAnimator(clashRunningState, tunnelStartingState)
        }

        override fun onViewDetachedFromWindow(view: View) {
            hostLifecycle?.removeObserver(powerLifecycleObserver)
            updateBreathAnimator(false, false)
            card.animate().cancel()
            binding.powerHalo.animate().cancel()
            binding.powerDome.animate().cancel()
            binding.powerSweep.animate().cancel()
        }
    })
    card.setOnClickListener {
        request(Request.ToggleStatus)
    }
    card.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                v.animate().cancel()
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                // Spring back with a soft overshoot — the connect tap feels physical.
                v.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator(2.4f))
                    .setDuration(340L).start()
            }
        }
        false
    }
}
