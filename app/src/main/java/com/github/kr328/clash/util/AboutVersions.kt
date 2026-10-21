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

    /** Plain app version (e.g. `1.2.3`), as the companion status summary reports it. */
    val appSemver: String by lazy {
        semver.find(BuildConfig.VERSION_NAME)?.groupValues?.get(1) ?: BuildConfig.VERSION_NAME
    }

    /** The core version [core] shows, as a tag (e.g. `v1.19.26`); empty when unknown. */
    val coreTag: String by lazy {
        semver.find(CoreBuildConfig.CORE_TAG.replace("_", "-"))?.groupValues?.get(1)?.let { "v$it" }.orEmpty()
    }

    val core: String by lazy {
        val raw = CoreBuildConfig.CORE_TAG.replace("_", "-")
        val version = semver.find(raw)?.groupValues?.get(1) ?: raw.ifBlank { "—" }
        "Mihomo $version"
    }
}
