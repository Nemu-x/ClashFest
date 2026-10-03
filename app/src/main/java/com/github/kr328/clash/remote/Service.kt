package com.github.kr328.clash.remote

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.RemoteServiceUnavailableException
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.unbindServiceSilent
import java.util.concurrent.TimeUnit

class Service(private val context: Application, val crashed: () -> Unit) {
    val remote = Resource<IRemoteService>()
    private var bindingRequested = false

    private val connection = object : ServiceConnection {
        private var lastCrashed: Long = -1

        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            if (!bindingRequested) return
            remote.set(service.unwrap(IRemoteService::class))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (!bindingRequested) return
            remote.set(null)

            if (System.currentTimeMillis() - lastCrashed < TOGGLE_CRASHED_INTERVAL) {
                unbind()

                crashed()
            }

            lastCrashed = System.currentTimeMillis()
            Log.w("RemoteService killed or crashed")
        }

        override fun onNullBinding(name: ComponentName?) {
            if (!bindingRequested) return
            unbind()
            remote.fail(RemoteServiceUnavailableException())
        }

        override fun onBindingDied(name: ComponentName?) {
            if (!bindingRequested) return
            unbind()
            bind()
        }
    }

    fun bind() {
        if (bindingRequested) return
        remote.set(null)
        bindingRequested = true
        try {
            if (!context.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)) {
                unbind()
                remote.fail(RemoteServiceUnavailableException())
            }
        } catch (e: Exception) {
            unbind()
            remote.fail(RemoteServiceUnavailableException(e))
        }
    }

    fun unbind() {
        bindingRequested = false
        context.unbindServiceSilent(connection)

        remote.set(null)
    }

    companion object {
        private val TOGGLE_CRASHED_INTERVAL = TimeUnit.SECONDS.toMillis(10)
    }
}
