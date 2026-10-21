package com.github.kr328.clash.util

import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.core.BuildConfig as CoreBuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AboutVersionsTest {
    @Test
    fun versionLabelsCanBeReadWithoutAndroidOrNativeLibraries() {
        // JVM tests have no JNI runtime: touching Bridge here would fail to load it.
        val channel = if (BuildConfig.DEBUG) "Debug" else "Release"
        assertTrue(AboutVersions.app.matches(Regex("\\d+\\.\\d+\\.\\d+\\.$channel")))
        assertEquals(
            AboutVersions.coreLabel(CoreBuildConfig.CORE_NAME, CoreBuildConfig.CORE_TAG,
                CoreBuildConfig.CITRUS_TAG, CoreBuildConfig.CORE_REVISION),
            AboutVersions.core,
        )
        assertTrue(AboutVersions.core.startsWith("${CoreBuildConfig.CORE_NAME} "))
    }

    @Test
    fun coreIsNamedAfterTheSubmodule() {
        assertEquals("Citrus 1.0.0-beta.2", AboutVersions.coreLabel("Citrus", "v1.19.32", "v1.0.0-beta.2"))
        assertEquals("Citrus 1.0.0", AboutVersions.coreLabel("Citrus", "", "v1.0.0"))
        assertEquals("Citrus —", AboutVersions.coreLabel("Citrus", "v1.19.32", ""))
        assertEquals("Mihomo 1.19.32", AboutVersions.coreLabel("Mihomo", "v1.19.32", ""))
        assertEquals("Mihomo —", AboutVersions.coreLabel("Mihomo", "", ""))
    }

    @Test fun devBuildsShowTheActualPinnedCommit() {
        assertEquals("Citrus 1.0.0-beta.2 · 843a3700",
            AboutVersions.coreLabel("Citrus", "v1.19.32", "v1.0.0-beta.2", "843a37008f6f"))
        assertEquals("Citrus 1.0.0-beta.2",
            AboutVersions.coreLabel("Citrus", "v1.19.32", "v1.0.0-beta.2", "unknown"))
    }
}
