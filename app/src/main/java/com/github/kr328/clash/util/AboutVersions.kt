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

    val core: String by lazy {
        val raw = CoreBuildConfig.CORE_TAG.replace("_", "-")
        val version = semver.find(raw)?.groupValues?.get(1) ?: raw.ifBlank { "—" }
        "Mihomo $version"
    }
}
