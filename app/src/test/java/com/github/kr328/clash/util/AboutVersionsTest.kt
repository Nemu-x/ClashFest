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
        val compiledVersion = Regex("v?(\\d+\\.\\d+\\.\\d+)")
            .find(CoreBuildConfig.CORE_TAG)?.groupValues?.get(1)
        assertEquals("Mihomo $compiledVersion", AboutVersions.core)
    }
}
