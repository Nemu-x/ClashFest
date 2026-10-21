package com.github.kr328.clash.design.adapter

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.FlagDrawableLoader
import com.github.kr328.clash.design.util.FlagParser
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProxyTransportInfo

/**
 * Binds one proxy-node row view (flag, name, protocol chips, latency capsule, selection
 * ornaments and tap handling). Shared by the node picker sheet and the card's inline accordion so
 * both render rows identically.
 */
internal class ProxyNodeRowBinder(private val adapter: ProfileAdapter) {
    /**
     * Refreshes only the values that change without a structural change — the latency capsule and the
     * selection ornaments — on an already-inflated node row. Used on the live-ping tick so no view is
     * re-inflated (the capsules follow the URL-test, and the check follows a server-side `now` change).
     */
    internal fun updateProxyRowDynamic(row: View, pickerRow: ProxyPickerRow) {
        val delayMs = pickerRow.delayMs
        val capsule = row.findViewById<View>(R.id.latency_capsule)
        val dot = row.findViewById<View>(R.id.latency_dot)
        val delayView = row.findViewById<TextView>(R.id.proxy_delay)
        if (capsule != null && dot != null && delayView != null) {
            bindDelayCapsule(capsule, dot, delayView, pickerRow.proxy.name, delayMs)
        }
        val selected = pickerRow.selected
        row.isSelected = selected
        row.findViewById<View>(R.id.selected_bar)?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        row.findViewById<View>(R.id.selected_check)?.visibility = if (selected) View.VISIBLE else View.GONE
    }

    /**
     * Binds one proxy-node row view from a [ProxyPickerRow]. Shared by [ProxyNodeAdapter], whose
     * RecyclerView recycles views so only the ~10 visible rows are ever inflated — a 200-node group
     * no longer inflates 200 views synchronously and freezes the UI on open (O-07).
     */
    internal fun bindProxyNodeRow(
        row: View,
        profile: Profile,
        pickerRow: ProxyPickerRow,
        showGroupInSubtitle: Boolean,
        onSelectionChanged: () -> Unit,
    ) {
        val p = pickerRow.proxy
        val groupName = pickerRow.groupName

        bindNodeRowAppearance(
            row = row,
            title = p.title.ifBlank { p.name },
            type = p.type,
            subtitle = p.subtitle,
            groupName = groupName,
            showGroupInSubtitle = showGroupInSubtitle,
        ) { adapter.transportInfoByProfile[profile.uuid]?.get(p.name) }

        val delayMs = pickerRow.delayMs

        val capsule = row.findViewById<View>(R.id.latency_capsule)
        val dot = row.findViewById<View>(R.id.latency_dot)
        val delayView = row.findViewById<TextView>(R.id.proxy_delay)
        bindDelayCapsule(capsule, dot, delayView, p.name, delayMs)
        // Tap the capsule → measure just this node. The capsule sits inside the clickable row, so
        // it consumes the touch and the row's select/detail handlers do not fire.
        capsule.setOnClickListener {
            if (!adapter.markNodePingPending(p.name)) return@setOnClickListener
            delayView.text = NODE_PING_PENDING_TEXT
            adapter.onPingNode(profile, groupName, p.name)
        }

        val selected = pickerRow.selected
        row.isSelected = selected
        row.findViewById<View>(R.id.selected_bar).visibility =
            if (selected) View.VISIBLE else View.INVISIBLE
        row.findViewById<View>(R.id.selected_check).visibility =
            if (selected) View.VISIBLE else View.GONE
        val mainHit = row.findViewById<View>(R.id.proxy_row_main_hit)
        val tryPickNode: () -> Boolean = {
            val canPickLive = adapter.clashRunning && adapter.selection.useEngineFor(profile)
            // No profile is active yet (e.g. a subscription imported without being switched on):
            // Home already presents this one as "Active profile" and opens its picker, so picking a
            // node there activates it instead of answering with the node's YAML detail.
            val activatesProfile = profile.imported && adapter.activeProfileUuid == null && !adapter.clashRunning
            val canPickOffline = profile.imported && profile.uuid == adapter.activeProfileUuid
            if (activatesProfile) adapter.activateProfile(profile)
            if (canPickLive || canPickOffline || activatesProfile) {
                adapter.setPendingProxySelection(profile.uuid, groupName, p.name)
                // Re-submit the list so the newly-selected and previously-selected rows rebind
                // (the RecyclerView is data-driven; DiffUtil touches just those two rows).
                onSelectionChanged()
                adapter.onProxyNodeSelected(profile, groupName, p.name)
                true
            } else {
                false
            }
        }
        mainHit.setOnClickListener {
            if (!tryPickNode()) {
                adapter.onProxyYamlDetail(profile, groupName, p.name)
            }
        }
        mainHit.setOnLongClickListener {
            adapter.onProxyYamlDetail(profile, groupName, p.name)
            true
        }
    }

