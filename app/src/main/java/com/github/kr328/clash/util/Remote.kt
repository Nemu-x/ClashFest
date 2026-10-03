package com.github.kr328.clash.util

import android.os.DeadObjectException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.RemoteServiceUnavailableException
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Wait at most 10s for each service connection, with bounded DeadObject retries.
 * Profile downloads and other work after connection retain their own timeouts.
 */
private const val REMOTE_MAX_RETRIES = 10
private const val REMOTE_BACKOFF_BASE_MS = 50L
private const val REMOTE_BACKOFF_MAX_MS = 1000L

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T {
    var attempt = 0
    while (true) {
        val remote = Remote.service.remote.get()

        try {
            return withContext(context) { remote.clash().block() }
        } catch (e: DeadObjectException) {
            attempt += 1
            Log.w("Remote services panic (clash, attempt $attempt)")
            Remote.service.remote.reset(remote)
            if (attempt >= REMOTE_MAX_RETRIES) throw RemoteServiceUnavailableException(e)
            val backoff = (REMOTE_BACKOFF_BASE_MS shl (attempt - 1)).coerceAtMost(REMOTE_BACKOFF_MAX_MS)
            delay(backoff)
        }
    }
}

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T {
    var attempt = 0
    while (true) {
        val remote = Remote.service.remote.get()

        try {
            return withContext(context) { remote.profile().block() }
        } catch (e: DeadObjectException) {
            attempt += 1
            Log.w("Remote services panic (profile, attempt $attempt)")
            Remote.service.remote.reset(remote)
            if (attempt >= REMOTE_MAX_RETRIES) throw RemoteServiceUnavailableException(e)
            val backoff = (REMOTE_BACKOFF_BASE_MS shl (attempt - 1)).coerceAtMost(REMOTE_BACKOFF_MAX_MS)
            delay(backoff)
        }
    }
}
