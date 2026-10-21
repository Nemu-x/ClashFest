package com.github.kr328.clash.companion.controller

import com.github.kr328.clash.companion.protocol.ActionInfo
import com.github.kr328.clash.companion.protocol.ActionList
import com.github.kr328.clash.companion.protocol.ActionRequest
import com.github.kr328.clash.companion.protocol.CloseRequest
import com.github.kr328.clash.companion.protocol.CloseResponse
import com.github.kr328.clash.companion.protocol.ConnectionList
import com.github.kr328.clash.companion.protocol.ControlDecodeException
import com.github.kr328.clash.companion.protocol.Delay
import com.github.kr328.clash.companion.protocol.DelayRequest
import com.github.kr328.clash.companion.protocol.DelayResponse
import com.github.kr328.clash.companion.protocol.Events
import com.github.kr328.clash.companion.protocol.Fingerprint
import com.github.kr328.clash.companion.protocol.ModeRequest
import com.github.kr328.clash.companion.protocol.ModeResponse
import com.github.kr328.clash.companion.protocol.ModeState
import com.github.kr328.clash.companion.protocol.ProfileList
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
import com.github.kr328.clash.companion.protocol.Traffic
import com.github.kr328.clash.companion.protocol.UnfixRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Error surfaced from the gateway's uniform error envelope (PROTOCOL.md §5.5), or derived from the
 * HTTP status when the body carries none. [code] is the machine code (`not_found`, `bad_request`,
 * …); `bad_response` means the agent answered with something this controller cannot decode.
 */
class CompanionError(val code: String, message: String, val httpStatus: Int = 0) : Exception(message)

/**
 * Pinned-TLS bearer client for one paired agent (PROTOCOL.md §5.2). Validates the connection
 * solely by `SHA-256(DER(leaf))` against the pinned `fp` (no CA chain, no hostname check) and
 * attaches the bearer token plus this controller's identity headers on every request.
 *
 * Every remote button of §8.6–§8.19 has a typed call here; the caller hides buttons whose
 * capability the agent does not advertise. Blocking I/O — call from a background dispatcher.
 */
