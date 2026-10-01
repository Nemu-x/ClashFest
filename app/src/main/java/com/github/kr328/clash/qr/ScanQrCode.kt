package com.github.kr328.clash.qr

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/** Outcome of a [ScanQrCode] launch. */
sealed class QrScanResult {
    data class Success(val content: String) : QrScanResult()
    object Canceled : QrScanResult()
    object MissingPermission : QrScanResult()
    data class Error(val message: String?) : QrScanResult()
}

/**
 * Opens the in-app QR scanner ([QrScanActivity]) and returns the decoded text.
 * Replaces the ML Kit based quickie contract: same call shape, but the decoder
 * is ZXing (already in the APK for the Companion pairing code) over CameraX,
 * so the scanner needs no Google Play services and no 5 MB native model.
 */
class ScanQrCode : ActivityResultContract<Unit?, QrScanResult>() {
    override fun createIntent(context: Context, input: Unit?): Intent =
        Intent(context, QrScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): QrScanResult = when (resultCode) {
        Activity.RESULT_OK -> QrScanResult.Success(intent?.getStringExtra(QrScanActivity.EXTRA_CONTENT).orEmpty())
        QrScanActivity.RESULT_MISSING_PERMISSION -> QrScanResult.MissingPermission
        QrScanActivity.RESULT_ERROR -> QrScanResult.Error(intent?.getStringExtra(QrScanActivity.EXTRA_ERROR))
        else -> QrScanResult.Canceled
    }
}
