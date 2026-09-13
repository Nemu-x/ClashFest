package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of `configscript.Result` from the engine
 * (`core/src/main/golang/native/config/configscript/script.go`).
 *
 * Success and failure share one channel because the bridge carries a single string.
 */
@Serializable
data class ConfigScriptEnvelope(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("yaml") val yaml: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
)

/**
 * Why a user config script did not produce a document.
 *
 * The codes are the engine's sentinel strings, kept stable so the UI can show its own
 * wording per cause instead of surfacing a raw engine message. An unrecognised code maps to
 * [Runtime] rather than throwing — a newer engine must not break an older screen.
 */
@Serializable
enum class ConfigScriptError(val code: String) {
    /** The script does not parse — a syntax error. */
    Compile("script-compile"),

    /** The script parses but never defines `main`. */
    NoMain("script-no-main"),

    /** `main()` threw. */
    Runtime("script-runtime"),

    /** `main()` ran too long and was interrupted — almost always a runaway loop. */
    Timeout("script-timeout"),

    /** `main()` returned nothing, or something that is not a config object. */
    BadValue("script-bad-value"),

    /** The returned document is implausibly large. */
    TooLarge("script-too-large"),
    ;

    companion object {
        fun fromCode(code: String?): ConfigScriptError =
            entries.firstOrNull { it.code == code } ?: Runtime
    }
}

/** Outcome of running a user config script. */
sealed interface ConfigScriptResult {
    data class Success(val yaml: String) : ConfigScriptResult

    /** [message] is raw engine output — log it, do not show it verbatim. */
    data class Failure(val error: ConfigScriptError, val message: String) : ConfigScriptResult
}
