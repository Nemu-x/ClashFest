package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.branding.BrandNotificationChrome
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import java.io.File
import java.util.UUID

/**
 * Everything the persistent VPN notification shows besides live traffic, shared by
 * [StaticNotificationModule] and [DynamicNotificationModule]:
 *
 * - title: operator brand name when the active subscription is branded, else the profile name;
 * - accent colour and large icon from the brand manifest / cached logo file;
 * - the node the traffic currently leaves through (leaf of the first selector, or of GLOBAL in
 *   Global mode) — resolved from the engine, never per tick: on PROFILE_LOADED, on a selector
 *   patch ([Intents.ACTION_PROXY_SELECTION_CHANGED]) and on screen-on, so auto-group flips show
 *   up at the latest when the user next looks at the phone;
 * - days left, from the imported profile's `expire` (one Room read per profile load);
 * - two actions: Disconnect (self broadcast handled by [CloseModule]) and Change node (opens the
 *   Home node picker via [Intents.ACTION_OPEN_NODE_PICKER]).
 *
 * Every field is cached so the 10 s traffic tick stays a pure `notify()`; callers compare
 * [snapshot] to skip identical rebuilds.
 */
class ClashNotificationPresenter(private val service: Service) {
    private var profileUuid: UUID? = null
    private var profileName: String? = null
    private var brandName: String? = null
    private var accentColor: Int? = null
    private var largeIcon: Bitmap? = null
    private var largeIconPath: String? = null
    private var daysLeft: Int? = null
    private var currentNode: String? = null

    /** Cheap identity of the non-traffic state; changes whenever a rebuild is warranted. */
    val snapshot: String
        get() = "$profileName|$brandName|$accentColor|$largeIconPath|$daysLeft|$currentNode"

    /** Re-reads profile, brand and quota state. Call on PROFILE_LOADED (Room read inside). */
    suspend fun refreshProfileState() {
        profileName = StatusProvider.currentProfile
        val uuid = ServiceStore(service).activeProfile
        profileUuid = uuid
        if (uuid == null) {
            brandName = null
            accentColor = null
            setLargeIcon(null)
            daysLeft = null
            return
        }

        val chrome = BrandNotificationChrome.forProfile(service, uuid)
        brandName = chrome.name
        accentColor = chrome.accentColor
        setLargeIcon(chrome.logoPath)

        daysLeft = runCatching { ImportedDao().queryByUUID(uuid)?.expire }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?.let { expireAt ->
                val now = System.currentTimeMillis()
                if (expireAt <= now) 0
                else ((expireAt - now + 86_400_000L - 1L) / 86_400_000L).toInt().coerceAtLeast(1)
            }
    }

    /**
     * Resolves the leaf node behind the primary selector. A handful of JNI calls (one per nested
     * group level), so it is event-driven, not ticked.
     */
    fun refreshNode() {
        currentNode = runCatching { resolveCurrentNode() }
            .onFailure { Log.d("Notification: node resolve failed: ${it.message}") }
            .getOrNull()
    }

    private fun resolveCurrentNode(): String? {
        val names = Clash.queryGroupNames(true)
        val mode = Clash.queryTunnelState().mode
        val start = if (mode == TunnelState.Mode.Global) {
            "GLOBAL"
        } else {
            names.firstOrNull() ?: return null
        }
        Log.d("Notification: resolve node mode=$mode start=$start groups=$names")
        val seen = HashSet<String>()
        var current = start
        while (seen.add(current)) {
            val group = Clash.queryGroup(current, ProxySort.Default)
            val now = group.now.takeIf { it.isNotBlank() } ?: return null
            val nowIsGroup = now in names ||
                group.proxies.firstOrNull { it.name == now }?.type?.group == true
            if (!nowIsGroup) return now
            current = now
        }
        return null
    }

    fun title(): String =
        brandName ?: profileName ?: service.getString(R.string.notification_profile_none)

    fun nodeLine(): String? = currentNode

    fun daysLeftLine(): String? =
        daysLeft?.let { service.resources.getQuantityString(R.plurals.notification_days_left, it, it) }

    /** Fresh builder carrying all static chrome; the caller sets the text fields. */
    fun newBuilder(): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(service, StaticNotificationModule.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setColor(accentColor ?: service.getColorCompat(R.color.color_clash))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(title())
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    R.id.nf_clash_status,
                    Intent().setComponent(Components.MAIN_ACTIVITY).setFlags(MAIN_FLAGS),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            // Disconnect: the same self broadcast the tile and the in-app switch send; CloseModule
            // receives it inside this process, so nothing is started and no FGS/BAL rule applies.
            .addAction(
                0,
                service.getString(R.string.notification_action_disconnect),
                PendingIntent.getBroadcast(
                    service,
                    R.id.nf_action_stop,
                    Intent(Intents.ACTION_CLASH_REQUEST_STOP).setPackage(service.packageName),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .addAction(
                0,
                service.getString(R.string.notification_action_change_node),
                PendingIntent.getActivity(
                    service,
                    R.id.nf_action_node,
                    Intent(Intents.ACTION_OPEN_NODE_PICKER)
                        .setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(MAIN_FLAGS),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
        largeIcon?.let(builder::setLargeIcon)
        return builder
    }

    private fun setLargeIcon(path: String?) {
        if (path == largeIconPath) return
        largeIconPath = path
        largeIcon = path
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { file -> runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() }
    }

    private companion object {
        const val MAIN_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
}
