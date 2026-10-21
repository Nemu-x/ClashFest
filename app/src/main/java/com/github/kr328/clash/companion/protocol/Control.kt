package com.github.kr328.clash.companion.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Wire models of the remote-control buttons (PROTOCOL.md §8.1 summary, §8.6–§8.19, §9.2). Pure JVM
 * so the `control.json` golden vectors run in plain unit tests. Every model encodes to a
 * [JsonObject] that [CanonicalJson] turns into the exact bytes the Go reference emits: lists are
 * always arrays, optional fields are omitted when empty.
 *
 * Decoders are strict about types (a string where an integer belongs is a [ControlDecodeException],
 * mapped to `bad_request` by the gateway) and lenient about unknown fields.
 */
class ControlDecodeException(message: String) : Exception(message)

/** Remote-control capability tags (PROTOCOL.md §8.3), in advertisement order. */
object Capabilities {
    const val STATUS = "status"
    const val POWER = "power"
    const val SUBSCRIPTION = "subscription"
    const val RENAME = "rename"
    const val UNPAIR = "unpair"
    const val PROXIES = "proxies"
    const val SELECT = "select"
    const val UNFIX = "unfix"
    const val DELAY = "delay"
    const val MODE = "mode"
    const val PROFILES = "profiles"
    const val PROFILE_UPDATE = "profile_update"
    const val PROFILE_DELETE = "profile_delete"
    const val PROVIDERS = "providers"
    const val CONNECTIONS = "connections"
    const val TRAFFIC = "traffic"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val ACTIONS = "actions"
    const val CORE = "core"
    const val EVENTS = "events"

    /** The §8.3 order every agent advertises in. */
    val ORDER = listOf(
        STATUS, POWER, SUBSCRIPTION, RENAME, UNPAIR, PROXIES, SELECT, UNFIX, DELAY, MODE,
        PROFILES, PROFILE_UPDATE, PROFILE_DELETE, PROVIDERS, CONNECTIONS, TRAFFIC, LOGS,
        SETTINGS, ACTIONS, CORE, EVENTS,
    )
}

/** Field accessors that fail with [ControlDecodeException] on a wrong type. */
internal class Fields(private val obj: JsonObject) {
    private fun primitive(key: String): JsonPrimitive? = when (val v = obj[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> v
        else -> throw ControlDecodeException("$key must be a scalar")
    }

    fun str(key: String): String? = primitive(key)?.let {
        if (!it.isString) throw ControlDecodeException("$key must be a string")
        it.content
    }

    fun long(key: String): Long? = primitive(key)?.let {
        if (it.isString) throw ControlDecodeException("$key must be an integer")
        it.longOrNull ?: throw ControlDecodeException("$key must be an integer")
    }

    fun int(key: String): Int? = long(key)?.let {
        if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw ControlDecodeException("$key out of range")
        it.toInt()
    }

    fun bool(key: String): Boolean? = primitive(key)?.let {
        if (it.isString) throw ControlDecodeException("$key must be a boolean")
        it.booleanOrNull ?: throw ControlDecodeException("$key must be a boolean")
    }

    fun obj(key: String): JsonObject? = when (val v = obj[key]) {
        null, is JsonNull -> null
        is JsonObject -> v
        else -> throw ControlDecodeException("$key must be an object")
    }

    fun arr(key: String): JsonArray? = when (val v = obj[key]) {
        null, is JsonNull -> null
        is JsonArray -> v
        else -> throw ControlDecodeException("$key must be an array")
    }

    fun objects(key: String): List<JsonObject> = arr(key).orEmpty().map {
        it as? JsonObject ?: throw ControlDecodeException("$key must hold objects")
    }

    fun strings(key: String): List<String> = arr(key).orEmpty().map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: throw ControlDecodeException("$key must hold strings")
    }

    fun raw(key: String): JsonElement? = obj[key]?.takeUnless { it is JsonNull }

    fun reqStr(key: String) = str(key) ?: throw ControlDecodeException("$key is required")
    fun reqLong(key: String) = long(key) ?: throw ControlDecodeException("$key is required")
    fun reqInt(key: String) = int(key) ?: throw ControlDecodeException("$key is required")
    fun reqBool(key: String) = bool(key) ?: throw ControlDecodeException("$key is required")
}

private fun JsonElement.asObject(what: String): JsonObject =
    this as? JsonObject ?: throw ControlDecodeException("$what must be a JSON object")

