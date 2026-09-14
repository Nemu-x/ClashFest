package com.github.kr328.clash.service.clash.module

import android.app.Service
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

class StaticNotificationModule(service: Service) : Module<Unit>(service) {
    private val presenter = ClashNotificationPresenter(service)

    override suspend fun run() = coroutineScope {
        val loaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }
        val selectionChanged = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROXY_SELECTION_CHANGED)
        }

        // Rebuild only when something visible changed (profile reloaded under the same name,
        // selector patched to the node already shown, ...).
        var lastSnapshot: String? = null

        while (true) {
            select<Unit> {
                loaded.onReceive {
                    presenter.refreshProfileState()
                    presenter.refreshNode()
                }
                selectionChanged.onReceive {
                    presenter.refreshNode()
                }
            }

            val snapshot = presenter.snapshot
            if (snapshot == lastSnapshot) continue
            lastSnapshot = snapshot

            val notification = presenter.newBuilder()
                .setContentText(presenter.nodeLine() ?: service.getText(R.string.running))
                .setSubText(presenter.daysLeftLine())
                .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
        }
    }

    companion object {
        // Bumped from the original "clash_status_channel": a channel's showBadge can't be changed
        // after creation (Android ignores in-place updates), so the no-badge fix only reaches
        // existing installs by recreating the channel under a new id (+ deleting the old one).
        const val CHANNEL_ID = "clash_status_channel_v2"
        private const val LEGACY_CHANNEL_ID = "clash_status_channel"

        fun createNotificationChannel(service: Service) {
            val manager = NotificationManagerCompat.from(service)
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(service.getText(R.string.clash_service_status_channel))
                    // The persistent VPN-foreground notification must not put a "1" badge on the
                    // launcher icon — users read it as an unread alert and can't swipe it away.
                    .setShowBadge(false)
                    .build()
            )
        }

        fun notifyLoadingNotification(service: Service) {
            val notification =
                NotificationCompat.Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_service)
                    .setOngoing(true)
                    .setColor(service.getColorCompat(R.color.color_clash))
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setContentTitle(service.getText(R.string.loading))
                    .build()

            service.startForegroundCompat(R.id.nf_clash_status, notification)
        }
    }
}