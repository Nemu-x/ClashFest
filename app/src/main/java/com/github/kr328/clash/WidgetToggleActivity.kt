package com.github.kr328.clash

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.log.LogRedaction
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.R as ServiceR
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer
import com.github.kr328.clash.service.widget.SpeedWidgetRenderer.State
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.widget.WidgetToggleGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class WidgetToggleActivity : ComponentActivity(), CoroutineScope by MainScope() {
    private var ownsToggle = false
    private var permissionPending = false
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        permissionPending = false
        perform {
            if (result.resultCode == RESULT_OK) {
                startSelectedProfile()
            } else {
                Toast.makeText(this@WidgetToggleActivity, ServiceR.string.widget_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        permissionPending = savedInstanceState?.getBoolean(PERMISSION_PENDING) == true
        if (intent.action != Intents.ACTION_WIDGET_TOGGLE ||
            !gate.tryBegin(SystemClock.elapsedRealtime(), restoring = permissionPending)
        ) {
            finish()
            return
        }
        ownsToggle = true
        if (permissionPending) return
        if (savedInstanceState != null) {
            perform { }
            return
        }
        perform {
            val status = snapshot()
            if (status.serviceRunning) {
                paint(State.Stopping)
                stopClashService()
                awaitState(running = false)
            } else {
                startSelectedProfile()
            }
        }
    }

    private fun perform(block: suspend () -> Unit) = launch {
        try {
            block()
        } catch (timeout: TimeoutCancellationException) {
            Toast.makeText(this@WidgetToggleActivity, ServiceR.string.widget_action_failed, Toast.LENGTH_SHORT).show()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Widget toggle failed: ${LogRedaction.throwableMessage(error)}")
            Toast.makeText(this@WidgetToggleActivity, ServiceR.string.widget_action_failed, Toast.LENGTH_SHORT).show()
        } finally {
            if (!permissionPending) {
                withContext(NonCancellable) {
                    try {
                        val status = snapshot()
                        paint(State.fromStatus(status.serviceRunning, status.currentProfile != null))
                    } finally {
                        finish()
                    }
                }
            }
        }
    }

    private suspend fun startSelectedProfile() {
        val active = withTimeout(5000L) { withProfile { queryActive() } }
        if (active == null || active.pending || !active.imported) {
            Toast.makeText(this, ServiceR.string.widget_choose_subscription, Toast.LENGTH_SHORT).show()
            startActivity(Intent(Intents.ACTION_OPEN_PROFILES).setClass(this, MainActivity::class.java))
            return
        }
        paint(State.Starting)
        val request = startClashService()
        if (request != null) {
            permissionPending = true
            permissionLauncher.launch(request)
            return
        }
        awaitState(running = true)
    }

    private suspend fun awaitState(running: Boolean) = withTimeout(20000L) {
        while (true) {
            val status = snapshot()
            if (if (running) status.serviceRunning && status.currentProfile != null else !status.serviceRunning) break
            delay(250L)
        }
    }

    private suspend fun snapshot() = withContext(Dispatchers.IO) {
        StatusClient(this@WidgetToggleActivity).statusSnapshot()
    }

    private suspend fun paint(state: State) = withContext(Dispatchers.IO) {
        SpeedWidgetRenderer.renderAll(this@WidgetToggleActivity, state)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PERMISSION_PENDING, permissionPending)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        cancel()
        if (ownsToggle) gate.finish()
        super.onDestroy()
    }

    private companion object {
        const val PERMISSION_PENDING = "permission_pending"
        val gate = WidgetToggleGate()
    }
}
