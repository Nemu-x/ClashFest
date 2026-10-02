package com.github.kr328.clash

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer.State

class SpeedWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(SpeedWidgetRenderer.provider(context)))
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val status = StatusClient(context).statusSnapshot()
        val state = State.fromStatus(status.serviceRunning, status.currentProfile != null)
        ids.forEach { SpeedWidgetRenderer.renderId(context, manager, it, state) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        onUpdate(context, manager, intArrayOf(id))
    }
}
