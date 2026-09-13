package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.branding.BrandStore
import java.util.UUID

/**
 * Whether this profile is allowed to run a user config script, and the runner to use.
 *
 * The check lives here rather than in [ConfigComposer] so composition stays a pure function of
 * (subscription + layer) with no notion of operators, and there is exactly one place that decides.
 *
 * An operator can forbid scripts with `X-Brand-Lock-Config-Script` — a script rewrites proxies,
 * DNS and rules wholesale, so on a managed subscription it would be a way around whatever policy
 * the operator set. The flag is read directly, without `X-Branding-Enabled`, like the other policy
 * flags: restricting the client is not the same thing as skinning it.
 *
 * Enforcement is at *compose* time, not only in the UI. Hiding the editor alone would mean a script
 * saved before the operator turned the flag on kept running forever.
 */
object ConfigScriptPolicy {
    /** True when the operator forbids config scripts for [uuid]. */
    fun isLocked(context: Context, uuid: UUID): Boolean =
        BrandStore(context).manifestFor(uuid).lockConfigScript == true

    /**
     * The runner for [uuid] — a real engine-backed one, or a disabled one when the operator
     * forbids scripts.
     */
    fun runnerFor(context: Context, uuid: UUID, profileName: String): ConfigScriptRunner =
        if (isLocked(context, uuid)) {
            Log.d("ConfigScript: operator policy forbids scripts for $uuid, skipping")
            ConfigScriptRunner.Disabled
        } else {
            engineConfigScriptRunner(profileName)
        }
}