private fun stringArray(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

private fun objectArray(values: List<JsonObject>) = JsonArray(values)

// --- status (§8.1) -----------------------------------------------------------------------------

/** At-a-glance agent state; every field optional, the whole object omitted when empty. */
data class Summary(
    val mode: String? = null,
    val profile: String? = null,
    val proxy: String? = null,
    val appVersion: String? = null,
    val coreVersion: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(mode, profile, proxy, appVersion, coreVersion).all { it.isNullOrEmpty() }

    fun toJson(): JsonObject = buildJsonObject {
        appVersion?.takeIf { it.isNotEmpty() }?.let { put("appVersion", it) }
        coreVersion?.takeIf { it.isNotEmpty() }?.let { put("coreVersion", it) }
        mode?.takeIf { it.isNotEmpty() }?.let { put("mode", it) }
        profile?.takeIf { it.isNotEmpty() }?.let { put("profile", it) }
        proxy?.takeIf { it.isNotEmpty() }?.let { put("proxy", it) }
    }

    companion object {
        fun fromJson(e: JsonElement): Summary = Fields(e.asObject("summary")).run {
            Summary(str("mode"), str("profile"), str("proxy"), str("appVersion"), str("coreVersion"))
        }
    }
}

data class Status(
    val app: String,
    val capabilities: List<String>,
    val id: String,
    val name: String,
    val power: String,
    val ver: Int,
    val summary: Summary? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("app", app)
        put("capabilities", stringArray(capabilities))
        put("id", id)
        put("name", name)
        put("power", power)
        summary?.takeUnless { it.isEmpty }?.let { put("summary", it.toJson()) }
        put("ver", ver)
    }

    companion object {
        fun fromJson(e: JsonElement): Status = Fields(e.asObject("status")).run {
            Status(
                app = reqStr("app"),
                capabilities = strings("capabilities"),
                id = reqStr("id"),
                name = reqStr("name"),
                power = reqStr("power"),
                ver = reqInt("ver"),
                summary = obj("summary")?.let(Summary::fromJson),
            )
        }
    }
}

// --- proxies (§8.7–§8.9) -----------------------------------------------------------------------

data class ProxyNode(val name: String, val type: String, val delay: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("delay", delay)
        put("name", name)
        put("type", type)
    }

    companion object {
        fun fromJson(o: JsonObject) = Fields(o).run { ProxyNode(reqStr("name"), reqStr("type"), reqInt("delay")) }
    }
}

data class ProxyGroup(
    val name: String,
    val type: String,
    val now: String,
    val fixed: String,
    val selectable: Boolean,
    val nodes: List<ProxyNode>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("fixed", fixed)
        put("name", name)
        put("nodes", objectArray(nodes.map { it.toJson() }))
        put("now", now)
        put("selectable", selectable)
        put("type", type)
    }

    companion object {
        fun fromJson(o: JsonObject) = Fields(o).run {
            ProxyGroup(
                name = reqStr("name"),
                type = reqStr("type"),
                now = reqStr("now"),
                fixed = reqStr("fixed"),
                selectable = reqBool("selectable"),
                nodes = objects("nodes").map(ProxyNode::fromJson),
            )
        }
    }
}

data class ProxyList(val groups: List<ProxyGroup>) {
    fun toJson(): JsonObject = buildJsonObject { put("groups", objectArray(groups.map { it.toJson() })) }

    companion object {
        fun fromJson(e: JsonElement) =
            ProxyList(Fields(e.asObject("body")).objects("groups").map(ProxyGroup::fromJson))
    }
}

data class SelectRequest(val group: String?, val node: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        group?.let { put("group", it) }
        node?.let { put("node", it) }
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run { SelectRequest(str("group"), str("node")) }
    }
}

data class SelectResponse(val group: String, val now: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("group", group)
        put("now", now)
        put("ok", true)
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run { SelectResponse(reqStr("group"), reqStr("now")) }
    }
}

data class UnfixRequest(val group: String?) {
    fun toJson(): JsonObject = buildJsonObject { group?.let { put("group", it) } }

    companion object {
        fun fromJson(e: JsonElement) = UnfixRequest(Fields(e.asObject("body")).str("group"))
    }
}

