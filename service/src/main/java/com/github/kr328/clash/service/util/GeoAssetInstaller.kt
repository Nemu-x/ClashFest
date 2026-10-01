package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import java.io.File
import java.io.FileOutputStream

private val bundledGeoAssets = listOf(
    "geoip.metadb",
    "geoip.dat",
    "geosite.dat",
    "ASN.mmdb",
)

/**
 * Bundled until 1.1.0 next to geoip.metadb. mihomo opens whichever of the two it
 * lists first, so a leftover copy could keep shadowing the metadb that this
 * installer refreshes on every app update. The user's own import is written as
 * lower-case `country.mmdb` (GeoDatabaseImport) and is left alone.
 */
private const val LEGACY_BUNDLED_COUNTRY_MMDB = "Country.mmdb"

fun Context.ensureBundledGeoAssets() {
    val clashDir = filesDir.resolve("clash").apply { mkdirs() }
    val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime

    for (assetName in bundledGeoAssets) {
        ensureAssetFresh(clashDir, assetName, updateDate)
    }
    val legacy = clashDir.resolve(LEGACY_BUNDLED_COUNTRY_MMDB)
    if (legacy.isFile && legacy.delete()) {
        Log.i("Removed legacy bundled $LEGACY_BUNDLED_COUNTRY_MMDB (geoip.metadb is the country database)")
    }
}

private fun Context.ensureAssetFresh(clashDir: File, assetName: String, updateDate: Long) {
    val target = clashDir.resolve(assetName)
    if (target.exists() && (target.length() <= 0L || target.lastModified() < updateDate)) {
        target.delete()
    }
    if (target.exists()) return

    try {
        FileOutputStream(target).use { output ->
            assets.open(assetName).use { input ->
                input.copyTo(output)
            }
        }
    } catch (e: Exception) {
        Log.w("Asset $assetName not bundled, skipping ($e)")
    }
}
