package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.model.ConfigScriptError
import com.github.kr328.clash.service.model.ProxyHardeningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The script stage inside [ConfigComposer]. The runner is injected, so these assert the
 * *wiring* — when it runs, what it receives, and that hardening still gets the last word.
 * The transform itself is covered by the Go tests in `native/config/configscript`.
 */
class ConfigComposerScriptTest {
    private val geo = GeoDataUrls("", "", "", "")
    private val base = "mode: rule\nport: 7890\n"

    private fun compose(layer: UserLayer, runner: ConfigScriptRunner) =
        ConfigComposer.compose(base, layer, geo, ProxyHardeningMode.Off, runner)

    @Test
    fun `no script leaves the document alone`() {
        var called = false
        compose(UserLayer()) { yaml, _ -> called = true; yaml }
        assertTrue("runner must not be invoked without a script", !called)
    }

    @Test
    fun `disabled script does not run`() {
        var called = false
        val layer = UserLayer(script = UserScript(source = "function main(c){return c}", enabled = false))
        compose(layer) { yaml, _ -> called = true; yaml }
        assertTrue("a disabled script must not run", !called)
    }

    @Test
    fun `blank source does not run`() {
        var called = false
        compose(UserLayer(script = UserScript(source = "   "))) { yaml, _ -> called = true; yaml }
        assertTrue("a blank script must not run", !called)
    }

    @Test
    fun `enabled script receives the composed document and its output is used`() {
        var seen: String? = null
        val layer = UserLayer(script = UserScript(source = "SCRIPT"))
        val out = compose(layer) { yaml, script ->
            seen = script
            "$yaml# rewritten\n"
        }
        assertEquals("SCRIPT", seen)
        assertTrue("script output must survive", out.contains("# rewritten"))
        assertTrue("composed input must reach the script", out.contains("port: 7890"))
    }

    @Test
    fun `script failure propagates so the caller can decide`() {
        val layer = UserLayer(script = UserScript(source = "boom"))
        try {
            compose(layer) { _, _ -> throw ConfigScriptException(ConfigScriptError.Timeout, "too slow") }
            fail("expected the failure to propagate")
        } catch (e: ConfigScriptException) {
            assertEquals(ConfigScriptError.Timeout, e.error)
        }
    }

    @Test
    fun `hardening runs after the script`() {
        // A script that re-opens a loopback listener must not survive hardening.
        val layer = UserLayer(script = UserScript(source = "x"))
        val out = ConfigComposer.compose(base, layer, geo, ProxyHardeningMode.Strict) { yaml, _ ->
            yaml + "external-controller: 0.0.0.0:9090\n"
        }
        assertTrue(
            "hardener must still rebind what the script opened, got:\n$out",
            !out.contains("0.0.0.0:9090"),
        )
    }
}
