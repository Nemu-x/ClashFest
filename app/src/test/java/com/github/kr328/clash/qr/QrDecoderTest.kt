package com.github.kr328.clash.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrDecoderTest {
    /** Renders [text] as a QR code into a Y plane with the given padding and stride slack. */
    private fun luminanceOf(text: String, scale: Int = 4, pad: Int = 40, strideSlack: Int = 0): Triple<ByteArray, Int, Pair<Int, Int>> {
        // Same hints as the app's own QR writer (companion/QrCode.kt): UTF-8 payloads.
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 0)
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val width = matrix.width * scale + pad * 2
        val height = matrix.height * scale + pad * 2
        val rowStride = width + strideSlack
        val y = ByteArray(rowStride * height) { 0xF0.toByte() } // light background
        for (row in 0 until matrix.height) {
            for (col in 0 until matrix.width) {
                if (!matrix.get(col, row)) continue
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    y[(pad + row * scale + dy) * rowStride + pad + col * scale + dx] = 0x10
                }
            }
        }
        return Triple(y, rowStride, width to height)
    }

    @Test
    fun decodes_subscription_url_from_luminance_plane() {
        val url = "https://panel.example.com/sub/abc123?format=clash"
        val (y, stride, size) = luminanceOf(url)
        assertEquals(url, QrDecoder().decode(y, stride, size.first, size.second))
    }

    @Test
    fun decodes_with_row_stride_larger_than_width() {
        // CameraX Y planes often carry padding bytes at the end of each row.
        val text = "clashfest://install-config?url=https%3A%2F%2Fexample.com%2Fsub"
        val (y, stride, size) = luminanceOf(text, strideSlack = 64)
        assertEquals(text, QrDecoder().decode(y, stride, size.first, size.second))
    }

    @Test
    fun decodes_unicode_payload() {
        val text = "Подписка ✓ 香港"
        val (y, stride, size) = luminanceOf(text)
        assertEquals(text, QrDecoder().decode(y, stride, size.first, size.second))
    }

    @Test
    fun returns_null_for_a_blank_frame_and_stays_reusable() {
        val decoder = QrDecoder()
        val blank = ByteArray(320 * 240) { 0x80.toByte() }
        assertNull(decoder.decode(blank, 320, 320, 240))
        val (y, stride, size) = luminanceOf("after-blank")
        assertEquals("after-blank", decoder.decode(y, stride, size.first, size.second))
    }
}
