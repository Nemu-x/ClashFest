package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.companion.CompanionPin
import com.github.kr328.clash.companion.CompanionStore
import com.github.kr328.clash.companion.protocol.ActionList
import com.github.kr328.clash.companion.protocol.ActionRequest
import com.github.kr328.clash.companion.protocol.CanonicalJson
import com.github.kr328.clash.companion.protocol.Capabilities
import com.github.kr328.clash.companion.protocol.CloseRequest
import com.github.kr328.clash.companion.protocol.CloseResponse
import com.github.kr328.clash.companion.protocol.ControlDecodeException
import com.github.kr328.clash.companion.protocol.DelayRequest
import com.github.kr328.clash.companion.protocol.DelayResponse
import com.github.kr328.clash.companion.protocol.Events
import com.github.kr328.clash.companion.protocol.Ids
import com.github.kr328.clash.companion.protocol.LogLine
import com.github.kr328.clash.companion.protocol.ModeRequest
import com.github.kr328.clash.companion.protocol.ModeResponse
import com.github.kr328.clash.companion.protocol.Modes
import com.github.kr328.clash.companion.protocol.OkResponse
import com.github.kr328.clash.companion.protocol.ProfileRequest
import com.github.kr328.clash.companion.protocol.ProfileSelectResponse
import com.github.kr328.clash.companion.protocol.ProviderInfo
import com.github.kr328.clash.companion.protocol.ProviderList
import com.github.kr328.clash.companion.protocol.ProviderRequest
import com.github.kr328.clash.companion.protocol.ProxyList
import com.github.kr328.clash.companion.protocol.SelectRequest
import com.github.kr328.clash.companion.protocol.SelectResponse
import com.github.kr328.clash.companion.protocol.SettingList
import com.github.kr328.clash.companion.protocol.SettingRequest
import com.github.kr328.clash.companion.protocol.SettingResponse
import com.github.kr328.clash.companion.protocol.Status
import com.github.kr328.clash.util.ChangeBus
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import javax.net.ssl.SSLServerSocketFactory

/**
 * The clashctl gateway (PROTOCOL.md §8) — the only thing exposed on the LAN. Serves the P1
 * app-only endpoints and the remote buttons (§8.6–§8.19) over pinned HTTPS, enforcing bearer auth
 * (§5.2) before any hook and a strict endpoint whitelist (§2). Everything is canonical JSON (§3.4);
 * errors use the uniform envelope (§5.4, §5.5).
 *
 * Check order for every button: auth → capability advertised (else `not_found`, no side effects)
 * → body/query validation (`bad_request`) → hook. `GET /v1/events` streams server-sent events
 * (§9.2) through [CompanionEventHub].
 *
 * @param port 0 binds an ephemeral port; read [getListeningPort] after [start] for the actual one.
 * @param remoteCapabilities the remote buttons this agent serves, in §8.3 order.
 */
