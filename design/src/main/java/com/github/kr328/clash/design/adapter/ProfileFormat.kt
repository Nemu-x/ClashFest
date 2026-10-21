package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.StandalonePing
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.toBytesString
import com.github.kr328.clash.service.model.Profile
import java.util.UUID

private val profileEmojiPool = listOf(
    "🐱", "🐶", "🦊", "🐼", "🐻", "🐨", "🐯", "🦁",
    "🐸", "🐵", "🐙", "🦄", "🐧", "🐺", "🐹", "🐰",
)

internal fun profileEmoji(profile: Profile): String {
    val index = (profile.uuid.hashCode() and Int.MAX_VALUE) % profileEmojiPool.size
    return profileEmojiPool[index]
}

internal fun displayGroupName(groupName: String): String =
    groupName.trim().replace(Regex("\\s+"), " ")

internal fun proxySelectionKey(uuid: UUID, groupName: String): String =
    "${uuid}|${groupName}"

/**
 * Matches an engine group name against a stored one, tolerating the whitespace differences that
 * appear when a name round-trips through YAML ("US  Auto" vs "US Auto").
 *
 * Exact equality is checked first so an exact name always wins over a normalized near-match:
 * mihomo treats two names differing only in whitespace runs as two distinct groups, and callers
 * resolve with `firstOrNull`, which would otherwise silently pick whichever came first in map
 * order. See [uniqueGroupMatch] for the lookups that must not guess at all.
 */
internal fun groupsMatchKey(engineName: String, storedName: String): Boolean {
    if (engineName == storedName) return true
    return displayGroupName(engineName) == displayGroupName(storedName)
}

/**
 * Resolves [name] against [candidates] the way [groupsMatchKey] does, but returns null when the
 * normalized form is ambiguous instead of picking an arbitrary winner. Showing no data beats
 * showing another group's members under this group's heading.
 */
internal fun <V> uniqueGroupMatch(candidates: Map<String, V>, name: String): V? {
    candidates[name]?.let { return it }
    val matches = candidates.entries.filter { groupsMatchKey(name, it.key) }
    return matches.singleOrNull()?.value
}

/**
 * YAML `type:` → [Proxy.Type]. The keys are the engine's **config** spellings from
 * `adapter/parser.go`, which are not the same as the wire names [Proxy.Type] is
 * modelled on (`gost-relay` here vs `GostRelay` from `AdapterType.String()`); the
 * short aliases (`ss`, `hy2`, `wg`, …) are ours, for hand-written configs.
 * In sync with mihomo v1.19.31 — re-check `parser.go` after every core bump.
 */
internal fun proxyTypeFromYamlName(raw: String?): Proxy.Type = when (raw?.trim()?.lowercase()) {
    "ss", "shadowsocks" -> Proxy.Type.Shadowsocks
    "ssr", "shadowsocksr" -> Proxy.Type.ShadowsocksR
    "snell" -> Proxy.Type.Snell
    "socks5", "socks" -> Proxy.Type.Socks5
    "http", "https" -> Proxy.Type.Http
    "vmess" -> Proxy.Type.Vmess
    "vless" -> Proxy.Type.Vless
    "trojan" -> Proxy.Type.Trojan
    "hysteria" -> Proxy.Type.Hysteria
    "hysteria2", "hy2" -> Proxy.Type.Hysteria2
    "tuic" -> Proxy.Type.Tuic
    "shadowquic" -> Proxy.Type.ShadowQuic
    "wireguard", "wg" -> Proxy.Type.WireGuard
    "ssh" -> Proxy.Type.Ssh
    "mieru" -> Proxy.Type.Mieru
    "anytls" -> Proxy.Type.AnyTLS
    "sudoku" -> Proxy.Type.Sudoku
    "masque" -> Proxy.Type.Masque
    "trusttunnel" -> Proxy.Type.TrustTunnel
    "openvpn" -> Proxy.Type.OpenVPN
    "tailscale" -> Proxy.Type.Tailscale
    "zerotier" -> Proxy.Type.ZeroTier
    "easytier" -> Proxy.Type.EasyTier
    "gost-relay" -> Proxy.Type.GostRelay
    "direct" -> Proxy.Type.Direct
    "reject" -> Proxy.Type.Reject
    "dns" -> Proxy.Type.Dns
    "chain" -> Proxy.Type.Chain
    "rematch" -> Proxy.Type.Rematch
    else -> Proxy.Type.Unknown
}

