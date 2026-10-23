package com.github.kr328.clash.design.branding

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import com.github.kr328.clash.common.util.decodeSampledBitmap
import java.io.File

/**
 * Decodes the cached brand-logo bitmap from its `<filesDir>/brand/<sha256>`
 * path and assigns it to an [ImageView]. Result is cached process-wide so
 * the same logo isn't re-decoded for every rebind.
 *
 * Falls back to clearing the image when the path is null / unreadable.
 */
object BrandLogoBinder {

    // Enough for the largest place a logo is shown (full-width headers on a 1080p screen).
    private const val LOGO_MAX_PX = 1024

    private val cache = LruCache<String, Drawable>(8)

    fun bind(view: ImageView, path: String?) {
        if (path.isNullOrBlank()) {
            view.setImageDrawable(null)
            return
        }
        cache.get(path)?.let {
            view.setImageDrawable(it)
            return
        }
        val file = File(path)
        if (!file.isFile) {
            view.setImageDrawable(null)
            return
        }
        val drawable = runCatching {
            val bitmap = decodeSampledBitmap(path, LOGO_MAX_PX) ?: return@runCatching null
            BitmapDrawable(view.resources, bitmap)
        }.getOrNull()
        if (drawable == null) {
            view.setImageDrawable(null)
            return
        }
        cache.put(path, drawable)
        view.setImageDrawable(drawable)
    }

    fun invalidate() = cache.evictAll()
}
