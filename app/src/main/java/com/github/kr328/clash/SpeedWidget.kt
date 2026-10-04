package com.github.kr328.clash

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.net.VpnService
import android.widget.Toast
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.log.LogRedaction
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.Service
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.R as ServiceR
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer.State
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.widget.WidgetToggleAction
import com.github.kr328.clash.widget.WidgetToggleGate
import com.github.kr328.clash.widget.widgetToggleAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SpeedWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intents.ACTION_WIDGET_TOGGLE -> runAsync { toggle(context.applicationContext) }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val manager = AppWidgetManager.getInstance(context)
                onUpdate(context, manager, manager.getAppWidgetIds(SpeedWidgetRenderer.provider(context)))
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runAsync {
            withContext(Dispatchers.IO) {
                val state = currentState(context)
                ids.forEach { SpeedWidgetRenderer.renderId(context, manager, it, state) }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle,
    ) {
        onUpdate(context, manager, intArrayOf(id))
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        val scope = MainScope()
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("Widget update failed: ${LogRedaction.throwableMessage(error)}")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun toggle(context: Context) {
        val gate = WidgetToggleGate.shared
        if (!gate.tryBegin(SystemClock.elapsedRealtime())) return
        try {
            withTimeout(25_000L) {
                val status = snapshot(context)
                val ready = if (status.serviceRunning) true else selectedProfileReady()
                when (widgetToggleAction(status.serviceRunning, ready, permissionRequired(context))) {
                    WidgetToggleAction.Stop -> {
                        paint(context, State.Stopping)
                        context.stopClashService()
                        awaitState(context, running = false)
                    }
                    WidgetToggleAction.Start -> {
                        paint(context, State.Starting)
                        if (context.startClashService() != null) {
                            requestPermission(context)
                        } else {
                            awaitState(context, running = true)
                        }
                    }
                    WidgetToggleAction.ChooseProfile -> toast(context, ServiceR.string.widget_choose_subscription)
                    WidgetToggleAction.GrantPermission -> requestPermission(context)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            toast(context, ServiceR.string.widget_action_failed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Widget toggle failed: ${LogRedaction.throwableMessage(error)}")
            toast(context, ServiceR.string.widget_action_failed)
        } finally {
            try {
                withContext(NonCancellable) {
                    withTimeout(1500L) { paint(context, withContext(Dispatchers.IO) { currentState(context) }) }
                }
            } finally {
                gate.finish()
            }
        }
    }

    private suspend fun selectedProfileReady(): Boolean {
        // The app's shared remote connection is unbound while no activity is visible.
        val service = Service(Global.application) { Log.w("Widget profile service disconnected") }
        try {
            service.bind()
            return withTimeout(5000L) {
                val remote = service.remote.get(5000L)
                withContext(Dispatchers.IO) {
                    val profile = remote.profile().queryActive()
                    profile != null && profile.imported && !profile.pending
                }
            }
        } finally {
            service.unbind()
        }
    }

    private fun permissionRequired(context: Context): Boolean =
        UiStore(context).enableVpn && VpnService.prepare(context) != null

    private fun currentState(context: Context): State {
        val status = StatusClient(context).statusSnapshot()
        return State.fromStatus(status.serviceRunning, status.currentProfile != null,
            permissionRequired = !status.serviceRunning && permissionRequired(context))
    }

    private suspend fun requestPermission(context: Context) {
        paint(context, State.PermissionRequired)
        toast(context, ServiceR.string.widget_permission_required)
    }

    private suspend fun awaitState(context: Context, running: Boolean) = withTimeout(18_000L) {
        while (true) {
            val status = snapshot(context)
            if (if (running) status.serviceRunning && status.currentProfile != null else !status.serviceRunning) break
            delay(250L)
        }
    }

    private suspend fun snapshot(context: Context) = withContext(Dispatchers.IO) {
        StatusClient(context).statusSnapshot()
    }

    private suspend fun paint(context: Context, state: State) = withContext(Dispatchers.IO) {
        SpeedWidgetRenderer.renderAll(context, state)
    }

    private fun toast(context: Context, message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