/** `node`, `url` and `timeout` are optional; `timeout` 0 means the default. */
data class DelayRequest(val group: String?, val node: String? = null, val url: String? = null, val timeout: Int = 0) {
    fun toJson(): JsonObject = buildJsonObject {
        group?.let { put("group", it) }
        node?.takeIf { it.isNotEmpty() }?.let { put("node", it) }
        if (timeout != 0) put("timeout", timeout)
        url?.takeIf { it.isNotEmpty() }?.let { put("url", it) }
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run {
            DelayRequest(str("group"), str("node"), str("url"), int("timeout") ?: 0)
        }
    }
}

data class Delay(val name: String, val delay: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("delay", delay)
        put("name", name)
    }

    companion object {
        fun fromJson(o: JsonObject) = Fields(o).run { Delay(reqStr("name"), reqInt("delay")) }
    }
}

data class DelayResponse(val delays: List<Delay>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("delays", objectArray(delays.map { it.toJson() }))
        put("ok", true)
    }

    companion object {
        fun fromJson(e: JsonElement) =
            DelayResponse(Fields(e.asObject("body")).objects("delays").map(Delay::fromJson))
    }
}

// --- mode (§8.10) ------------------------------------------------------------------------------

object Modes {
    const val RULE = "rule"
    const val GLOBAL = "global"
    const val DIRECT = "direct"
    val ALL = listOf(RULE, GLOBAL, DIRECT)
}

data class ModeState(val mode: String, val modes: List<String>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        put("modes", stringArray(modes))
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run { ModeState(reqStr("mode"), strings("modes")) }
    }
}

data class ModeRequest(val mode: String?) {
    fun toJson(): JsonObject = buildJsonObject { mode?.let { put("mode", it) } }

    companion object {
        fun fromJson(e: JsonElement) = ModeRequest(Fields(e.asObject("body")).str("mode"))
    }
}

data class ModeResponse(val mode: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode)
        put("ok", true)
    }

    companion object {
        fun fromJson(e: JsonElement) = ModeResponse(Fields(e.asObject("body")).reqStr("mode"))
    }
}

// --- profiles (§8.11–§8.13) --------------------------------------------------------------------

/** Subscription traffic info in bytes; [expire] in unix seconds, 0 = never/unknown. */
data class Usage(val upload: Long, val download: Long, val total: Long, val expire: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("download", download)
        put("expire", expire)
        put("total", total)
        put("upload", upload)
    }

    companion object {
        fun fromJson(o: JsonObject) = Fields(o).run {
            Usage(reqLong("upload"), reqLong("download"), reqLong("total"), reqLong("expire"))
        }
    }
}

data class ProfileInfo(
    val id: String,
    val name: String,
    val type: String,
    val updatedAt: Long,
    val usage: Usage? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("type", type)
        put("updatedAt", updatedAt)
        usage?.let { put("usage", it.toJson()) }
    }

    companion object {
        const val REMOTE = "remote"
        const val LOCAL = "local"

        fun fromJson(o: JsonObject) = Fields(o).run {
            ProfileInfo(reqStr("id"), reqStr("name"), reqStr("type"), reqLong("updatedAt"), obj("usage")?.let(Usage::fromJson))
        }
    }
}

data class ProfileList(val active: String, val profiles: List<ProfileInfo>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("active", active)
        put("profiles", objectArray(profiles.map { it.toJson() }))
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run {
            ProfileList(reqStr("active"), objects("profiles").map(ProfileInfo::fromJson))
        }
    }
}

/** For update an absent id means "all remote profiles". */
data class ProfileRequest(val id: String?) {
    fun toJson(): JsonObject = buildJsonObject { id?.takeIf { it.isNotEmpty() }?.let { put("id", it) } }

    companion object {
        fun fromJson(e: JsonElement) = ProfileRequest(Fields(e.asObject("body")).str("id"))
    }
}

data class ProfileSelectResponse(val active: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("active", active)
        put("ok", true)
    }

    companion object {
        fun fromJson(e: JsonElement) = ProfileSelectResponse(Fields(e.asObject("body")).reqStr("active"))
    }
}

object OkResponse {
    fun toJson(): JsonObject = buildJsonObject { put("ok", true) }

    fun fromJson(e: JsonElement): OkResponse {
        if (Fields(e.asObject("body")).reqBool("ok") != true) throw ControlDecodeException("ok must be true")
        return this
    }
}

// --- providers (§8.14) -------------------------------------------------------------------------

