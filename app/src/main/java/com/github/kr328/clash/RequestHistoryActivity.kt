package com.github.kr328.clash

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.RequestHistoryDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.log.RequestHistoryTrackingSession
import com.github.kr328.clash.service.model.RequestHistorySnapshot
import com.github.kr328.clash.service.model.formatRequestHistoryExport
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter

class RequestHistoryActivity : BaseActivity<RequestHistoryDesign>() {
    override suspend fun main() {
        val design = RequestHistoryDesign(this)
        setContentDesign(design)

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        suspend fun querySnapshot(): RequestHistorySnapshot? {
            val raw = withClash { queryRequestHistory() }
            return withContext(Dispatchers.Default) {
                runCatching {
                    json.decodeFromString(RequestHistorySnapshot.serializer(), raw)
                }.getOrElse {
                    if (it is CancellationException) throw it
                    Log.w("Request history decode failed; raw size=${raw.length}", it)
                    null
                }
            }
        }

        suspend fun refresh() {
            querySnapshot()?.let { design.patchSnapshot(it) }
        }

        val tracking = RequestHistoryTrackingSession(
            scope = this,
            start = { withClash { startRequestHistoryTracking() } },
            stop = { withClash { stopRequestHistoryTracking() } },
            refresh = { refresh() },
            onFailure = {
                Log.w("Request history refresh failed", it)
                if (it is com.github.kr328.clash.common.util.RemoteServiceUnavailableException && activityStarted) {
                    launch { design.showExceptionToast(it) }
                }
            },
        )

        try {
            tracking.setVisible(activityStarted)

            while (isActive) {
                select<Unit> {
                    events.onReceive {
                        when (it) {
                            Event.ActivityStart -> tracking.setVisible(activityStarted)
                            Event.ActivityStop -> tracking.setVisible(false)
                            Event.ServiceRecreated -> {
                                tracking.setVisible(false)
                                tracking.setVisible(activityStarted)
                            }
                            else -> Unit
                        }
                    }
                    design.requests.onReceive {
                        when (it) {
                            RequestHistoryDesign.Request.Clear -> launch {
                                withClash { clearRequestHistory() }
                                refresh()
                                design.showToast(R.string.request_history_cleared, ToastDuration.Short)
                            }
                            RequestHistoryDesign.Request.Export -> launch {
                                val snapshot = querySnapshot() ?: return@launch
                                val output = startActivityForResult(
                                    ActivityResultContracts.CreateDocument("text/csv"),
                                    "mikan-request-history.csv",
                                )
                                if (output != null) {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            writeExport(snapshot, output)
                                        }
                                        design.showToast(R.string.file_exported, ToastDuration.Long)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        design.showExceptionToast(error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                tracking.setVisible(false)
            }
        }
    }

    private fun writeExport(snapshot: RequestHistorySnapshot, uri: Uri) {
        OutputStreamWriter(contentResolver.openOutputStream(uri)).use {
            it.write(formatRequestHistoryExport(snapshot.requests))
        }
    }
}