    /** Latency capsule text + style; a node with a single-node test in flight shows "…". */
    private fun bindDelayCapsule(capsule: View, dot: View, delayView: TextView, proxyName: String, delayMs: Int) {
        applyDelayStyle(capsule, dot, delayView, delayMs, capsule.context)
        delayView.text = if (adapter.isNodePingPending(proxyName)) NODE_PING_PENDING_TEXT else formatDelay(delayMs)
    }
}

/**
 * The static look of one node row — flag, name / city, protocol chips and subtitle — shared by the
 * local picker rows and the remote-control picker (which has no transport detail: [transportFor]
 * returns null there, so only the protocol chip shows).
 */
internal fun bindNodeRowAppearance(
    row: View,
    title: String,
    type: Proxy.Type,
    subtitle: String,
    groupName: String,
    showGroupInSubtitle: Boolean,
    transportFor: () -> ProxyTransportInfo?,
) {
    val context = row.context
    val flag = FlagParser.parse(title)
    val cleanTitle = flag?.let {
        title.removePrefix(it.emoji)
            .trimStart(' ', '|', '-', '_', '.', ':')
            .ifBlank { title }
    } ?: title
    val (namePart, cityPart) = splitNameAndCity(cleanTitle)
    row.findViewById<TextView>(R.id.proxy_title).text = namePart
    row.findViewById<TextView>(R.id.proxy_city).apply {
        if (cityPart != null) {
            text = cityPart
            visibility = View.VISIBLE
        } else {
            visibility = View.GONE
        }
    }
    val flagCard = row.findViewById<View>(R.id.proxy_flag_card)
    val flagImage = row.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.proxy_flag_image)
    val flagText = row.findViewById<TextView>(R.id.proxy_flag)
    if (flag != null) {
        flagCard.visibility = View.VISIBLE
        val sizePx = context.dp(28)
        val bitmap = FlagDrawableLoader.loadBitmap(context, flag.code, sizePx)
        if (bitmap != null) {
            flagImage.setImageBitmap(bitmap)
            flagImage.visibility = View.VISIBLE
            flagText.visibility = View.GONE
        } else {
            flagImage.visibility = View.GONE
            flagText.text = flag.emoji
            flagText.visibility = View.VISIBLE
        }
    } else {
        flagCard.visibility = View.GONE
    }

    val typeBadge = row.findViewById<TextView>(R.id.proxy_type_badge)
    val transportBadge = row.findViewById<TextView>(R.id.proxy_transport_badge)
    val realityBadge = row.findViewById<TextView>(R.id.proxy_reality_badge)
    val typeName = type.name

    // Leaf node → protocol chips (VLESS / GRPC / REALITY). Nested auto-group →
    // a single group-type chip (URL-TEST / FALLBACK / …) so groups read distinctly
    // from nodes. Both resolve offline (overlay: type from transport/groups preview).
    val groupTypeLabel = groupTypeLabel(type)
    val showBadge: Boolean
    if (type.group) {
        if (groupTypeLabel != null) {
            applyProtoChip(typeBadge, groupTypeLabel, ContextCompat.getColor(context, groupTypeColor(type)))
        } else {
            typeBadge.visibility = View.GONE
        }
        transportBadge.visibility = View.GONE
        realityBadge.visibility = View.GONE
        showBadge = groupTypeLabel != null
    } else {
        showBadge = typeName != "Unknown"
        if (showBadge) {
            applyProtoChip(typeBadge, typeName.uppercase(), ContextCompat.getColor(context, protocolFamilyColor(typeName)))
        } else {
            typeBadge.visibility = View.GONE
        }

        val transportInfo = if (showBadge && hasTransport(typeName)) transportFor() else null
        val transportLabel = transportLabel(transportInfo, typeName)
        if (transportLabel != null) {
            applyProtoChip(transportBadge, transportLabel, ContextCompat.getColor(context, R.color.proto_transport))
        } else {
            transportBadge.visibility = View.GONE
        }
        // One security chip: REALITY when set, otherwise TLS when the node encrypts with it.
        when {
            transportInfo?.reality == true ->
                applyProtoChip(realityBadge, "REALITY", ContextCompat.getColor(context, R.color.proto_reality))
            transportInfo != null && usesTls(transportInfo, typeName) ->
                applyProtoChip(realityBadge, "TLS", ContextCompat.getColor(context, R.color.proto_tls))
            else -> realityBadge.visibility = View.GONE
        }
    }

    val rawSubtitle = subtitle
        .takeIf { it.isNotBlank() && !it.equals(typeName, ignoreCase = true) }
    val shownSubtitle = buildList {
        rawSubtitle?.let(::add)
        if (showGroupInSubtitle) add(displayGroupName(groupName))
    }.joinToString(" · ").takeIf { it.isNotBlank() }
    row.findViewById<TextView>(R.id.proxy_subtitle).apply {
        visibility = if (shownSubtitle == null) View.GONE else View.VISIBLE
        text = shownSubtitle.orEmpty()
    }
    row.findViewById<View>(R.id.proxy_subtitle_sep).visibility =
        if (showBadge && shownSubtitle != null) View.VISIBLE else View.GONE
}

/** Shown in the latency capsule while a single-node test is in flight. */
private const val NODE_PING_PENDING_TEXT = "…"