internal fun formatExpiryLeft(expireMs: Long, context: Context): String? {
    if (expireMs <= 0L) return null
    val now = System.currentTimeMillis()
    val diff = expireMs - now
    if (diff <= 0L) return context.getString(R.string.proxy_sheet_expiry_expired)
    val totalHours = diff / 3_600_000L
    val days = totalHours / 24L
    val hours = totalHours % 24L
    return if (days > 0) {
        context.getString(R.string.proxy_sheet_expiry_days_hours, days.toInt(), hours.toInt())
    } else {
        context.getString(R.string.proxy_sheet_expiry_hours, hours.toInt().coerceAtLeast(1))
    }
}

/**
 * Keyed on the **enum** name (the caller passes `p.type.name`), so `gostrelay`, not
 * the config spelling `gost-relay`. Grouping is by protocol family, not by product:
 * QUIC-based transports share one colour, the tunnel-style outbounds
 * (wireguard/tailscale/zerotier/easytier/openvpn) another, TCP proxy protocols a third.
 * An unlisted type falls back to grey rather than losing its chip.
 */
internal fun protocolFamilyColor(typeName: String): Int = when (typeName.lowercase()) {
    "vmess", "vless" -> R.color.proto_vless
    "trojan" -> R.color.proto_trojan
    "hysteria", "hysteria2" -> R.color.proto_hysteria
    "tuic", "anytls", "masque", "shadowquic" -> R.color.proto_tuic
    "shadowsocks", "shadowsocksr", "snell", "socks5", "mieru", "sudoku", "ssh" ->
        R.color.proto_shadowsocks
    "http", "gostrelay" -> R.color.proto_http
    "wireguard", "trusttunnel", "tailscale", "zerotier", "easytier", "openvpn" -> R.color.proto_wireguard
    "direct" -> R.color.proto_tcp
    else -> R.color.proto_default
}

/** Lumen protocol chip: coloured label on a soft (~14% alpha) tint of the same colour. */
internal fun applyProtoChip(badge: TextView, label: String, color: Int) {
    badge.visibility = View.VISIBLE
    badge.text = label
    badge.setTextColor(color)
    val tint = (color and 0x00FFFFFF) or (0x24 shl 24) // ~14% alpha fill
    badge.backgroundTintList = ColorStateList.valueOf(tint)
}

/** Label for a nested auto-group member (url-test / fallback / …); null for leaf nodes. */
internal fun groupTypeLabel(type: Proxy.Type): String? = when (type) {
    Proxy.Type.URLTest -> "URL-TEST"
    Proxy.Type.Fallback -> "FALLBACK"
    Proxy.Type.LoadBalance -> "LOAD-BALANCE"
    Proxy.Type.Selector -> "SELECTOR"
    Proxy.Type.Relay -> "RELAY"
    Proxy.Type.Chain -> "CHAIN"
    else -> null
}

/** Per-type chip colour — shared semantic palette with SlothClash (brand unity). */
internal fun groupTypeColor(type: Proxy.Type): Int = when (type) {
    Proxy.Type.URLTest -> R.color.group_urltest
    Proxy.Type.Fallback -> R.color.group_fallback
    Proxy.Type.LoadBalance -> R.color.group_loadbalance
    Proxy.Type.Selector -> R.color.group_selector
    Proxy.Type.Relay -> R.color.group_relay
    Proxy.Type.Chain -> R.color.group_relay
    else -> R.color.proto_default
}