data class ProviderInfo(
    val name: String,
    val kind: String,
    val vehicle: String,
    val count: Int,
    val updatedAt: Long,
    val usage: Usage? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("count", count)
        put("kind", kind)
        put("name", name)
        put("updatedAt", updatedAt)
        usage?.let { put("usage", it.toJson()) }
        put("vehicle", vehicle)
    }

    companion object {
        const val PROXY = "proxy"
        const val RULE = "rule"

        fun fromJson(o: JsonObject) = Fields(o).run {
            ProviderInfo(
                reqStr("name"), reqStr("kind"), reqStr("vehicle"), reqInt("count"), reqLong("updatedAt"),
                obj("usage")?.let(Usage::fromJson),
            )
        }
    }
}

data class ProviderList(val providers: List<ProviderInfo>) {
    fun toJson(): JsonObject = buildJsonObject { put("providers", objectArray(providers.map { it.toJson() })) }

    companion object {
        fun fromJson(e: JsonElement) =
            ProviderList(Fields(e.asObject("body")).objects("providers").map(ProviderInfo::fromJson))
    }
}

data class ProviderRequest(val kind: String?, val name: String?) {
    fun toJson(): JsonObject = buildJsonObject {
        kind?.let { put("kind", it) }
        name?.let { put("name", it) }
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run { ProviderRequest(str("kind"), str("name")) }
    }
}

// --- connections & traffic (§8.15–§8.16) -------------------------------------------------------

data class ConnectionInfo(
    val id: String,
    val host: String,
    val network: String,
    val process: String,
    val rule: String,
    val chains: List<String>,
    val upload: Long,
    val download: Long,
    val start: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("chains", stringArray(chains))
        put("download", download)
        put("host", host)
        put("id", id)
        put("network", network)
        put("process", process)
        put("rule", rule)
        put("start", start)
        put("upload", upload)
    }

    companion object {
        fun fromJson(o: JsonObject) = Fields(o).run {
            ConnectionInfo(
                reqStr("id"), reqStr("host"), reqStr("network"), reqStr("process"), reqStr("rule"),
                strings("chains"), reqLong("upload"), reqLong("download"), reqLong("start"),
            )
        }
    }
}

data class ConnectionList(val uploadTotal: Long, val downloadTotal: Long, val connections: List<ConnectionInfo>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("connections", objectArray(connections.map { it.toJson() }))
        put("downloadTotal", downloadTotal)
        put("uploadTotal", uploadTotal)
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run {
            ConnectionList(reqLong("uploadTotal"), reqLong("downloadTotal"), objects("connections").map(ConnectionInfo::fromJson))
        }
    }
}

/** An absent id closes every connection. */
data class CloseRequest(val id: String?) {
    fun toJson(): JsonObject = buildJsonObject { id?.takeIf { it.isNotEmpty() }?.let { put("id", it) } }

    companion object {
        fun fromJson(e: JsonElement) = CloseRequest(Fields(e.asObject("body")).str("id"))
    }
}

data class CloseResponse(val closed: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("closed", closed)
        put("ok", true)
    }

    companion object {
        fun fromJson(e: JsonElement) = CloseResponse(Fields(e.asObject("body")).reqInt("closed"))
    }
}

/** Rates in bytes/s, totals in bytes. */
data class Traffic(val up: Long, val down: Long, val upTotal: Long, val downTotal: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("down", down)
        put("downTotal", downTotal)
        put("up", up)
        put("upTotal", upTotal)
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run {
            Traffic(reqLong("up"), reqLong("down"), reqLong("upTotal"), reqLong("downTotal"))
        }
    }
}

// --- logs (§8.17) — modelled for the vectors; ClashFest does not advertise `logs` yet ----------

data class LogLine(val time: Long, val level: String, val msg: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("level", level)
        put("msg", msg)
        put("time", time)
    }

    companion object {
        val LEVELS = listOf("error", "warning", "info", "debug")

        fun fromJson(o: JsonObject) = Fields(o).run { LogLine(reqLong("time"), reqStr("level"), reqStr("msg")) }
    }
}

data class LogList(val lines: List<LogLine>) {
    fun toJson(): JsonObject = buildJsonObject { put("lines", objectArray(lines.map { it.toJson() })) }

    companion object {
        fun fromJson(e: JsonElement) = LogList(Fields(e.asObject("body")).objects("lines").map(LogLine::fromJson))
    }
}

// --- settings & actions (§8.18–§8.19) ----------------------------------------------------------

