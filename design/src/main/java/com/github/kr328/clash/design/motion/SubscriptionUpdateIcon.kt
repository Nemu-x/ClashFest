package com.github.kr328.clash.design.motion

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.ProfilePageState
import com.github.kr328.clash.design.util.hostActivity
import com.google.android.material.button.MaterialButton
import java.util.UUID

fun bindSubscriptionUpdateButton(button: MaterialButton, state: ProfilePageState, uuid: UUID) {
    val updating = state.isUpdating(uuid)
    button.isEnabled = !updating
    button.setText(when {
        updating -> R.string.profiles_updating
        state.hasUpdateError(uuid) -> R.string.subscription_retry
        else -> R.string.update
    })
    bindSubscriptionUpdateIcon(button, updating, state.wasUpdatedSuccessfully(uuid))
}

fun bindSubscriptionUpdateIcon(view: View, updating: Boolean, successful: Boolean) {
    val motion = view.getTag(R.id.subscription_update_icon_motion) as? SubscriptionUpdateIcon
        ?: SubscriptionUpdateIcon(view).also { view.setTag(R.id.subscription_update_icon_motion, it) }
    motion.bind(updating, successful)
}

private class SubscriptionUpdateIcon(private val view: View) : View.OnAttachStateChangeListener,
    LifecycleEventObserver, ViewTreeObserver.OnPreDrawListener {
    private val lifecycle = (view.context.hostActivity() as? LifecycleOwner)?.lifecycle
    private var state = -1
    private var observingDraw = false
    private var playCompletion = false
    private val visibleArea = android.graphics.Rect()
    private fun drawable(): Drawable? = when (view) {
        is MaterialButton -> view.icon
        is ImageView -> view.drawable
        else -> null
    }

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    fun bind(updating: Boolean, successful: Boolean) {
        val next = if (updating) 1 else if (successful) 2 else 0
        if (state != next) {
            (drawable() as? Animatable)?.stop()
            state = next
            val resource = when (state) {
                1 -> R.drawable.avd_subscription_refresh
                2 -> R.drawable.avd_subscription_updated
                else -> R.drawable.ic_baseline_sync
            }
            val icon = AppCompatResources.getDrawable(view.context, resource)
            when (view) {
                is MaterialButton -> view.icon = icon
                is ImageView -> view.setImageDrawable(icon)
            }
            playCompletion = next == 2
        }
        val needsDraw = updating && view.isAttachedToWindow
        if (needsDraw != observingDraw) {
            if (needsDraw) view.viewTreeObserver.addOnPreDrawListener(this)
            else if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
            observingDraw = needsDraw
        }
        refresh()
    }

    private fun refresh() {
        val animation = drawable() as? Animatable ?: return
        val visible = view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
            view.getGlobalVisibleRect(visibleArea) && lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
        if (!visible || !motionEnabled()) { animation.stop(); return }
        if (state == 1 && !animation.isRunning) animation.start()
        else if (playCompletion) { playCompletion = false; animation.start() }
    }

    override fun onPreDraw(): Boolean { refresh(); return true }
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) = refresh()
    override fun onViewAttachedToWindow(view: View) { lifecycle?.addObserver(this); bind(state == 1, state == 2) }
    override fun onViewDetachedFromWindow(view: View) {
        lifecycle?.removeObserver(this)
        if (observingDraw && view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
        observingDraw = false
        (drawable() as? Animatable)?.stop()
    }
}
