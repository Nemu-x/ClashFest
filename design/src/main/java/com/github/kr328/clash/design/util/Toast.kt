package com.github.kr328.clash.design.util

import com.github.kr328.clash.design.Design
import com.github.kr328.clash.common.util.RemoteServiceUnavailableException
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.google.android.material.dialog.MaterialAlertDialogBuilder

suspend fun Design<*>.showExceptionToast(message: CharSequence) {
    showToast(message, ToastDuration.Long) {
        setAction(R.string.detail) {
            MaterialAlertDialogBuilder(it.context)
                .setTitle(R.string.error)
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(R.string.ok) { _, _ -> }
                .show()
        }
    }
}

suspend fun Design<*>.showExceptionToast(exception: Exception) {
    showExceptionToast(
        if (exception is RemoteServiceUnavailableException) context.getString(R.string.remote_service_unavailable)
        else exception.message ?: "Unknown",
    )
}
