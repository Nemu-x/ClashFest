package com.github.kr328.clash.main

import android.widget.Toast
import com.github.kr328.clash.MainActivity
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.BypassPreset
import com.github.kr328.clash.util.BypassPresets
import com.github.kr328.clash.util.withProfile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The pre-start "exclude regional apps from the VPN" offer (operator recommendation or the
 * generic one-time seed).
 */
internal class MainBypassPrompter(private val activity: MainActivity) {
    private val uiStore get() = activity.store
    private val themedContext get() = activity.themedContext

    /**
     * Pre-start bypass offer, two sources in priority order:
     *
     * 1. Operator recommendation (`X-Bypass-Preset` header, stored per profile) — offered
     *    once per (profile, preset id) even if the generic prompt was already answered;
     *    a changed header value re-offers once. Never auto-applied.
     * 2. Generic one-time seed: when the per-app exclusion list is empty, the preset with
     *    the most installed matches (threshold ≥3). If nothing clears the threshold we do
     *    not ask and keep the prompt un-handled, so a user who installs regional apps
     *    later still gets a single offer.
     *
     * Skip still starts VPN; cancel does not.
     */
    suspend fun maybePromptRuBypass(): Boolean {
        val service = ServiceStore(activity)

        val operator = withContext(Dispatchers.IO) { operatorRecommendedPreset(service) }
        if (operator != null) {
            val (preset, installedApps) = operator
            return promptBypassPreset(service, preset, installedApps, fromOperator = true)
        }

        if (uiStore.ruBypassPromptHandled) return true
        val best = withContext(Dispatchers.IO) {
            if (service.accessControlPackages.isNotEmpty()) null
            else BypassPresets.bestInstalled(activity, activity.packageManager)
        } ?: return true
        val (preset, installedApps) = best
        return promptBypassPreset(service, preset, installedApps, fromOperator = false)
    }

    /**
     * Unanswered operator recommendation for the active profile, resolved to a bundled
     * preset with its installed matches.
     *
     * The "offered" marker encodes WHEN we last asked, so a growing list can re-offer:
     * - `Apply` stores `<id>:<preset.version>` — bumping the bundled preset's `version`
     *   (i.e. we shipped more apps in that list) makes the marker stale and re-offers once.
     * - `Skip` stores `<id>:skip` — version-independent, so a user who declined is never
     *   nagged again for that region no matter how the list grows.
     * A different preset id from the operator (`ru`→`cn`) never matches either marker, so
     * it always offers. Unknown ids and zero-match presets are consumed as skip.
     */
    private suspend fun operatorRecommendedPreset(
        service: ServiceStore,
    ): Pair<BypassPreset, Set<String>>? {
        val active = runCatching { withProfile { queryActive() } }.getOrNull() ?: return null
        val recommended = service.subscriptionBypassPresetFor(active.uuid) ?: return null

        val preset = BypassPresets.load(activity).firstOrNull { it.id == recommended }
        val installed = preset?.installed(activity.packageManager).orEmpty()
        if (preset == null || installed.isEmpty()) {
            service.setSubscriptionBypassPresetOfferedFor(active.uuid, bypassSkipMarker(recommended))
            return null
        }

        val offered = service.subscriptionBypassPresetOfferedFor(active.uuid)
        if (offered == bypassAppliedMarker(preset) || offered == bypassSkipMarker(recommended)) {
            return null
        }
        return preset to installed
    }

    private fun bypassAppliedMarker(preset: BypassPreset): String = "${preset.id}:${preset.version}"
    private fun bypassSkipMarker(presetId: String): String = "$presetId:skip"

    private suspend fun promptBypassPreset(
        service: ServiceStore,
        preset: BypassPreset,
        installedApps: Set<String>,
        fromOperator: Boolean,
    ): Boolean {
        val title = BypassPresets.displayTitle(activity, preset)

        suspend fun markAnswered(applied: Boolean) {
            if (fromOperator) {
                val marker = if (applied) bypassAppliedMarker(preset) else bypassSkipMarker(preset.id)
                withContext(Dispatchers.IO) {
                    runCatching { withProfile { queryActive() } }.getOrNull()?.let {
                        service.setSubscriptionBypassPresetOfferedFor(it.uuid, marker)
                    }
                }
            } else {
                uiStore.ruBypassPromptHandled = true
            }
        }

        return suspendCancellableCoroutine { cont ->
            val body = if (fromOperator) {
                activity.getString(R.string.bypass_operator_prompt_message, installedApps.size, title)
            } else {
                activity.getString(R.string.bypass_prompt_message, installedApps.size, title)
            }
            val message = buildString {
                append(body)
                append("\n\n")
                append(activity.getString(R.string.bypass_prompt_tile_note))
            }
            val dialogTitle = if (fromOperator) {
                activity.getString(R.string.bypass_operator_prompt_title)
            } else {
                activity.getString(R.string.bypass_prompt_title, title)
            }
            val dialog = MaterialAlertDialogBuilder(themedContext)
                .setTitle(dialogTitle)
                .setMessage(message)
                .setPositiveButton(R.string.bypass_prompt_apply) { d, _ ->
                    d.dismiss()
                    activity.launch {
                        val count = withContext(Dispatchers.IO) {
                            BypassPresets.applyToStore(service, activity.packageManager, preset)
                        }
                        markAnswered(applied = true)
                        if (count > 0) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.bypass_preset_seeded, count),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }
                }
                .setNegativeButton(R.string.bypass_prompt_skip) { d, _ ->
                    d.dismiss()
                    activity.launch {
                        markAnswered(applied = false)
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
                .show()
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
        }
    }
}
