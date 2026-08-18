package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.ConfigScriptError
import com.github.kr328.clash.design.databinding.DesignConfigScriptBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Editor for a profile's user JS config script.
 *
 * Deliberately a plain monospace field rather than a code editor: the whole point of the feature
 * is that people paste scripts written for other Clash clients, and correctness is checked by
 * *running* the script through the engine ([Request.Check]) — which catches far more than
 * highlighting would, including a script that parses fine but blows up on this subscription's
 * data.
 */
class ConfigScriptDesign(context: Context) : Design<ConfigScriptDesign.Request>(context) {
    enum class Request {
        Save, Check, InsertTemplate
    }

    private val binding = DesignConfigScriptBinding
        .inflate(context.layoutInflater, context.root, false)
        .also { it.self = this }

    override val root: View
        get() = binding.root

    /** Current editor contents. */
    val source: String
        get() = binding.scriptText.text?.toString().orEmpty()

    val enabled: Boolean
        get() = binding.enabledSwitch.isChecked

    suspend fun setTitle(title: String) = withContext(Dispatchers.Main) {
        binding.screenTitle.text = title
    }

    suspend fun setState(source: String, enabled: Boolean, locked: Boolean) =
        withContext(Dispatchers.Main) {
            binding.locked = locked
            binding.scriptText.setText(source)
            binding.scriptText.isEnabled = !locked
            binding.enabledSwitch.isChecked = enabled
            binding.enabledSwitch.isEnabled = !locked
        }

    suspend fun insertTemplate(template: String) = withContext(Dispatchers.Main) {
        binding.scriptText.setText(template)
        binding.scriptText.setSelection(template.length)
    }

    /** Clears the status line. */
    suspend fun clearStatus() = withContext(Dispatchers.Main) {
        binding.statusText.visibility = View.GONE
    }

    suspend fun showOk(message: String) = withContext(Dispatchers.Main) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            MaterialColors.getColor(binding.statusText, MaterialR.attr.colorOnSurfaceVariant),
        )
        binding.statusText.visibility = View.VISIBLE
    }

    suspend fun showError(error: ConfigScriptError) = withContext(Dispatchers.Main) {
        binding.statusText.text = context.getString(messageFor(error))
        binding.statusText.setTextColor(
            MaterialColors.getColor(binding.statusText, MaterialR.attr.colorError),
        )
        binding.statusText.visibility = View.VISIBLE
    }

    companion object {
        /** Wording per cause — the engine's own message is log material, not UI copy. */
        fun messageFor(error: ConfigScriptError): Int = when (error) {
            ConfigScriptError.Compile -> R.string.config_script_error_compile
            ConfigScriptError.NoMain -> R.string.config_script_error_no_main
            ConfigScriptError.Runtime -> R.string.config_script_error_runtime
            ConfigScriptError.BadValue -> R.string.config_script_error_bad_value
            ConfigScriptError.Timeout -> R.string.config_script_error_timeout
            ConfigScriptError.TooLarge -> R.string.config_script_error_too_large
        }
    }
}
