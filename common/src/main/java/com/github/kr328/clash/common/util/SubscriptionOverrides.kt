package com.github.kr328.clash.common.util

import android.content.Context
import org.json.JSONObject
import java.util.UUID

object SubscriptionOverrides {
    private const val PREFS = "subscription_overrides"
    private const val PREFIX_UA = "ua_"
    private const val PREFIX_UA_STRICT = "ua_strict_"

    /**
     * Written by the UI process (PropertiesActivity) and read by the `:background`
     * service process (ProfileProcessor) on the very next fetch. A plain
     * MODE_PRIVATE handle is cached per process after its first load, so the
     * service kept seeing the value from before the user picked a preset.
     * MODE_MULTI_PROCESS is deprecated but still makes getSharedPreferences()
     * re-read the file when another process changed it, and the writers below
     * use commit() so the file is on disk before the IPC that triggers the fetch.
     */
    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    fun getUserAgent(context: Context, uuid: UUID): String? {
        val value = prefs(context)
            .getString(PREFIX_UA + uuid.toString(), "")
            ?.trim()
            .orEmpty()
        return value.takeIf { it.isNotBlank() }
    }

    fun setUserAgent(context: Context, uuid: UUID, userAgent: String?) {
        val key = PREFIX_UA + uuid.toString()
        val value = userAgent?.trim().orEmpty()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(key) else putString(key, value)
        }.commit()
    }

    fun isStrictUserAgent(context: Context, uuid: UUID): Boolean {
        return prefs(context)
            .getBoolean(PREFIX_UA_STRICT + uuid.toString(), false)
    }

    fun setStrictUserAgent(context: Context, uuid: UUID, strict: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(PREFIX_UA_STRICT + uuid.toString(), strict)
            .commit()
    }
}

object SubscriptionRequestHeaders {
    /**
     * The mihomo release tag the core was built from ("v1.19.32"), installed by the
     * Application from `core.BuildConfig.CORE_TAG`; common cannot depend on :core.
     * Null/blank means unknown and the UA then carries a bare `mihomo` token.
     */
    @Volatile
    var coreTag: String? = null

    private val releaseTag = Regex("""^v?(\d+\.\d+\.\d+)$""")
    private val semverPrefix = Regex("""^v?(\d+\.\d+\.\d+)""")

    /**
     * Same string the native subscription fetch sends (`native/useragent` in Go):
     * `mihomo/<coreVersion> Mikan/<semver>`. The mihomo token leads because
     * Marzban / Remnawave pick the clash-meta output format from the first token
     * rather than the app name. Keep the same leading core token as the native fetch.
     */
    fun defaultUserAgent(context: Context): String {
        val ver = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
        return buildDefaultUserAgent(ver, coreTag)
    }

    fun buildDefaultUserAgent(versionName: String, coreTag: String?): String {
        val core = coreTag?.trim()?.let { releaseTag.find(it)?.groupValues?.get(1) }
        val app = semverPrefix.find(versionName)?.groupValues?.get(1) ?: versionName
        return buildString {
            append("mihomo")
            if (!core.isNullOrEmpty()) append('/').append(core)
            append(" Mikan/").append(app)
        }
    }

    fun build(context: Context, userAgentOverride: String? = null): Map<String, String> {
        val ua = userAgentOverride?.trim().takeUnless { it.isNullOrBlank() } ?: defaultUserAgent(context)
        return LinkedHashMap<String, String>().apply {
            put("User-Agent", ua)
            putAll(SubscriptionDeviceHeaders.headerMap(context))
        }
    }

    fun toJson(context: Context, userAgentOverride: String? = null): String =
        JSONObject(build(context, userAgentOverride)).toString()

    /**
     * Native fetch already sets its own stable User-Agent in Go layer.
     * Inject UA only when user explicitly overrides it for a subscription.
     */
    fun toNativeFetchJson(context: Context, userAgentOverride: String? = null): String {
        val map = LinkedHashMap<String, String>().apply {
            putAll(SubscriptionDeviceHeaders.headerMap(context))
            userAgentOverride?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("User-Agent", it) }
        }
        return JSONObject(map as Map<*, *>).toString()
    }
}
