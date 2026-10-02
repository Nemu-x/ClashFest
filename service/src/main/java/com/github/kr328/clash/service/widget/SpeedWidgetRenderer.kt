package com.github.kr328.clash.service.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.R

object SpeedWidgetRenderer {
    private const val PROVIDER = "com.github.kr328.clash.SpeedWidget"
    private const val TOGGLE_ACTIVITY = "com.github.kr328.clash.WidgetToggleActivity"

    enum class State(val child: Int, val description: Int, val busy: Boolean = false) {
        Off(0, R.string.widget_state_off),
        Starting(1, R.string.widget_state_starting, true),
        On(2, R.string.widget_state_on),
        Stopping(3, R.string.widget_state_stopping, true);

        companion object {
            fun fromStatus(serviceRunning: Boolean, profileLoaded: Boolean): State = when {
                !serviceRunning -> Off
                !profileLoaded -> Starting
                else -> On
            }
        }
    }

    fun provider(context: Context) = ComponentName(context.packageName, PROVIDER)

    fun renderAll(context: Context, state: State) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = runCatching { manager.getAppWidgetIds(provider(context)) }.getOrDefault(intArrayOf())
        ids.forEach { renderId(context, manager, it, state) }
    }

    fun renderId(context: Context, manager: AppWidgetManager, id: Int, state: State) {
        val views = RemoteViews(context.packageName, R.layout.widget_speed_compact).apply {
            setDisplayedChild(R.id.widget_root, state.child)
            setContentDescription(R.id.widget_root, context.getString(state.description))
            setBoolean(R.id.widget_root, "setEnabled", !state.busy)
            setOnClickPendingIntent(R.id.widget_root, if (state.busy) null else togglePendingIntent(context))
        }
        runCatching { manager.updateAppWidget(id, views) }.onFailure {
            Log.w("Unable to refresh VPN widget")
        }
    }

    private fun togglePendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(Intents.ACTION_WIDGET_TOGGLE)
            .setComponent(ComponentName(context.packageName, TOGGLE_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
    )
}