class GatewayServer(
    port: Int,
    sslFactory: SSLServerSocketFactory?,
    private val pairingStore: Pairings,
    private val store: AgentIdentity,
    private val hooks: CompanionHooks,
    private val remoteCapabilities: List<String> = CLASHFEST_REMOTE_CAPABILITIES,
) : NanoHTTPD(port) {

    /** What the gateway needs from the pairing store (§7); [PairingStore] in the app. */
    interface Pairings {
        fun authenticate(token: String): PairingStore.Pairing?
        fun recordController(tokenHash: String, controllerId: String?, label: String?)
        fun issueToken(): String
        fun revoke(tokenHash: String)

        /** Whether a pairing with [tokenHash] still exists (event streams re-check it). */
        fun isPaired(tokenHash: String): Boolean
    }

    /** The agent's own identity (§3.1, §8.5); [CompanionStore] in the app. */
    interface AgentIdentity {
        val deviceId: String
        val displayName: String
    }

    private val advertised: List<String> =
        (P1_CAPABILITIES + remoteCapabilities + Capabilities.EVENTS)
            .distinct()
            .sortedBy { Capabilities.ORDER.indexOf(it) }

    internal val events = CompanionEventHub(
        status = ::buildStatus,
        settingsFingerprint = {
            if (Capabilities.SETTINGS in advertised) CanonicalJson.encode(SettingList(hooks.settings()).toJson()) else null
        },
        traffic = if (Capabilities.TRAFFIC in advertised) hooks::traffic else null,
        isAuthorized = { hash -> pairingStore.isPaired(hash) },
    )

    private val routes: Map<String, Route> = listOf(
        Route(Method.GET, "/v1/proxies", Capabilities.PROXIES, ::proxies),
        Route(Method.POST, "/v1/proxies/select", Capabilities.SELECT, ::select),
        Route(Method.POST, "/v1/proxies/unfix", Capabilities.UNFIX) { _, _ -> unreachable() },
        Route(Method.POST, "/v1/proxies/delay", Capabilities.DELAY, ::delay),
        Route(Method.GET, "/v1/mode", Capabilities.MODE) { _, _ -> hook { ok(hooks.mode().toJson()) } },
        Route(Method.POST, "/v1/mode", Capabilities.MODE, ::setMode),
        Route(Method.GET, "/v1/profiles", Capabilities.PROFILES) { _, _ -> hook { ok(hooks.profiles().toJson()) } },
        Route(Method.POST, "/v1/profiles/select", Capabilities.PROFILES, ::selectProfile),
        Route(Method.POST, "/v1/profiles/update", Capabilities.PROFILE_UPDATE, ::updateProfile),
        Route(Method.POST, "/v1/profiles/delete", Capabilities.PROFILE_DELETE) { _, _ -> unreachable() },
        Route(Method.GET, "/v1/providers", Capabilities.PROVIDERS) { _, _ ->
            hook { ok(ProviderList(hooks.providers()).toJson()) }
        },
        Route(Method.POST, "/v1/providers/update", Capabilities.PROVIDERS, ::updateProvider),
        Route(Method.POST, "/v1/providers/healthcheck", Capabilities.PROVIDERS, ::healthCheckProvider),
        Route(Method.GET, "/v1/connections", Capabilities.CONNECTIONS) { _, _ -> hook { ok(hooks.connections().toJson()) } },
        Route(Method.POST, "/v1/connections/close", Capabilities.CONNECTIONS, ::closeConnections),
        Route(Method.GET, "/v1/traffic", Capabilities.TRAFFIC) { _, _ -> hook { ok(hooks.traffic().toJson()) } },
        Route(Method.GET, "/v1/logs", Capabilities.LOGS) { _, _ -> unreachable() },
        Route(Method.GET, "/v1/settings", Capabilities.SETTINGS) { _, _ ->
            hook { ok(SettingList(hooks.settings()).toJson()) }
        },
        Route(Method.POST, "/v1/settings", Capabilities.SETTINGS, ::setSetting),
        Route(Method.GET, "/v1/actions", Capabilities.ACTIONS) { _, _ -> hook { ok(ActionList(hooks.actions()).toJson()) } },
        Route(Method.POST, "/v1/actions", Capabilities.ACTIONS, ::runAction),
        Route(Method.GET, "/v1/events", Capabilities.EVENTS, ::events),
    ).associateBy { "${it.method.name} ${it.path}" }

    init {
        if (sslFactory != null) makeSecure(sslFactory, null)
    }

    override fun start(timeout: Int, daemon: Boolean) {
        events.start()
        CompanionAgent.onRevoked = events::dropToken
        super.start(timeout, daemon)
    }

    override fun stop() {
        CompanionAgent.onRevoked = null
        events.stop()
        super.stop()
    }

    /** Chunked SSE must reach the socket frame by frame; gzip would buffer it. */
    override fun useGzipWhenAccepted(r: Response): Boolean =
        r.mimeType?.startsWith(MIME_EVENT_STREAM) != true && super.useGzipWhenAccepted(r)

    public override fun serve(session: IHTTPSession): Response {
        val tracked = BodyTrackingSession(session)
        val response = try {
            handle(tracked)
        } catch (t: Throwable) {
            Log.w("Companion gateway error: ${t.message}")
            error(Response.Status.INTERNAL_ERROR, "internal", "unexpected agent error")
        }
        // Early answers (401, not_found, unpair, oversize) never read the body; leftover bytes on a
        // keep-alive connection would be parsed as the next request. Drain it, or close the link.
        if (!tracked.bodyConsumed && !drainBody(session)) {
            response.closeConnection(true)
            response.addHeader("Connection", "close")
        }
        return response
    }

    /** Marks whether a handler consumed the request body through [parseBody]. */
    private class BodyTrackingSession(private val inner: IHTTPSession) : IHTTPSession by inner {
        var bodyConsumed = false
            private set

        override fun parseBody(files: MutableMap<String, String>?) {
            bodyConsumed = true
            inner.parseBody(files)
        }
    }

    /**
     * Discards an unread request body so the connection can be reused. Returns false when that is
     * not possible (chunked or unknown framing, over the largest accepted body, or a short read).
     */
    private fun drainBody(session: IHTTPSession): Boolean {
        if (session.headers["transfer-encoding"] != null) return false
        val raw = session.headers["content-length"] ?: return true
        val length = raw.trim().toLongOrNull() ?: return false
        if (length !in 0..MAX_SUBSCRIPTION_BODY_BYTES) return false
        return try {
            val input = session.inputStream
            val buffer = ByteArray(DRAIN_BUFFER_BYTES)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) return false
                remaining -= read
            }
            true
        } catch (e: Exception) {
            Log.d("Companion: request body drain failed: ${e.message}")
            false
        }
    }

    private fun handle(session: IHTTPSession): Response {
        // PIN-assisted pairing (§6.4) is the ONE endpoint without bearer auth — it issues the token.
        // Gated instead by the on-screen PIN (single-use, expiring, rate-limited).
        if (session.method == Method.POST && session.uri == "/v1/pair") return pair(session)

        // Bearer auth (§5.2) — applies to every other endpoint, before any hook or routing.
        val header = session.headers["authorization"].orEmpty()
        val token = if (header.startsWith(BEARER)) header.substring(BEARER.length) else null
        val pairing = token?.let { pairingStore.authenticate(it) }
            ?: return error(Response.Status.UNAUTHORIZED, "unauthorized", "missing or invalid token")

        // Best-effort: record the controller's advertised identity for the management UI.
        pairingStore.recordController(
            pairing.tokenHash,
            session.headers["x-clashctl-id"],
            session.headers["x-clashctl-name"],
        )

        val uri = session.uri
        val route = routes["${session.method?.name} $uri"]
        return when {
            route != null ->
                if (route.capability in advertised) {
                    route.handler(session, pairing)
                } else {
                    // §8.6: an unadvertised button answers not_found after auth, with no side effects.
                    error(Response.Status.NOT_FOUND, "not_found", "capability not supported: ${route.capability}")
                }
            session.method == Method.GET && uri == "/v1/status" -> ok(buildStatus().toJson())
            session.method == Method.POST && uri == "/v1/power" -> power(session)
            session.method == Method.POST && uri == "/v1/subscription" -> subscription(session)
            session.method == Method.POST && uri == "/v1/rename" -> rename(session)
            // §7.4: giving up control needs a valid token and nothing else.
            session.method == Method.POST && uri == "/v1/unpair" -> unpair(pairing)
            // `core` (§9.1) is not advertised, so per §8.6 its paths answer not_found after auth.
            uri.startsWith("/v1/core") || uri == "/upgrade" ->
                error(Response.Status.NOT_FOUND, "not_found", "capability not supported: ${Capabilities.CORE}")
            else -> error(Response.Status.NOT_FOUND, "not_found", "unknown endpoint")
        }
    }

    // --- P1 ------------------------------------------------------------------------------------

    /** §8.1. A failing summary only drops the optional field; it never fails the status call. */
    private fun buildStatus(): Status {
        val summary = try {
            hooks.summary()
        } catch (e: Exception) {
            Log.d("Companion: summary unavailable: ${e.message}")
            null
        }
        return Status(
            app = CompanionStore.APP_ID,
            capabilities = advertised,
            id = store.deviceId,
            name = store.displayName,
            power = hooks.powerState(),
            ver = PROTOCOL_VERSION,
            summary = summary,
        )
    }

    private fun power(session: IHTTPSession): Response {
        val body = parseBody(session, MAX_JSON_BODY_BYTES) ?: return badRequest("malformed body")
        val action = body["action"]?.jsonPrimitive?.content
        if (action !in setOf("on", "off", "toggle")) return badRequest("invalid action")
        return try {
            val resulting = hooks.power(action!!)
            ChangeBus.emit(ChangeBus.Change.Power, remote = true)
            ok(buildJsonObject { put("ok", true); put("power", resulting) })
        } catch (e: CompanionHooks.PowerUnavailable) {
            error(Response.Status.INTERNAL_ERROR, "internal", e.message ?: "power unavailable")
        }
    }

    private fun subscription(session: IHTTPSession): Response {
        val body = parseBody(session, MAX_SUBSCRIPTION_BODY_BYTES) ?: return badRequest("malformed body")
        val url = body["url"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val payload = body["payload"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val name = body["name"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PROFILE_NAME
        // Exactly one of url/payload (§8.4).
        if ((url == null) == (payload == null)) return badRequest("exactly one of url/payload required")
        return try {
            hooks.importSubscription(url, payload, name)
            ChangeBus.emit(ChangeBus.Change.Profiles, remote = true)
            ok(buildJsonObject { put("ok", true) })
        } catch (e: CompanionHooks.PayloadUnsupported) {
            badRequest(e.message ?: "inline payload not supported")
        }
    }

    private fun rename(session: IHTTPSession): Response {
        val body = parseBody(session, MAX_JSON_BODY_BYTES) ?: return badRequest("malformed body")
        val name = body["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return badRequest("empty name")
        hooks.rename(name)
        ChangeBus.emit(ChangeBus.Change.Name, remote = true)
        return ok(buildJsonObject { put("name", name); put("ok", true) })
    }

    /** §7.4: revoke the calling controller's own pairing and close its event streams. */
    private fun unpair(pairing: PairingStore.Pairing): Response {
        pairingStore.revoke(pairing.tokenHash)
        events.dropToken(pairing.tokenHash)
        return ok(OkResponse.toJson())
    }

    /**
     * PIN-assisted pairing (§6.4). Body: `{"pin":"<code>","device":{"id":"<id>","name":"<name>"}}`.
     * On a correct PIN, issues and returns a bearer token bound to the controller; wrong PIN →
     * `pin_invalid` (403), too many attempts → `pin_rate_limited` (429).
     */
    private fun pair(session: IHTTPSession): Response {
        val body = parseBody(session, MAX_PAIR_BODY_BYTES) ?: return badRequest("malformed body")
        val pin = body["pin"]?.jsonPrimitive?.content ?: return badRequest("pin required")
        val device = body["device"]?.jsonObject
        val controllerId = device?.get("id")?.jsonPrimitive?.content
        val controllerName = device?.get("name")?.jsonPrimitive?.content

        return when (CompanionPin.verify(pin)) {
            CompanionPin.Result.OK -> {
                val token = pairingStore.issueToken()
                pairingStore.recordController(Ids.tokenHash(token), controllerId, controllerName)
                ok(buildJsonObject { put("ok", true); put("token", token) })
            }
            CompanionPin.Result.RATE_LIMITED ->
                error(429, "pin_rate_limited", "too many attempts")
            CompanionPin.Result.WRONG, CompanionPin.Result.EXPIRED ->
                error(Response.Status.FORBIDDEN, "pin_invalid", "wrong or expired pin")
        }
    }

    // --- remote buttons (§8.7–§8.19) -----------------------------------------------------------

    private fun proxies(session: IHTTPSession, pairing: PairingStore.Pairing): Response =
        hook { ok(ProxyList(hooks.proxyGroups()).toJson()) }

    private fun select(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val req = SelectRequest.fromJson(body)
        val group = req.group?.takeIf { it.isNotEmpty() }
        val node = req.node?.takeIf { it.isNotEmpty() }
        if (group == null || node == null) return@withBody badRequest("group and node are required")
        // The shared select path announces the change itself (ChangeBus.Proxies).
        hook { ok(SelectResponse(group, hooks.selectProxy(group, node)).toJson()) }
    }

    private fun delay(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val req = DelayRequest.fromJson(body)
        val group = req.group?.takeIf { it.isNotEmpty() } ?: return@withBody badRequest("group is required")
        val timeout = when {
            req.timeout == 0 -> DEFAULT_DELAY_TIMEOUT
            req.timeout < MIN_DELAY_TIMEOUT || req.timeout > MAX_DELAY_TIMEOUT ->
                return@withBody badRequest("timeout must be 0 or within 100..30000 ms")
            else -> req.timeout
        }
        val url = req.url?.takeIf { it.isNotEmpty() }
        if (url != null && !isHttpUrl(url)) return@withBody badRequest("url must be an absolute http(s) URL")
        hook {
            val delays = hooks.testDelay(group, req.node?.takeIf { it.isNotEmpty() }, url, timeout)
            ChangeBus.emit(ChangeBus.Change.Proxies, remote = true)
            ok(DelayResponse(delays).toJson())
        }
    }

    private fun setMode(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val mode = ModeRequest.fromJson(body).mode
        if (mode !in Modes.ALL) return@withBody badRequest("mode must be rule, global or direct")
        // The shared mode path announces the change itself (ChangeBus.Mode).
        hook { ok(ModeResponse(hooks.setMode(mode!!)).toJson()) }
    }

    private fun selectProfile(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val id = ProfileRequest.fromJson(body).id?.takeIf { it.isNotEmpty() }
            ?: return@withBody badRequest("id is required")
        hook {
            val active = hooks.selectProfile(id)
            ChangeBus.emit(ChangeBus.Change.Profiles, remote = true)
            ok(ProfileSelectResponse(active).toJson())
        }
    }

    private fun updateProfile(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val id = ProfileRequest.fromJson(body).id?.takeIf { it.isNotEmpty() }
        hook {
            hooks.updateProfile(id)
            ChangeBus.emit(ChangeBus.Change.Profiles, remote = true)
            ok(OkResponse.toJson())
        }
    }

    private fun updateProvider(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val req = ProviderRequest.fromJson(body)
        val name = req.name?.takeIf { it.isNotEmpty() }
        if (name == null || req.kind !in setOf(ProviderInfo.PROXY, ProviderInfo.RULE)) {
            return@withBody badRequest("name and kind (proxy or rule) are required")
        }
        hook {
            hooks.updateProvider(req.kind!!, name)
            ChangeBus.emit(ChangeBus.Change.Providers, remote = true)
            ok(OkResponse.toJson())
        }
    }

    private fun healthCheckProvider(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val req = ProviderRequest.fromJson(body)
        val name = req.name?.takeIf { it.isNotEmpty() }
        if (name == null || req.kind != ProviderInfo.PROXY) {
            return@withBody badRequest("name and kind (proxy) are required")
        }
        hook {
            hooks.healthCheckProvider(name)
            ChangeBus.emit(ChangeBus.Change.Proxies, remote = true)
            ok(OkResponse.toJson())
        }
    }

    private fun closeConnections(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val id = CloseRequest.fromJson(body).id?.takeIf { it.isNotEmpty() }
        hook {
            val closed = hooks.closeConnections(id)
            ChangeBus.emit(ChangeBus.Change.Connections, remote = true)
            ok(CloseResponse(closed).toJson())
        }
    }

    private fun setSetting(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val req = SettingRequest.fromJson(body)
        val key = req.key?.takeIf { it.isNotEmpty() } ?: return@withBody badRequest("key is required")
        hook {
            val current = hooks.settings().firstOrNull { it.key == key }
                ?: return@hook error(Response.Status.NOT_FOUND, "not_found", "unknown setting: $key")
            current.rejectValue(req.value)?.let { return@hook badRequest(it) }
            val updated = hooks.setSetting(key, req.value as JsonPrimitive)
            ChangeBus.emit(ChangeBus.Change.Settings, remote = true)
            ok(SettingResponse(updated).toJson())
        }
    }

    private fun runAction(session: IHTTPSession, pairing: PairingStore.Pairing): Response = withBody(session) { body ->
        val action = ActionRequest.fromJson(body).action?.takeIf { it.isNotEmpty() }
            ?: return@withBody badRequest("action is required")
        hook {
            if (hooks.actions().none { it.id == action }) {
                return@hook error(Response.Status.NOT_FOUND, "not_found", "unknown action: $action")
            }
            hooks.runAction(action)
            ok(OkResponse.toJson())
        }
    }

    /** §9.2: errors are plain JSON before the stream starts; then `text/event-stream`. */
    private fun events(session: IHTTPSession, pairing: PairingStore.Pairing): Response {
        val params = session.parameters.orEmpty()
        val topics = params["topics"].orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim() }
        val level = params["level"]?.firstOrNull().orEmpty()
        if (level.isNotEmpty() && level !in LogLine.LEVELS) {
            return badRequest("level must be one of ${LogLine.LEVELS.joinToString(", ")}")
        }
        val first = try {
            buildStatus()
        } catch (e: Exception) {
            return coreUnavailable(e.message ?: "status unavailable")
        }
        // The logs topic is ignored: `logs` is not advertised.
        val stream = events.open(pairing.tokenHash, Events.TOPIC_TRAFFIC in topics, first)
        return newChunkedResponse(Response.Status.OK, MIME_EVENT_STREAM, stream).apply {
            addHeader("Cache-Control", "no-cache")
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private class Route(
        val method: Method,
        val path: String,
        val capability: String,
        val handler: (IHTTPSession, PairingStore.Pairing) -> Response,
    )

    /** Capabilities ClashFest never advertises; their routes exist only to answer not_found. */
    private fun unreachable(): Response = error(Response.Status.NOT_FOUND, "not_found", "not supported")

    private inline fun withBody(session: IHTTPSession, block: (JsonObject) -> Response): Response {
        val body = parseBody(session, MAX_JSON_BODY_BYTES) ?: return badRequest("malformed body")
        return try {
            block(body)
        } catch (e: ControlDecodeException) {
            badRequest(e.message ?: "invalid body")
        }
    }

    /** Maps hook failures onto the §5.5 model. */
    private inline fun hook(block: () -> Response): Response = try {
        block()
    } catch (e: CompanionHooks.NotFound) {
        error(Response.Status.NOT_FOUND, "not_found", e.message ?: "not found")
    } catch (e: CompanionHooks.Refused) {
        badRequest(e.message ?: "refused")
    } catch (e: Exception) {
        Log.w("Companion hook failed: ${e.message}")
        coreUnavailable(e.message ?: "core unavailable")
    }

    private fun isHttpUrl(raw: String): Boolean = try {
        val uri = URI(raw)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    private fun parseBody(session: IHTTPSession, maxBytes: Long): JsonObject? {
        if (!isAcceptedContentLength(session.headers["content-length"], maxBytes)) return null
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val data = files["postData"] ?: return null
            if (!isAcceptedBodyText(data, maxBytes)) return null
            Json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun ok(obj: JsonObject): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, CanonicalJson.encode(obj))

    private fun badRequest(message: String): Response =
        error(Response.Status.BAD_REQUEST, "bad_request", message)

    private fun coreUnavailable(message: String): Response = error(502, "core_unavailable", message)

    private fun error(status: Response.Status, code: String, message: String): Response =
        newFixedLengthResponse(status, MIME_JSON, errorBody(code, message))

    /** For HTTP codes NanoHTTPD's [Response.Status] enum doesn't cover (e.g. 429, 502). */
    private fun error(httpCode: Int, code: String, message: String): Response {
        val status = object : Response.IStatus {
            override fun getRequestStatus(): Int = httpCode
            override fun getDescription(): String = "$httpCode $code"
        }
        return newFixedLengthResponse(status, MIME_JSON, errorBody(code, message))
    }

    private fun errorBody(code: String, message: String): String =
        CanonicalJson.encode(
            buildJsonObject {
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            },
        )

    companion object {
        private const val BEARER = "Bearer "
        private const val MIME_JSON = "application/json"
        private const val MIME_EVENT_STREAM = "text/event-stream"
        private const val PROTOCOL_VERSION = 1
        private const val DEFAULT_PROFILE_NAME = "Shared"
        private const val MAX_PAIR_BODY_BYTES = 16L * 1024L
        private const val MAX_JSON_BODY_BYTES = 64L * 1024L
        private const val MAX_SUBSCRIPTION_BODY_BYTES = 1024L * 1024L
        private const val DEFAULT_DELAY_TIMEOUT = 5000
        private const val MIN_DELAY_TIMEOUT = 100
        private const val MAX_DELAY_TIMEOUT = 30000
        private const val DRAIN_BUFFER_BYTES = 8 * 1024

        private val P1_CAPABILITIES = listOf(
            Capabilities.STATUS, Capabilities.POWER, Capabilities.SUBSCRIPTION, Capabilities.RENAME, Capabilities.UNPAIR,
        )

        /**
         * The remote buttons ClashFest has (§8.3 order). Not offered: `unfix` (the app has no such
         * button), `profile_delete` (no destructive remote operations), `logs` (later) and `core`.
         */
        val CLASHFEST_REMOTE_CAPABILITIES = listOf(
            Capabilities.PROXIES,
            Capabilities.SELECT,
            Capabilities.DELAY,
            Capabilities.MODE,
            Capabilities.PROFILES,
            Capabilities.PROFILE_UPDATE,
            Capabilities.PROVIDERS,
            Capabilities.CONNECTIONS,
            Capabilities.TRAFFIC,
            Capabilities.SETTINGS,
            Capabilities.ACTIONS,
        )

        internal fun isAcceptedContentLength(raw: String?, maxBytes: Long): Boolean {
            val length = raw?.trim()?.toLongOrNull() ?: return false
            return length in 0..maxBytes
        }

        internal fun isAcceptedBodyText(value: String, maxBytes: Long): Boolean =
            value.toByteArray(Charsets.UTF_8).size.toLong() <= maxBytes
    }
}
