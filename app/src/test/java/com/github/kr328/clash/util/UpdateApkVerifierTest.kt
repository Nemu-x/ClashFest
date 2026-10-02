package com.github.kr328.clash.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateApkVerifierTest {
    @Test
    fun acceptsOnlyMikanGitHubReleaseApkUrls() {
        assertTrue(
            UpdateApkVerifier.isTrustedDownloadUrl(
                "https://github.com/getmikan/MikanApp/releases/download/v1.2.3/mikan-alpha-universal.apk",
            ),
        )
        assertFalse(
            UpdateApkVerifier.isTrustedDownloadUrl(
                "http://github.com/getmikan/MikanApp/releases/download/v1.2.3/mikan.apk",
            ),
        )
        assertFalse(
            UpdateApkVerifier.isTrustedDownloadUrl(
                "https://evil.example/getmikan/MikanApp/releases/download/v1.2.3/mikan.apk",
            ),
        )
        assertFalse(
            UpdateApkVerifier.isTrustedDownloadUrl(
                "https://github.com/Other/MikanApp/releases/download/v1.2.3/mikan.apk",
            ),
        )
        assertFalse(
            UpdateApkVerifier.isTrustedDownloadUrl(
                "https://github.com/getmikan/MikanApp/releases/download/v1.2.3/checksum.txt",
            ),
        )
        assertFalse(UpdateApkVerifier.isTrustedDownloadUrl("https://user@github.com/getmikan/MikanApp/releases/download/v1.2.3/app.apk"))
        assertFalse(UpdateApkVerifier.isTrustedDownloadUrl("https://github.com:443/getmikan/MikanApp/releases/download/v1.2.3/app.apk"))
    }

    @Test
    fun rejectsUpstreamAndOtherRepositoryUpdates() {
        for (repo in listOf("Nemu-x/ClashFest", "getmikan/OtherApp", "Other/MikanApp")) {
            assertFalse(UpdateApkVerifier.isTrustedDownloadUrl("https://github.com/$repo/releases/download/v1.2.3/mikan.apk"))
            assertFalse(UpdateApkVerifier.isTrustedReleasePageUrl("https://github.com/$repo/releases/tag/v1.2.3"))
        }
    }

    @Test
    fun rejectsAmbiguousDownloadUrls() {
        val base = "https://github.com/getmikan/MikanApp/releases/download/v1.2.3/mikan.apk"
        for (suffix in listOf("?redirect=evil", "#fragment", "/extra")) {
            assertFalse(UpdateApkVerifier.isTrustedDownloadUrl(base + suffix))
        }
        assertFalse(UpdateApkVerifier.isTrustedDownloadUrl(null))
        assertFalse(UpdateApkVerifier.isTrustedDownloadUrl(""))
        assertFalse(UpdateApkVerifier.isTrustedDownloadUrl("https://github.com.evil.example/getmikan/MikanApp/releases/download/v1.2.3/mikan.apk"))
    }

    @Test
    fun acceptsOnlyMikanGitHubReleasePages() {
        assertTrue(UpdateApkVerifier.isTrustedReleasePageUrl("https://github.com/getmikan/MikanApp/releases/tag/v1.2.3"))
        assertFalse(UpdateApkVerifier.isTrustedReleasePageUrl("https://evil.example/getmikan/MikanApp/releases/tag/v1.2.3"))
        assertFalse(UpdateApkVerifier.isTrustedReleasePageUrl("https://github.com/getmikan/MikanApp/issues/1"))
        assertFalse(UpdateApkVerifier.isTrustedReleasePageUrl("intent://github.com/getmikan/MikanApp/releases/tag/v1.2.3"))
    }
}
