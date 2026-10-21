package com.github.kr328.clash.companion.agent

import com.github.kr328.clash.companion.protocol.ActionInfo
import com.github.kr328.clash.companion.protocol.ConnectionList
import com.github.kr328.clash.companion.protocol.Delay
import com.github.kr328.clash.companion.protocol.ModeState
import com.github.kr328.clash.companion.protocol.Modes
import com.github.kr328.clash.companion.protocol.ProfileInfo
import com.github.kr328.clash.companion.protocol.ProfileList
import com.github.kr328.clash.companion.protocol.ProviderInfo
import com.github.kr328.clash.companion.protocol.ProxyGroup
import com.github.kr328.clash.companion.protocol.ProxyNode
import com.github.kr328.clash.companion.protocol.Setting
import com.github.kr328.clash.companion.protocol.Summary
import com.github.kr328.clash.companion.protocol.Traffic
import com.github.kr328.clash.companion.protocol.Ids
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream

internal const val GOOD_TOKEN = "good-token"

internal class FakePairings : GatewayServer.Pairings {
    val pairings = mutableListOf(PairingStore.Pairing(tokenHash = Ids.tokenHash(GOOD_TOKEN), pairedAt = 1L))
    val revoked = mutableListOf<String>()

    override fun authenticate(token: String): PairingStore.Pairing? =
        pairings.firstOrNull { it.tokenHash == Ids.tokenHash(token) }

    override fun recordController(tokenHash: String, controllerId: String?, label: String?) = Unit

    override fun issueToken(): String = error("not used")

    override fun revoke(tokenHash: String) {
        revoked += tokenHash
        pairings.removeAll { it.tokenHash == tokenHash }
    }

    override fun isPaired(tokenHash: String): Boolean = pairings.any { it.tokenHash == tokenHash }
}

internal class FakeIdentity : GatewayServer.AgentIdentity {
    override val deviceId: String = "AAECAwQFBgcICQoLDA0ODw"
    override val displayName: String = "Living Room TV"
}

/** Answers every button with fixed data and records the state-changing calls. */
internal open class FakeHooks : CompanionHooks {
    val calls = mutableListOf<String>()
    var mode = Modes.RULE

    override fun powerState(): String = "on"
    override fun power(action: String): String = "on".also { calls += "power:$action" }
    override fun importSubscription(url: String?, payload: String?, name: String) { calls += "import:$url" }
    override fun rename(name: String) { calls += "rename:$name" }

    override fun summary(): Summary = Summary(mode = mode, profile = "Main", proxy = "HK-01", appVersion = "1.0.0")

    override fun proxyGroups(): List<ProxyGroup> = listOf(
        ProxyGroup("Proxy", "Selector", "HK-01", "", true, listOf(ProxyNode("HK-01", "Vless", 64), ProxyNode("JP-01", "Vless", 0))),
    )

    override fun selectProxy(group: String, node: String): String {
        calls += "select:$group/$node"
        if (group != "Proxy") throw CompanionHooks.NotFound("unknown group: $group")
        return node
    }

    override fun testDelay(group: String, node: String?, url: String?, timeoutMs: Int): List<Delay> {
        calls += "delay:$group/$node/$url/$timeoutMs"
        return listOf(Delay("HK-01", 64))
    }

    override fun mode(): ModeState = ModeState(mode, listOf(Modes.RULE, Modes.GLOBAL))

    override fun setMode(mode: String): String {
        calls += "mode:$mode"
        if (mode == Modes.DIRECT) throw CompanionHooks.Refused("direct is not offered")
        this.mode = mode
        return mode
    }

    override fun profiles(): ProfileList = ProfileList("p-1", listOf(ProfileInfo("p-1", "Main", ProfileInfo.REMOTE, 0)))
    override fun selectProfile(id: String): String = id.also { calls += "profile:$id" }
    override fun updateProfile(id: String?) { calls += "update:$id" }
    override fun providers(): List<ProviderInfo> = emptyList()
    override fun updateProvider(kind: String, name: String) { calls += "provider:$kind/$name" }
    override fun healthCheckProvider(name: String) { calls += "providercheck:$name" }
    override fun connections(): ConnectionList = ConnectionList(0, 0, emptyList())
    override fun closeConnections(id: String?): Int = 3.also { calls += "close:$id" }
    override fun traffic(): Traffic = Traffic(1, 2, 3, 4)

    override fun settings(): List<Setting> = listOf(
        Setting("ipv6", Setting.BOOL, JsonPrimitive(false)),
        Setting("tun_stack", Setting.ENUM, JsonPrimitive("system"), listOf("system", "gvisor"), "Stack Mode"),
    )

    override fun setSetting(key: String, value: JsonPrimitive): Setting {
        calls += "setting:$key=$value"
        return settings().first { it.key == key }.copy(value = value)
    }

    override fun actions(): List<ActionInfo> = listOf(ActionInfo("healthcheck_all"), ActionInfo("reconnect", "Reconnect"))
    override fun runAction(id: String) { calls += "action:$id" }
}

internal fun gateway(
    hooks: CompanionHooks = FakeHooks(),
    pairings: FakePairings = FakePairings(),
    remote: List<String> = GatewayServer.CLASHFEST_REMOTE_CAPABILITIES,
) = GatewayServer(0, null, pairings, FakeIdentity(), hooks, remote)

/** A request as NanoHTTPD would hand it to [GatewayServer.serve]. */
internal class FakeSession(
    private val method: NanoHTTPD.Method,
    pathAndQuery: String,
    private val body: String? = null,
    token: String? = GOOD_TOKEN,
) : NanoHTTPD.IHTTPSession {
    private val uri = pathAndQuery.substringBefore('?')
    private val query = pathAndQuery.substringAfter('?', "")
    private val headers = buildMap {
        if (token != null) put("authorization", "Bearer $token")
        if (body != null) put("content-length", body.toByteArray(Charsets.UTF_8).size.toString())
    }

    override fun execute() = Unit
    override fun getCookies(): NanoHTTPD.CookieHandler? = null
    override fun getHeaders(): Map<String, String> = headers
    override fun getInputStream(): InputStream = ByteArrayInputStream(body.orEmpty().toByteArray())
    override fun getMethod(): NanoHTTPD.Method = method

    @Deprecated("NanoHTTPD API")
    override fun getParms(): Map<String, String> = getParameters().mapValues { it.value.first() }

    override fun getParameters(): Map<String, List<String>> =
        if (query.isEmpty()) {
            emptyMap()
        } else {
            query.split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
                .groupBy({ it.first }, { it.second })
        }

    override fun getQueryParameterString(): String = query
    override fun getUri(): String = uri

    override fun parseBody(files: MutableMap<String, String>) {
        if (body != null) files["postData"] = body
    }

    override fun getRemoteIpAddress(): String = "192.0.2.1"
    override fun getRemoteHostName(): String = "controller"
}

internal fun NanoHTTPD.Response.bodyText(): String = data.readBytes().toString(Charsets.UTF_8)

/** Reads one SSE frame (up to and including the blank line), or null at end of stream. */
internal fun InputStream.readFrame(): String? {
    val out = StringBuilder()
    val buf = ByteArray(1)
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val n = read(buf, 0, 1)
        if (n < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
        bytes.write(buf, 0, 1)
        val text = bytes.toString("UTF-8")
        if (text.endsWith("\n\n")) {
            out.append(text)
            return out.toString()
        }
    }
}
