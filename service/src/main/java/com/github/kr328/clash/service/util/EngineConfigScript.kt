package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigScriptResult

/**
 * The real [ConfigScriptRunner]: hands the document to the engine, which parses the YAML, runs
 * the script and re-serialises with the same library it loads configs with.
 *
 * [profileName] is the second argument `main(config, profileName)` receives. Some clients pass
 * it and some do not, so we always supply it: a script that declares one parameter simply ignores
 * the extra argument.
 *
 * A script is user code: this blocks for as long as the script runs (capped engine-side), so
 * call it off the main thread.
 */
fun engineConfigScriptRunner(profileName: String): ConfigScriptRunner =
    ConfigScriptRunner { yaml, script ->
        when (val result = Clash.applyConfigScript(yaml, script, profileName)) {
            is ConfigScriptResult.Success -> result.yaml
            is ConfigScriptResult.Failure -> throw ConfigScriptException(result.error, result.message)
        }
    }
