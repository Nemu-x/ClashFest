package com.github.kr328.clash.service.util

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorPolicyHeadersTest {
    private fun snapshot(json: String): FetchHeadersFile {
        val dir = Files.createTempDirectory("oph").toFile()
        File(dir, FetchHeadersFile.FILE_NAME).writeText(json)
        return FetchHeadersFile.readFrom(dir)!!
    }

    @Test
    fun reads_all_three_policies() {
        val p = OperatorPolicyHeaders.policyOf(
            snapshot("""{"x-network-stack":"gVisor","share-links":"true","x-bypass-preset":"ru"}"""),
        )
        assertEquals("gvisor", p.networkStack)
        assertTrue(p.shareLinksLocked)
        assertEquals("ru", p.bypassPreset)
    }

    @Test
    fun withdrawn_headers_clear_the_policy() {
        // A completed fetch without the headers: the operator withdrew them, so nothing stays locked.
        val p = OperatorPolicyHeaders.policyOf(snapshot("""{"subscription-userinfo":"upload=1; download=2; total=3"}"""))
        assertNull(p.networkStack)
        assertFalse(p.shareLinksLocked)
        assertNull(p.bypassPreset)
    }

    @Test
    fun auto_stack_is_kept_as_explicit_unlock() {
        assertEquals("auto", OperatorPolicyHeaders.policyOf(snapshot("""{"x-network-stack":"auto"}""")).networkStack)
    }

    @Test
    fun unknown_stack_and_none_preset_read_as_absent() {
        val p = OperatorPolicyHeaders.policyOf(snapshot("""{"x-network-stack":"lwip2","x-bypass-preset":"none"}"""))
        assertNull(p.networkStack)
        assertNull(p.bypassPreset)
    }
}
