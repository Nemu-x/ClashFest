package com.github.kr328.clash.common.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes [path] downsampled by a power of two so that neither side is much larger than
 * [maxSidePx]. Operator logos are whatever size the panel serves; full-resolution decodes of
 * them for a 48dp header or a notification icon only cost memory.
 */
fun decodeSampledBitmap(path: String, maxSidePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSidePx)
    }
    return BitmapFactory.decodeFile(path, options)
}

internal fun sampleSizeFor(width: Int, height: Int, maxSidePx: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxSidePx || height / (sample * 2) >= maxSidePx) {
        sample *= 2
    }
    return sample
}
