package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.service.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.util.concurrent.TimeUnit

class DynamicNotificationModule(service: Service) : Module<Unit>(service) {
    private val presenter = ClashNotificationPresenter(service)
    private val notificationManager = NotificationManagerCompat.from(service)
    private var lastNow: Long = Long.MIN_VALUE
    private var lastTotal: Long = Long.MIN_VALUE
    private var lastInteractive: Boolean = true
    private var lastSnapshot: String? = null

    private fun update() {
        val now = Clash.queryTrafficNow()
        val total = Clash.queryTrafficTotal()
        val interactive = service.getSystemService<PowerManager>()?.isInteractive ?: true
        val snapshot = presenter.snapshot

        if (lastNow == now && lastTotal == total && lastInteractive == interactive && lastSnapshot == snapshot) {
            return
        }
        lastNow = now
        lastTotal = total
        lastInteractive = interactive
        lastSnapshot = snapshot

        val speeds = service.getString(
            R.string.clash_notification_content,
            "${now.trafficUpload()}/s", "${now.trafficDownload()}/s",
        )
        val totals = service.getString(
            R.string.clash_notification_content,
            total.trafficUpload(), total.trafficDownload(),
        )
        val node = presenter.nodeLine()
        val daysLeft = presenter.daysLeftLine()

        // Collapsed: "<node>  ↑/↓ speeds" with days-left as the header sub text (totals when the
        // subscription has no expiry). Expanded: one line per fact.
        val collapsed = if (node != null) "$node  $speeds" else speeds
        val expanded = listOfNotNull(node, speeds, totals, daysLeft).joinToString("\n")

        val notification = presenter.newBuilder()
            .setContentText(collapsed)
            .setSubText(daysLeft ?: totals)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .build()

        notificationManager.notify(R.id.nf_clash_status, notification)
    }

    override suspend fun run() = coroutineScope {
        var shouldUpdate = service.getSystemService<PowerManager>()?.isInteractive ?: true

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val profileLoaded = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_LOADED)
        }

        val selectionChanged = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROXY_SELECTION_CHANGED)
        }

        // Two tickers: a frequent one while the user is looking at the screen,
        // and a sparse one while the screen is off. The screen-off ticker exists only
        // so totals stay roughly accurate after a long sleep; per-second deltas are not
        // visible anyway when there is nothing to display. The node is never resolved on a
        // tick — only on the events below — so the tick stays two traffic queries + notify().
        val tickerInteractive = ticker(TimeUnit.SECONDS.toMillis(10))
        val tickerIdle = ticker(TimeUnit.MINUTES.toMillis(2))

        while (true) {
            select<Unit> {
                screenToggle.onReceive {
                    when (it.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            shouldUpdate = true
                            // Auto groups may have flipped while the screen was off.
                            presenter.refreshNode()
                            update()
                        }
                        Intent.ACTION_SCREEN_OFF ->
                            shouldUpdate = false
                    }
                }
                profileLoaded.onReceive {
                    presenter.refreshProfileState()
                    presenter.refreshNode()
                    update()
                }
                selectionChanged.onReceive {
                    presenter.refreshNode()
                    update()
                }
                if (shouldUpdate) {
                    tickerInteractive.onReceive {
                        update()
                    }
                } else {
                    tickerIdle.onReceive {
                        update()
                    }
                }
            }
        }
    }
}