/** [value] is a JSON boolean for [BOOL] and one of [options] for [ENUM]. */
data class Setting(
    val key: String,
    val type: String,
    val value: JsonPrimitive,
    val options: List<String> = emptyList(),
    val label: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("key", key)
        label?.takeIf { it.isNotEmpty() }?.let { put("label", it) }
        if (options.isNotEmpty()) put("options", stringArray(options))
        put("type", type)
        put("value", value)
    }

    /** Null when [candidate] fits this setting's type and options, otherwise why it does not. */
    fun rejectValue(candidate: JsonElement?): String? = when (type) {
        BOOL -> if (candidate is JsonPrimitive && !candidate.isString && candidate.booleanOrNull != null) null
        else "value of $key must be a boolean"
        ENUM -> if (candidate is JsonPrimitive && candidate.isString && candidate.content in options) null
        else "value of $key must be one of ${options.joinToString(", ")}"
        else -> "setting $key has an unknown type"
    }

    companion object {
        const val BOOL = "bool"
        const val ENUM = "enum"

        fun fromJson(o: JsonObject) = Fields(o).run {
            Setting(
                key = reqStr("key"),
                type = reqStr("type"),
                value = raw("value") as? JsonPrimitive ?: throw ControlDecodeException("value is required"),
                options = strings("options"),
                label = str("label"),
            )
        }
    }
}

data class SettingList(val settings: List<Setting>) {
    fun toJson(): JsonObject = buildJsonObject { put("settings", objectArray(settings.map { it.toJson() })) }

    companion object {
        fun fromJson(e: JsonElement) = SettingList(Fields(e.asObject("body")).objects("settings").map(Setting::fromJson))
    }
}

data class SettingRequest(val key: String?, val value: JsonElement?) {
    fun toJson(): JsonObject = buildJsonObject {
        key?.let { put("key", it) }
        value?.let { put("value", it) }
    }

    companion object {
        fun fromJson(e: JsonElement) = Fields(e.asObject("body")).run { SettingRequest(str("key"), raw("value")) }
    }
}

data class SettingResponse(val setting: Setting) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", true)
        put("setting", setting.toJson())
    }

    companion object {
        fun fromJson(e: JsonElement) = SettingResponse(
            Setting.fromJson(Fields(e.asObject("body")).obj("setting") ?: throw ControlDecodeException("setting is required")),
        )
    }
}

data class ActionInfo(val id: String, val label: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        label?.takeIf { it.isNotEmpty() }?.let { put("label", it) }
    }

    companion object {
        fun fromJson(o: JsonObject) = Fields(o).run { ActionInfo(reqStr("id"), str("label")) }
    }
}

data class ActionList(val actions: List<ActionInfo>) {
    fun toJson(): JsonObject = buildJsonObject { put("actions", objectArray(actions.map { it.toJson() })) }

    companion object {
        fun fromJson(e: JsonElement) = ActionList(Fields(e.asObject("body")).objects("actions").map(ActionInfo::fromJson))
    }
}

data class ActionRequest(val action: String?) {
    fun toJson(): JsonObject = buildJsonObject { action?.let { put("action", it) } }

    companion object {
        fun fromJson(e: JsonElement) = ActionRequest(Fields(e.asObject("body")).str("action"))
    }
}

// --- events (§9.2) -----------------------------------------------------------------------------

object Events {
    const val STATUS = "status"
    const val CHANGED = "changed"
    const val TRAFFIC = "traffic"
    const val LOG = "log"

    const val TOPIC_TRAFFIC = "traffic"
    const val TOPIC_LOGS = "logs"

    /** Heartbeat comment line; controllers ignore lines starting with `:`. */
    const val HEARTBEAT = ": ping\n\n"

    /** One SSE frame: `event: <type>\ndata: <canonical JSON>\n\n` (canonical JSON has no raw newline). */
    fun frame(type: String, data: JsonObject): String = "event: $type\ndata: ${CanonicalJson.encode(data)}\n\n"
}

data class Changed(val resource: String) {
    fun toJson(): JsonObject = buildJsonObject { put("resource", resource) }

    companion object {
        const val PROXIES = "proxies"
        const val MODE = "mode"
        const val PROFILES = "profiles"
        const val PROVIDERS = "providers"
        const val CONNECTIONS = "connections"
        const val SETTINGS = "settings"

        fun fromJson(e: JsonElement) = Changed(Fields(e.asObject("body")).reqStr("resource"))
    }
}
