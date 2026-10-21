package com.github.kr328.clash.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.lastSubscriptionFetchAt
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class ProfileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                Global.launch {
                    reset()

                    val service = Intent(Intents.ACTION_PROFILE_SCHEDULE_UPDATES)
                        .setComponent(ProfileWorker::class.componentName)

                    context.startForegroundServiceCompat(service)
                }
            }
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> {
                val redirect = intent.setComponent(ProfileWorker::class.componentName)

                context.startForegroundServiceCompat(redirect)
            }
        }
    }

    companion object {
        private val lock = Mutex()
        private var initialized: Boolean = false

        suspend fun rescheduleAll(context: Context) = lock.withLock {
            if (initialized)
                return

            initialized = true

            Log.i("Reschedule all profiles update")

            val subscriptions = ImportedDao().queryAll().filter { it.type != Profile.Type.File }
            subscriptions.forEach { scheduleNext(context, it) }

            // 1.2.0 rewrote REALITY nodes inside every stored subscription base; the only way
            // back to the operator's config is a fresh fetch. Once, right after the upgrade.
            if (subscriptions.isNotEmpty() && ServiceStore.consumeRealityCompatRefetch(context)) {
                Log.i("Re-fetching ${subscriptions.size} subscription(s) to drop the 1.2.0 REALITY rewrite")
                subscriptions.forEach { context.sendBroadcast(updateIntentOf(context, it)) }
            }
        }

        fun cancelNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)
        }

        fun schedule(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            intent.send(context, 0, null)
        }

        fun scheduleNext(context: Context, imported: Imported) {
            val intent = pendingIntentOf(context, imported)

            context.getSystemService<AlarmManager>()?.cancel(intent)

            if (imported.interval < TimeUnit.MINUTES.toMillis(15))
                return

            val current = System.currentTimeMillis()
            // Count from the last fetch. subscription.yaml is written only when the subscription
            // is fetched; config.yaml is re-composed on every VPN start and profile load, so timing
            // from it pushed the next update back after every connect and a daily user never got one.
            val profileDir = context.importedDir.resolve(imported.uuid.toString())
            val last = lastSubscriptionFetchAt(profileDir)

            // file not existed
            if (last <= 0)
                return

            val interval = (imported.interval - (current - last)).coerceAtLeast(0)

            // Not a wakeup alarm: a subscription refresh can wait until the device is awake
            // anyway. With a WAKEUP alarm per subscription, many subscriptions meant the phone
            // was woken (and a foreground service started) once per subscription per interval.
            context.getSystemService<AlarmManager>()
                ?.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + interval,
                    intent
                )
        }

        private suspend fun reset() = lock.withLock {
            initialized = false
        }

        private fun updateIntentOf(context: Context, imported: Imported): Intent =
            Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName)
                .setUUID(imported.uuid)

        private fun pendingIntentOf(context: Context, imported: Imported): PendingIntent {
            val intent = updateIntentOf(context, imported)

            return PendingIntent.getBroadcast(
                context,
                imported.uuid.hashCode(),
                intent,
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
    }
}