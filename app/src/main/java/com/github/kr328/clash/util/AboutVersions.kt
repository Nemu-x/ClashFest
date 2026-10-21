package com.github.kr328.clash.util

import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.core.BuildConfig as CoreBuildConfig

internal object AboutVersions {
    private val semver = Regex("v?(\\d+\\.\\d+\\.\\d+)")

    val app: String by lazy {
        val raw = BuildConfig.VERSION_NAME
        val version = semver.find(raw)?.groupValues?.get(1) ?: raw
        val channel = if (BuildConfig.DEBUG) "Debug" else "Release"
        "$version.$channel"
    }

    // Citrus keeps its pre-release suffix (1.0.0-beta.2), mihomo shows x.y.z.
    private val citrusVersion = Regex("""v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)""")

    val core: String by lazy { coreLabel(CoreBuildConfig.CORE_NAME, CoreBuildConfig.CORE_TAG,
        CoreBuildConfig.CITRUS_TAG, CoreBuildConfig.CORE_REVISION) }

    /** "Citrus 1.0.0-beta.2" for a Citrus core, "Mihomo 1.19.32" for upstream mihomo. */
    fun coreLabel(name: String, mihomoTag: String, citrusTag: String, revision: String = ""): String {
        if (name == "Citrus") {
            val raw = citrusTag.replace("_", "-")
            val version = citrusVersion.find(raw)?.groupValues?.get(1) ?: raw.ifBlank { "—" }
            val ref = revision.takeIf { it.matches(Regex("[0-9a-fA-F]{8,40}")) }?.take(8)
            return "Citrus $version" + (ref?.let { " · $it" } ?: "")
        }
        val raw = mihomoTag.replace("_", "-")
        val version = semver.find(raw)?.groupValues?.get(1) ?: raw.ifBlank { "—" }
        return "Mihomo $version"
    }
}
