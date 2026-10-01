package com.github.kr328.clash.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.github.kr328.clash.R
import com.github.kr328.clash.common.log.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen camera preview that decodes the first QR code it sees and returns
 * its text through [ScanQrCode]. Decoding runs on CameraX's analysis stream with
 * ZXing reading the Y plane directly, so no bitmap copies and no native model.
 */
class QrScanActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var analysisExecutor: ExecutorService
    private val delivered = AtomicBoolean(false)
    private val decoder = QrDecoder()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finishWith(RESULT_MISSING_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)
        preview = findViewById(R.id.qr_preview)
        findViewById<android.view.View>(R.id.qr_close).setOnClickListener { finishWith(RESULT_CANCELED) }
        analysisExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUseCase = Preview.Builder().build().also {
                    it.surfaceProvider = preview.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            } catch (e: Exception) {
                Log.w("QR scanner: camera unavailable", e)
                finishWith(RESULT_ERROR, Intent().putExtra(EXTRA_ERROR, e.message))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (delivered.get()) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val text = decoder.decode(bytes, plane.rowStride, image.width, image.height)
            if (!text.isNullOrEmpty() && delivered.compareAndSet(false, true)) {
                runOnUiThread { finishWith(RESULT_OK, Intent().putExtra(EXTRA_CONTENT, text)) }
            }
        } finally {
            image.close()
        }
    }

    private fun finishWith(code: Int, data: Intent? = null) {
        setResult(code, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
    }

    companion object {
        const val EXTRA_CONTENT = "content"
        const val EXTRA_ERROR = "error"
        const val RESULT_MISSING_PERMISSION = 10
        const val RESULT_ERROR = 11
    }
}