internal fun applyDelayStyle(
    capsule: View,
    dot: View,
    text: TextView,
    delayMs: Int,
    context: Context,
) {
    // Five levels: fast (<=200ms), medium (<=500ms), slow, timeout/untested, N/A (probe not
    // supported for the node). The level colour drives the dot and the text; a soft tint of the
    // same colour fills the inner pill. The outer touch wrapper keeps its own ripple.
    val colorRes = when {
        delayMs in 0..200 -> R.color.delay_good
        delayMs in 201..500 -> R.color.delay_medium
        delayMs in 501..Short.MAX_VALUE -> R.color.delay_bad
        else -> R.color.delay_timeout
    }
    val color = ContextCompat.getColor(context, colorRes)
    val fillAlpha = if (delayMs == StandalonePing.TCP_UNSUPPORTED) 0x14 else 0x26
    val pill = capsule.findViewById<View>(R.id.latency_pill) ?: capsule
    pill.backgroundTintList = ColorStateList.valueOf((color and 0x00FFFFFF) or (fillAlpha shl 24))
    dot.backgroundTintList = ColorStateList.valueOf(color)
    text.setTextColor(color)
}

internal fun providerNameForProxy(proxyName: String): String? {
    val trimmed = proxyName.trimStart()
    if (!trimmed.startsWith("[")) return null
    val end = trimmed.indexOf(']')
    if (end <= 1) return null
    return trimmed.substring(1, end).takeIf { it.isNotBlank() }
}

internal fun shouldHideProxyOption(groupName: String, proxy: Proxy): Boolean {
    if (!groupName.equals("GLOBAL", ignoreCase = true)) return false
    if (proxy.type == Proxy.Type.Direct || proxy.type == Proxy.Type.Reject) return true
    return proxy.name.equals("DIRECT", ignoreCase = true) ||
        proxy.name.equals("REJECT", ignoreCase = true)
}

internal fun shouldHideProxyName(groupName: String, proxyName: String): Boolean {
    if (!groupName.equals("GLOBAL", ignoreCase = true)) return false
    return proxyName.equals("DIRECT", ignoreCase = true) ||
        proxyName.equals("REJECT", ignoreCase = true)
}

internal fun formatDelay(delayMs: Int, context: Context): String =
    when {
        delayMs == StandalonePing.TCP_UNSUPPORTED -> context.getString(R.string.server_ping_unsupported)
        delayMs in 0..Short.MAX_VALUE -> "${delayMs}ms"
        else -> "—"
    }

internal fun formatNodeHeadline(raw: String, context: Context): Pair<String, String?> {
    val trimmed = raw.trim()
    val leadingEmoji = extractLeadingEmoji(trimmed)
    val stripped = if (leadingEmoji != null) {
        trimmed.removePrefix(leadingEmoji).trimStart(' ', '|', '-', '_', '.', ':')
    } else {
        trimmed
    }
    return stripped to leadingEmoji
}

private fun extractLeadingEmoji(raw: String): String? {
    val input = raw.trimStart()
    if (input.isEmpty()) return null

    val firstCp = input.codePointAt(0)
    val firstLen = Character.charCount(firstCp)

    // Handle country flags encoded as two regional-indicator symbols.
    if (isRegionalIndicator(firstCp) && input.length >= firstLen + 2) {
        val secondCp = input.codePointAt(firstLen)
        if (isRegionalIndicator(secondCp)) {
            return String(Character.toChars(firstCp)) + String(Character.toChars(secondCp))
        }
    }

    if (!isEmojiLike(firstCp)) return null
    val base = String(Character.toChars(firstCp))
    val remaining = input.substring(firstLen)
    val variant = if (remaining.startsWith("\uFE0F")) "\uFE0F" else ""
    return base + variant
}

private fun isRegionalIndicator(codePoint: Int): Boolean =
    codePoint in 0x1F1E6..0x1F1FF

private fun isEmojiLike(codePoint: Int): Boolean =
    codePoint in 0x1F300..0x1FAFF ||
        codePoint in 0x2600..0x27BF ||
        codePoint in 0x2300..0x23FF

internal fun formatProfileUsageLine(context: Context, p: Profile): String {
    val used = (p.download + p.upload).toBytesString(context)
    return if (p.total < 2) {
        context.getString(R.string.subscription_usage_format, used, "∞")
    } else {
        context.getString(R.string.subscription_usage_format, used, p.total.toBytesString(context))
    }
}

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