class CompanionClient internal constructor(
    private val base: String,
    private val token: String,
    private val selfId: String,
    private val selfName: String,
    private val client: OkHttpClient,
) {
    constructor(agent: ControllerStore.PairedAgent, selfId: String, selfName: String) : this(
        base = "https://${bracket(agent.host)}:${agent.port}",
        token = agent.token,
        selfId = selfId,
        selfName = selfName,
        client = buildPinnedClient(agent.fp),
    )

    /** Calls that wait on the agent's own network work (delay tests, subscription fetches). */
    private val slowClient by lazy { client.newBuilder().readTimeout(SLOW_READ_SECONDS, TimeUnit.SECONDS).build() }

    /** The event stream: heartbeats arrive at least every 30 s (§9.2), so a silent minute is a dead link. */
    private val streamClient by lazy {
        client.newBuilder().readTimeout(STREAM_READ_SECONDS, TimeUnit.SECONDS).build()
    }

    // --- P1 (§8.1–§8.5, §7.4) ----------------------------------------------------------------

    fun status(): Status = decode(request("GET", "/v1/status"), Status::fromJson)

    /** Returns the resulting power state. */
    fun power(action: String): String {
        val obj = request("POST", "/v1/power", buildJsonObject { put("action", action) })
        return obj["power"]?.jsonPrimitive?.content ?: action
    }

    fun subscription(url: String, name: String) {
        request("POST", "/v1/subscription", buildJsonObject { put("url", url); put("name", name) })
    }

    fun rename(name: String) {
        request("POST", "/v1/rename", buildJsonObject { put("name", name) })
    }

    /** Revokes this controller's own pairing on the agent (§7.4). */
    fun unpair() {
        request("POST", "/v1/unpair", JsonObject(emptyMap()))
    }

    // --- proxies (§8.7–§8.9) -----------------------------------------------------------------

    fun proxies(): ProxyList = decode(request("GET", "/v1/proxies"), ProxyList::fromJson)

    fun select(group: String, node: String): SelectResponse =
        decode(request("POST", "/v1/proxies/select", SelectRequest(group, node).toJson()), SelectResponse::fromJson)

    fun unfix(group: String): SelectResponse =
        decode(request("POST", "/v1/proxies/unfix", UnfixRequest(group).toJson()), SelectResponse::fromJson)

    /** Tests [node] in [group], or the whole group when [node] is null. */
    fun delay(group: String, node: String? = null, url: String? = null, timeoutMs: Int = 0): List<Delay> =
        decode(
            request("POST", "/v1/proxies/delay", DelayRequest(group, node, url, timeoutMs).toJson(), slowClient),
            DelayResponse::fromJson,
        ).delays

    // --- mode (§8.10) ------------------------------------------------------------------------

    fun mode(): ModeState = decode(request("GET", "/v1/mode"), ModeState::fromJson)

    /** Returns the mode now in effect. */
    fun setMode(mode: String): String =
        decode(request("POST", "/v1/mode", ModeRequest(mode).toJson()), ModeResponse::fromJson).mode

    // --- profiles (§8.11–§8.12) --------------------------------------------------------------

    fun profiles(): ProfileList = decode(request("GET", "/v1/profiles"), ProfileList::fromJson)

    /** Returns the now-active profile id. */
    fun selectProfile(id: String): String =
        decode(request("POST", "/v1/profiles/select", ProfileRequest(id).toJson()), ProfileSelectResponse::fromJson).active

    /** Refreshes one remote profile, or every remote profile when [id] is null. */
    fun updateProfile(id: String?) {
        request("POST", "/v1/profiles/update", ProfileRequest(id).toJson(), slowClient)
    }

    // --- providers (§8.14) -------------------------------------------------------------------

    fun providers(): List<ProviderInfo> = decode(request("GET", "/v1/providers"), ProviderList::fromJson).providers

    fun updateProvider(kind: String, name: String) {
        request("POST", "/v1/providers/update", ProviderRequest(kind, name).toJson(), slowClient)
    }

    fun healthCheckProvider(name: String) {
        request("POST", "/v1/providers/healthcheck", ProviderRequest(ProviderInfo.PROXY, name).toJson(), slowClient)
    }

    // --- connections & traffic (§8.15–§8.16) -------------------------------------------------

    fun connections(): ConnectionList = decode(request("GET", "/v1/connections"), ConnectionList::fromJson)

    /** Closes one connection, or every connection when [id] is null; returns how many closed. */
    fun closeConnections(id: String?): Int =
        decode(request("POST", "/v1/connections/close", CloseRequest(id).toJson()), CloseResponse::fromJson).closed

    fun traffic(): Traffic = decode(request("GET", "/v1/traffic"), Traffic::fromJson)

    // --- settings & actions (§8.18–§8.19) ----------------------------------------------------

    /** The settings this controller can show; unknown types are dropped (§8.3 forward compat). */
    fun settings(): List<RemoteSetting> =
        decode(request("GET", "/v1/settings"), SettingList::fromJson).settings.mapNotNull(RemoteSetting::from)

    /** Returns the setting's new state (null if the agent answered with a type this controller cannot show). */
    fun setSetting(key: String, value: SettingValue): RemoteSetting? =
        decode(request("POST", "/v1/settings", SettingRequest(key, value.toJson()).toJson()), SettingResponse::fromJson)
            .setting.let(RemoteSetting::from)

    fun actions(): List<ActionInfo> = decode(request("GET", "/v1/actions"), ActionList::fromJson).actions

    fun runAction(id: String) {
        request("POST", "/v1/actions", ActionRequest(id).toJson(), slowClient)
    }

    // --- events (§9.2) -----------------------------------------------------------------------

    /** A prepared `GET /v1/events` call; [cancel] from any thread ends a running [read]. */
    class EventStream internal constructor(private val call: Call) {
        fun cancel() = call.cancel()

        /**
         * Executes the stream and blocks, handing every decoded event to [onEvent], until the agent
         * ends it (returns normally) or it fails (throws; [CompanionError] when the agent refused
         * it). [onOpen] runs once the agent accepted the stream, before the first event. Unknown
         * event types and heartbeats are skipped (§9.2).
         */
        fun read(onOpen: () -> Unit = {}, onEvent: (CompanionEvent) -> Unit) {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw errorFrom(resp.code, resp.body?.string().orEmpty())
                }
                onOpen()
                val source = resp.body?.source() ?: return
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: return
                    val frame = parser.line(line) ?: continue
                    CompanionEvent.decode(frame)?.let(onEvent)
                }
            }
        }
    }

    /** Prepares the event stream; only the `traffic` and `logs` topics are requested (§9.2). */
    fun events(topics: List<String> = emptyList()): EventStream {
        val query = topics.filter { it == Events.TOPIC_TRAFFIC || it == Events.TOPIC_LOGS }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?.let { "?topics=" + URLEncoder.encode(it, "UTF-8") }
            .orEmpty()
        return EventStream(
            streamClient.newCall(
                authorized("/v1/events$query").header("Accept", "text/event-stream").get().build(),
            ),
        )
    }

    // --- plumbing ----------------------------------------------------------------------------

    private fun authorized(path: String): Request.Builder = Request.Builder()
        .url(base + path)
        .header("Authorization", "Bearer $token")
        .header("X-Clashctl-Id", selfId)
        .header("X-Clashctl-Name", selfName)

    private fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        http: OkHttpClient = client,
    ): JsonObject {
        val builder = authorized(path)
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, (body?.toString() ?: "{}").toRequestBody(JSON_MEDIA))
        }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw errorFrom(resp.code, text)
            val element = parseOrNull(text)
            return element as? JsonObject
                ?: throw CompanionError(BAD_RESPONSE, "agent sent a non-object body", resp.code)
        }
    }

    private fun <T> decode(obj: JsonObject, decoder: (JsonElement) -> T): T = try {
        decoder(obj)
    } catch (e: ControlDecodeException) {
        throw CompanionError(BAD_RESPONSE, e.message ?: "undecodable response")
    }

    internal companion object {
        const val BAD_RESPONSE = "bad_response"
        private const val SLOW_READ_SECONDS = 60L
        private const val STREAM_READ_SECONDS = 75L

        val JSON_MEDIA = "application/json".toMediaType()

        /** HTTP status → §5.5 code, for error responses that carry no envelope. */
        private val STATUS_CODES = mapOf(
            400 to "bad_request",
            401 to "unauthorized",
            403 to "forbidden",
            404 to "not_found",
            409 to "version_unsupported",
            429 to "pin_rate_limited",
            500 to "internal",
            502 to "core_unavailable",
        )

        /** Maps a non-2xx response to its [CompanionError]: the envelope's code when present (§5.5). */
        fun errorFrom(status: Int, body: String): CompanionError {
            val error = (parseOrNull(body) as? JsonObject)?.get("error") as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            val message = (error?.get("message") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            return CompanionError(
                code ?: STATUS_CODES[status] ?: "http_$status",
                message ?: "HTTP $status",
                status,
            )
        }

        private fun parseOrNull(text: String): JsonElement? =
            if (text.isBlank()) null else runCatching { Json.parseToJsonElement(text) }.getOrNull()

        fun bracket(host: String): String = if (host.contains(':')) "[$host]" else host

        fun buildPinnedClient(fp: String): OkHttpClient {
            val tm = PinnedTrustManager(fp)
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(tm), SecureRandom())
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, tm)
                .hostnameVerifier { _, _ -> true } // self-signed: identity is the pinned fp, not the name
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    /** Trusts exactly the certificate whose DER SHA-256 equals the pinned [fpHex] (§5.2). */
    private class PinnedTrustManager(private val fpHex: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("no certificate presented")
            val actual = Fingerprint.of(leaf)
            if (!actual.equals(fpHex, ignoreCase = true)) {
                throw CertificateException("pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/** True when [e] means the agent could not be reached at all (as opposed to answering with an error). */
fun isUnreachable(e: Throwable): Boolean = e is IOException
