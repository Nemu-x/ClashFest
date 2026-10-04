package com.github.kr328.clash.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.util.StandalonePing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object DirectServerPing {
    suspend fun measure(context: Context, yaml: String): Int {
        if (!StandalonePing.supportsTcpProbe(yaml)) return StandalonePing.TCP_UNSUPPORTED
        val (host, port) = StandalonePing.parseServerPortFromProxyYaml(yaml) ?: return -1
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return Int.MAX_VALUE
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivity.activeNetwork else null
        val network = connectivity.allNetworks
            .filter { candidate ->
                val capabilities = connectivity.getNetworkCapabilities(candidate)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedWith(compareByDescending<android.net.Network> { it == active }
                .thenByDescending { candidate ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        connectivity.getNetworkCapabilities(candidate)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                }
                .thenByDescending { candidate ->
                    connectivity.getNetworkCapabilities(candidate)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                })
            .firstOrNull() ?: return Int.MAX_VALUE
        val result = StandalonePing.tcpConnectMs(host, port, network)
        currentCoroutineContext().ensureActive()
        return result.getOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
    }
}
