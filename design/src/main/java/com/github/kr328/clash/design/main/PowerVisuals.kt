package com.github.kr328.clash.design.main

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.branding.BrandHolder
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.shouldAnimatePowerAmbient
import com.google.android.material.R as MaterialR

/**
 * The connect button on the Home hero: static colours per state, the icon cross-fade and the
 * ambient breath / sweep loop that plays only while the tunnel runs. State lives here; the host
 * only says whether the tunnel is running or starting.
 */
internal class PowerVisuals(
    private val context: Context,
    private val binding: DesignMainBinding,
    private val hostLifecycle: Lifecycle?,
    private val brandHolder: () -> BrandHolder,
    private val isRunning: () -> Boolean,
    private val isTunnelStarting: () -> Boolean,
    private val isHomeVisible: () -> Boolean,
    private val isAboutOpen: () -> Boolean,
) {
    /**
     * Breath loop for the power button + concentric rings — slow infinite
     * "ripple outwards" pulse that plays **only** while the tunnel is
     * running. Each layer (button → halo → inner ring → outer ring) shares
     * the same 2s period but starts with an increasing [startDelay], so the
     * inhale visually radiates from the button outward instead of all four
     * layers pulsing in lockstep. Idle and connecting states pin every
     * animated property back to its static value — no motion distraction.
     *
     * Held as separate animators so we can cancel them all in one place.
     */
    private var powerBreathAnimator: ValueAnimator? = null
    private var powerHaloBreathAnimator: ValueAnimator? = null
    private var powerRingInnerBreathAnimator: ValueAnimator? = null
    private var powerRingOuterBreathAnimator: ValueAnimator? = null
    private var powerSweepAnimator: ValueAnimator? = null
    private var powerIconAnimator: ValueAnimator? = null
    private var powerIconProgress = 0f
    private var powerIconRunning: Boolean? = null

    /**
     * The three ambient power effects (button breath, halo breath, conic sweep) are driven by a
     * SINGLE animator that ticks the clock; each vsync we derive all three values from elapsed
     * time. This replaced three separate INFINITE ValueAnimators — a method trace showed their
     * per-vsync machinery (Keyframe/clampFraction ×3) dominating the main thread on the connected
     * dashboard. One driver = one machinery pass, and all view writes land in a single frame gate
     * (~30fps) so the redraw fires once, not three staggered times. Visual output is identical:
     * the formulas reproduce each animator's period, interpolator and phase exactly.
     */
    private var ambientStartNs = 0L
    private var lastAmbientFrameNs = 0L

    /**
     * Tracks the running flag the breath animators were last spun up for, so
     * repeated [applyVisuals] calls (brand updates, profile refresh,
     * mode toggles) don't restart the breath mid-cycle and visibly blip the
     * rings. Only a real state transition (running ↔ not-running) tears down
     * and rebuilds the animators.
     */
    private var lastBreathRunning: Boolean? = null

    private val powerLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> refreshBreath()
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> updateBreathAnimator(false, false)
            else -> Unit
        }
    }

    /** Wires the attach/detach lifecycle and the press feedback onto the button. */
    fun install() {
        val card = binding.mainPowerCard
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                hostLifecycle?.addObserver(powerLifecycleObserver)
                refreshBreath()
            }

            override fun onViewDetachedFromWindow(view: View) {
                hostLifecycle?.removeObserver(powerLifecycleObserver)
                updateBreathAnimator(false, false)
                card.animate().cancel()
                binding.powerHalo.animate().cancel()
                binding.powerDome.animate().cancel()
                binding.powerSweep.animate().cancel()
                powerIconAnimator?.end()
                powerIconAnimator = null
            }
        })
        card.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.animate().cancel()
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).start()
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

    /** Active operator accent (validated + contrast-OK) or null. Used by applyVisuals. */
    private fun brandAccentColor(): Int? {
        val hex = brandHolder().manifest.accentColor?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() ?: return null
        val surface = context.resolveThemedColor(MaterialR.attr.colorSurface)
        return if (com.github.kr328.clash.common.branding.BrandValidation
                .hasMinContrast(parsed, surface)
        ) parsed else null
    }

    fun refreshBreath() {
        updateBreathAnimator(isRunning(), isTunnelStarting())
    }

    fun applyVisuals() {
        val button = binding.mainPowerCard
        val running = isRunning()
        val starting = isTunnelStarting() && !running
        val elevationDp = when {
            running -> 10f
            starting -> 8f
            else -> 7f
        }
        val accentBg = brandAccentColor() ?: context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val bgColor = context.resolveThemedColor(MaterialR.attr.colorSurfaceContainerHighest)
        button.backgroundTintList = ColorStateList.valueOf(bgColor)
        button.elevation = elevationDp * context.resources.displayMetrics.density
        button.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
        animatePowerIcon(running, bgColor, accentBg)
        binding.powerSheen.alpha = when {
            running -> 0.35f
            starting -> 0.25f
            else -> 0.22f
        }
        val overlayAlpha = if (running) 0.25f else 0.0f
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

    private fun animatePowerIcon(running: Boolean, background: Int, accent: Int) {
        val button = binding.mainPowerCard
        val icon = button.icon ?: return
        button.iconTint = null
        val gray = ContextCompat.getColor(context, R.color.mikan_power_icon_idle)
        val idleRim = ColorUtils.blendARGB(background, Color.WHITE, 0.10f)
        fun render(progress: Float) {
            powerIconProgress = progress
            val grayWeight = 1f - progress
            // Blend original artwork colors with gray, preserving transparency.
            icon.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                progress, 0f, 0f, 0f, Color.red(gray) * grayWeight,
                0f, progress, 0f, 0f, Color.green(gray) * grayWeight,
                0f, 0f, progress, 0f, Color.blue(gray) * grayWeight,
                0f, 0f, 0f, 1f, 0f,
            )))
            button.setStrokeColor(ColorStateList.valueOf(ColorUtils.blendARGB(idleRim, accent, progress)))
        }
        val previous = powerIconRunning
        powerIconRunning = running
        val target = if (running) 1f else 0f
        if (previous == null || !button.isAttachedToWindow ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())
        ) {
            powerIconAnimator?.cancel()
            powerIconAnimator = null
            render(target)
        } else if (previous != running) {
            powerIconAnimator?.cancel()
            powerIconAnimator = ValueAnimator.ofFloat(powerIconProgress, target).apply {
                duration = 560L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { render(it.animatedValue as Float) }
                start()
            }
        } else {
            render(powerIconProgress)
        }
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
    fun updateBreathAnimator(requestedRunning: Boolean, starting: Boolean) {
        val running = shouldAnimatePowerAmbient(
            running = requestedRunning,
            attached = binding.mainPowerCard.isAttachedToWindow,
            activityVisible = hostLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true,
            homeVisible = isHomeVisible(),
            aboutOpen = isAboutOpen(),
            animationsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled(),
        )
        // Skip rebuild when the state hasn't actually changed — keeps the
        // breath loop running smoothly through unrelated applyVisuals
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
            // button (Lumen); the halo glow is dim (its alpha is driven in applyVisuals).
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

    private companion object {
        /** ~30fps gate for the ambient power animations (33ms between rendered frames). */
        private const val AMBIENT_MIN_FRAME_NS = 33_000_000L
    }
}
