package com.github.kr328.clash.design.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.motion.animateExpressivePress
import com.github.kr328.clash.design.motion.motionEnabled
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.shouldAnimatePowerAmbient
import com.google.android.material.R as MaterialR

internal class PowerVisuals(
    private val context: Context,
    private val binding: DesignMainBinding,
    private val hostLifecycle: Lifecycle?,
    private val isRunning: () -> Boolean,
    private val isTunnelStarting: () -> Boolean,
    private val isHomeVisible: () -> Boolean,
    private val isAboutOpen: () -> Boolean,
) {
    private var previousRunning: Boolean? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> refreshMotion()
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stopMotion()
            else -> Unit
        }
    }

    fun install() {
        binding.mainPowerCard.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateExpressivePress(binding.mainPowerShell, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateExpressivePress(binding.mainPowerShell, false)
            }
            false
        }
        binding.mainPowerCard.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                hostLifecycle?.addObserver(lifecycleObserver)
                refreshMotion()
            }

            override fun onViewDetachedFromWindow(view: View) {
                hostLifecycle?.removeObserver(lifecycleObserver)
                stopMotion()
            }
        })
    }

    fun stopMotion() {
        binding.powerShape.allowMotion(false)
        binding.mainPowerShell.animate().cancel()
        binding.mainPowerShell.scaleX = 1f
        binding.mainPowerShell.scaleY = 1f
    }

    fun refreshMotion() {
        binding.powerShape.allowMotion(shouldAnimatePowerAmbient(
            attached = binding.mainPowerCard.isAttachedToWindow,
            activityVisible = hostLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true,
            homeVisible = isHomeVisible(),
            aboutOpen = isAboutOpen(),
            animationsEnabled = motionEnabled(),
        ))
    }

    fun applyVisuals() {
        val button = binding.mainPowerCard
        val running = isRunning()
        val starting = isTunnelStarting() && !running
        val animate = previousRunning != null && button.isAttachedToWindow && motionEnabled()
        button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        button.iconTint = null
        button.icon?.clearColorFilter()
        button.elevation = 0f
        button.strokeWidth = 0
        button.contentDescription = context.getString(if (running) R.string.main_power_hint_stop else R.string.main_power_hint_start)
        button.isActivated = running
        binding.mainSpeed.visibility = if (running) View.VISIBLE else View.GONE
        binding.mainStatusLabel.setTextColor(context.resolveThemedColor(MaterialR.attr.colorOnSurface))
        binding.powerShape.setState(running, starting, animate)
        previousRunning = running
        refreshMotion()
    }
}
