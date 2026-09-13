package com.github.kr328.clash.core.bridge

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.File

@Keep
object Bridge {
    external fun nativeReset()
    external fun nativeForceGc()
    external fun nativeUpdateGeoDatabases(): String?
    external fun nativeSuspend(suspend: Boolean)
    external fun nativeQueryTunnelState(): String
    external fun nativeQueryTrafficNow(): Long
    external fun nativeQueryTrafficTotal(): Long
    external fun nativeNotifyDnsChanged(dnsList: String)
    external fun nativeNotifyTimeZoneChanged(name: String, offset: Int)
    external fun nativeNotifyInstalledAppChanged(uidList: String)
    external fun nativeStartTun(fd: Int, stack: String, gateway: String, portal: String, dns: String, cb: TunInterface)
    external fun nativeStopTun()
    external fun nativeStartHttp(listenAt: String): String?
    external fun nativeStopHttp()
    external fun nativeQueryGroupNames(excludeNotSelectable: Boolean): String
    external fun nativeQueryAllGroupNamesIncludingHidden(): String
    external fun nativeQueryGroup(name: String, sort: String): String?
    external fun nativeHealthCheck(completable: CompletableDeferred<Unit>, name: String)
    external fun nativeHealthCheckWithCallback(callback: ProxyDelayCallback, name: String, testUrl: String)
    external fun nativeHealthCheckAll()
    external fun nativeHealthCheckAutoGroups(completable: CompletableDeferred<Unit>)
    external fun nativePatchSelector(selector: String, name: String): Boolean
    external fun nativeFetchAndValid(
        completable: FetchCallback,
        path: String,
        url: String,
        force: Boolean,
        viaProxy: Boolean,
        subscriptionHeadersJson: String,
    )

    external fun nativeFetchProvidersAndValid(
        completable: FetchCallback,
        path: String,
        force: Boolean,
        subscriptionHeadersJson: String,
    )

    external fun nativeSetAgeSecretKey(key: String?)
    external fun nativeGenX25519KeyPair(): String?
    external fun nativeGenHybridKeyPair(): String?
    external fun nativeVeritySecretKeys(secretKeys: String): Boolean
    external fun nativeToPublicKeys(secretKeys: String): String?
    external fun nativeVerityPublicKeys(publicKeys: String): Boolean
    external fun nativeLoad(completable: CompletableDeferred<Unit>, path: String)
    external fun nativeValidateProfile(completable: CompletableDeferred<Unit>, path: String)
    external fun nativeParseProfileSnapshot(path: String): String
    external fun nativeParseProfileSnapshotFromBytes(yaml: String): String
    external fun nativeResolveProxyGroupsFromBytes(yaml: String): String
    external fun nativeValidateProfileBytes(yaml: String): String?

    external fun nativeApplyConfigScript(yaml: String, script: String, profileName: String): String
    external fun nativeQueryProviders(): String
    external fun nativeQueryConnectionsSnapshot(): String
    external fun nativeCloseConnection(id: String): Boolean
    external fun nativeCloseAllConnections(): Int
    external fun nativeUpdateProvider(
        completable: CompletableDeferred<Unit>,
        type: String,
        name: String
    )

    external fun nativeReadOverride(slot: Int): String
    external fun nativeWriteOverride(slot: Int, content: String)
    external fun nativeClearOverride(slot: Int)
    external fun nativeQueryConfiguration(): String
    external fun nativeSubscribeLogcat(callback: LogcatInterface)
    external fun nativeCoreVersion(): String

    private external fun nativeInit(home: String, versionName: String, sdkVersion: Int, debug: Boolean)

    init {
        System.loadLibrary("bridge")

        val ctx = Global.application

        ParcelFileDescriptor.open(File(ctx.packageCodePath), ParcelFileDescriptor.MODE_READ_ONLY)
            .detachFd()

        val home = ctx.filesDir.resolve("clash").apply { mkdirs() }.absolutePath
        val versionName = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
        val sdkVersion = Build.VERSION.SDK_INT
        // Gates how much of the engine's log bus is mirrored into logcat: everything while
        // developing, warnings and errors only in release. Read off the installed app rather than
        // BuildConfig so the core module doesn't need one. See shouldForwardToLogcat.
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        Log.d("Home = $home")

        nativeInit(home, versionName, sdkVersion, debuggable)
    }
}
