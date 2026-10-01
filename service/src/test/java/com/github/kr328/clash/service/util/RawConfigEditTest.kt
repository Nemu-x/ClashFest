package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.RuleItem
import com.github.kr328.clash.service.model.RuleState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawConfigEditTest {
    private val dir: File = Files.createTempDirectory("raw-edit").toFile()

    @AfterTest fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(name: String, text: String?): File =
        File(dir, name).also { f -> if (text != null) f.writeText(text) }

    @Test fun identical_copy_is_not_an_edit() {
        val a = file("imported.yaml", "mixed-port: 7890\n")
        val b = file("pending.yaml", "mixed-port: 7890\n")
        b.setLastModified(a.lastModified() + 60_000) // copyRecursively bumps mtime; bytes decide
        assertFalse(RawConfigEdit.isEdited(a, b))
    }

    @Test fun changed_bytes_are_an_edit_even_at_equal_length() {
        val a = file("imported.yaml", "mixed-port: 7890\n")
        val b = file("pending.yaml", "mixed-port: 7891\n")
        assertTrue(RawConfigEdit.isEdited(a, b))
        assertTrue(RawConfigEdit.isEdited(a, file("longer.yaml", "mixed-port: 7890\nallow-lan: false\n")))
    }

    @Test fun missing_files_are_not_an_edit() {
        val a = file("imported.yaml", "mixed-port: 7890\n")
        assertFalse(RawConfigEdit.isEdited(a, File(dir, "absent.yaml")))
        assertFalse(RawConfigEdit.isEdited(File(dir, "absent.yaml"), a)) // first import: nothing to compare
    }

    @Test fun layer_after_raw_edit_keeps_only_the_script() {
        val layer = UserLayer(
            rules = RuleState(rules = listOf(RuleItem(id = "u1", type = "DOMAIN", value = "a.test", policy = "DIRECT"))),
            dnsHosts = DnsHostsConfig(enable = true, hosts = mapOf("a.test" to "1.2.3.4")),
            proxyChain = mapOf("n1" to "n2"),
            script = UserScript(source = "function main(c) { return c }"),
        )
        assertTrue(RawConfigEdit.hasContentEdits(layer))
        val after = RawConfigEdit.layerAfterRawEdit(layer)
        assertEquals(layer.script, after.script)
        assertTrue(after.rules.rules.isEmpty())
        assertNull(after.dnsHosts)
        assertTrue(after.proxyChain.isEmpty())
        assertFalse(RawConfigEdit.hasContentEdits(after))
        // without a script the result is an empty layer, which the store deletes on save
        assertTrue(RawConfigEdit.layerAfterRawEdit(layer.copy(script = null)).isEmpty())
    }
}
