package com.github.kr328.clash.service.util

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.DesyncConfigRequest
import com.github.kr328.clash.core.model.DesyncGeneratedConfig
import com.github.kr328.clash.service.model.DesyncProfileSettings
import java.io.File

/**
 * The config.yaml of a "Desync only" profile: generated from its [DesyncProfileSettings] by the
 * bridge (`Clash.generateDesyncOnlyConfig`, engine-validated there), never fetched. Written on
 * create/commit, on "update" and whenever the settings change.
 */
object DesyncProfileConfig {
    class GenerationException(message: String) : IllegalArgumentException(message)

    /** The YAML for [settings], or the generator's reason as a [GenerationException]. */
    fun generate(
        settings: DesyncProfileSettings,
        generator: (DesyncConfigRequest) -> DesyncGeneratedConfig = Clash::generateDesyncOnlyConfig,
    ): String {
        val result = generator(settings.toRequest())
        val yaml = result.yaml
        if (yaml.isNullOrBlank()) {
            throw GenerationException(result.error?.takeIf { it.isNotBlank() } ?: "desync profile generation failed")
        }
        return yaml
    }

    /** [generate] for the stored JSON; malformed or missing settings fall back to the defaults. */
    fun generate(
        rawSettings: String?,
        generator: (DesyncConfigRequest) -> DesyncGeneratedConfig = Clash::generateDesyncOnlyConfig,
    ): String = generate(DesyncProfileSettings.decode(rawSettings) ?: DesyncProfileSettings(), generator)

    /**
     * Regenerates `config.yaml` in [profileDir]. Throws before touching the file when generation
     * fails, so a bad settings change never leaves the profile without a working config.
     */
    fun writeTo(
        profileDir: File,
        rawSettings: String?,
        generator: (DesyncConfigRequest) -> DesyncGeneratedConfig = Clash::generateDesyncOnlyConfig,
    ): String {
        val yaml = generate(rawSettings, generator)
        profileDir.mkdirs()
        File(profileDir, "config.yaml").writeText(yaml)
        return yaml
    }

    /** Null when [settings] generate a profile, otherwise the reason (shown to the user). */
    fun check(
        settings: DesyncProfileSettings,
        generator: (DesyncConfigRequest) -> DesyncGeneratedConfig = Clash::generateDesyncOnlyConfig,
    ): String? = try {
        generate(settings, generator)
        null
    } catch (e: GenerationException) {
        e.message
    }
}
