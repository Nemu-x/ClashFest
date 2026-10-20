package com.github.kr328.clash.design.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.ProxyTransportInfo

internal fun providerNameForProxy(proxyName: String): String? {
    val trimmed = proxyName.trimStart()
    if (!trimmed.startsWith("[")) return null
    val end = trimmed.indexOf(']')
    if (end <= 1) return null
    return trimmed.substring(1, end).takeIf { it.isNotBlank() }
}

internal fun splitNameAndCity(text: String): Pair<String, String?> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return trimmed to null
    val lastSpace = trimmed.lastIndexOf(' ')
    if (lastSpace <= 0) return trimmed to null
    val head = trimmed.substring(0, lastSpace).trim()
    val tail = trimmed.substring(lastSpace + 1).trim()
    if (head.isEmpty() || tail.length < 3) return trimmed to null
    val tailIsCityLike = tail.first().isLetter() && tail.all { it.isLetter() || it == '-' || it == '\'' }
    val headHasIdShape = head.any { it.isDigit() || it == '-' || it == '_' || it == '#' }
    return if (tailIsCityLike && headHasIdShape) head to tail else trimmed to null
}

/**
 * "TCP" / "WS" / "GRPC" / "XHTTP" / "H2" / "QUIC" / "UDP"; null when YAML had no transport info.
 * Only V2Ray-style outbounds carry a `network:` key; for everything else a missing key meant
 * "TCP", which mislabelled QUIC protocols (Hysteria2, TUIC) and UDP tunnels (WireGuard).
 */
internal fun transportLabel(info: ProxyTransportInfo?, typeName: String): String? {
    if (info == null) return null
    when (typeName.lowercase()) {
        in QUIC_PROTOCOLS -> return "QUIC"
        in UDP_TUNNEL_PROTOCOLS -> return "UDP"
    }
    return when (val net = info.network.lowercase()) {
        "" -> "TCP"
        "h2", "http2" -> "H2"
        else -> net.uppercase()
    }
}

/** `tls: true`, or a protocol that is always TLS (Trojan, AnyTLS, the QUIC family). */
internal fun usesTls(info: ProxyTransportInfo, typeName: String): Boolean {
    val type = typeName.lowercase()
    return info.tls || type in ALWAYS_TLS_PROTOCOLS || type in QUIC_PROTOCOLS
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
    // The segments sit in proxy_proto_capsule, which clips them to one rounded shape.
    (badge.parent as? View)?.takeIf { it.id == R.id.proxy_proto_capsule }?.clipToOutline = true
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
    Proxy.Type.Relay, Proxy.Type.Chain -> R.color.group_relay
    else -> R.color.proto_default
}

internal fun applyDelayStyle(
    capsule: View,
    dot: View,
    text: TextView,
    delayMs: Int,
    context: Context,
) {
    // Delay reads as a pill: the value (Space Grotesk) on a soft fill of its own colour.
    val colorRes = when {
        delayMs in 0..200 -> R.color.delay_good
        delayMs in 201..500 -> R.color.delay_medium
        delayMs in 501..Short.MAX_VALUE -> R.color.delay_bad
        else -> R.color.delay_timeout
    }
    val color = ContextCompat.getColor(context, colorRes)
    capsule.setBackgroundResource(0)
    dot.visibility = View.GONE
    text.setTextColor(color)
    text.backgroundTintList = ColorStateList.valueOf((color and 0x00FFFFFF) or (0x26 shl 24)) // ~15%
}

internal fun formatDelay(delayMs: Int): String =
    when {
        delayMs in 0..Short.MAX_VALUE -> "${delayMs}ms"
        else -> "—"
    }

internal fun displayGroupName(groupName: String): String =
    groupName.trim().replace(Regex("\\s+"), " ")

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

internal fun extractLeadingEmoji(raw: String): String? {
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

internal fun isRegionalIndicator(codePoint: Int): Boolean =
    codePoint in 0x1F1E6..0x1F1FF

internal fun isEmojiLike(codePoint: Int): Boolean =
    codePoint in 0x1F300..0x1FAFF ||
        codePoint in 0x2600..0x27BF ||
        codePoint in 0x2300..0x23FF
/** Enum names, lowercased: QUIC-based outbounds, labelled "QUIC" instead of the default "TCP". */
private val QUIC_PROTOCOLS = setOf("hysteria", "hysteria2", "tuic", "masque", "shadowquic")
private val UDP_TUNNEL_PROTOCOLS = setOf("wireguard", "trusttunnel", "tailscale", "openvpn", "easytier")
private val ALWAYS_TLS_PROTOCOLS = setOf("trojan", "anytls")
