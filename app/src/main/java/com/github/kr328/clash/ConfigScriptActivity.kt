package com.github.kr328.clash

import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.ConfigScriptDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.ConfigScriptState
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/**
 * Hosts the user config script editor. Checking and saving both go through the engine, so what
 * the editor reports is exactly what the compose pipeline will do with the script.
 */
class ConfigScriptActivity : BaseActivity<ConfigScriptDesign>() {
    override suspend fun main() {
        val uuid = intent.uuid ?: return finish()
        val design = ConfigScriptDesign(this)
        setContentDesign(design)

        val profile = withProfile { queryByUUID(uuid) }
        design.setTitle(
            profile?.name?.takeIf { it.isNotBlank() }
                ?: getString(R.string.config_script_title),
        )

        val state = withProfile { readConfigScript(uuid) }
        design.setState(state.source, state.enabled, state.locked)

        while (isActive) {
            select<Unit> {
                design.requests.onReceive { req ->
                    when (req) {
                        ConfigScriptDesign.Request.InsertTemplate -> {
                            design.insertTemplate(ConfigScriptState.TEMPLATE)
                            design.clearStatus()
                        }
                        ConfigScriptDesign.Request.Check -> {
                            val source = design.source
                            if (source.isBlank()) {
                                design.clearStatus()
                            } else {
                                val failure = withProfile { checkConfigScript(uuid, source) }
                                if (failure == null) {
                                    design.showOk(getString(R.string.config_script_ok))
                                } else {
                                    design.showError(failure.error)
                                }
                            }
                        }
                        ConfigScriptDesign.Request.Save -> {
                            val failure = withProfile {
                                writeConfigScript(uuid, design.source, design.enabled)
                            }
                            if (failure == null) {
                                design.clearStatus()
                                design.showToast(R.string.config_script_saved, ToastDuration.Short)
                            } else {
                                // Nothing was written — the service refuses to store a script it
                                // could not run, so the profile never carries a dead script.
                                design.showError(failure.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
