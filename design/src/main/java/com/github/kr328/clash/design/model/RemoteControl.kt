package com.github.kr328.clash.design.model

/*
 * View models of the TV remote-control screen (CompanionRemoteDesign). The app maps the agent's
 * wire models onto these, so the design module stays free of the companion protocol.
 */

/** Link to the TV as the remote screen's header shows it. */
enum class RemoteLink { Connecting, Live, Reconnecting, Unreachable }

/** One policy group on the TV, in the TV's display order. [delay] 0 means unknown or failed. */
data class RemoteProxyGroup(
    val name: String,
    val type: String,
    val now: String,
    val selectable: Boolean,
    val nodes: List<RemoteProxyNode>,
)

data class RemoteProxyNode(val name: String, val type: String, val delay: Int)

/** The at-a-glance block (power, mode, profile, node) at the top of the remote screen. */
data class RemoteOverview(
    val power: Boolean,
    val mode: String?,
    val modes: List<String>,
    val profileName: String?,
    val profileMeta: String?,
    val profileUpdatable: Boolean,
    val node: String?,
)

data class RemoteProfileRow(
    val id: String,
    val name: String,
    val meta: String,
    val active: Boolean,
    val updatable: Boolean,
)

data class RemoteConnectionRow(val id: String, val title: String, val subtitle: String, val network: String)

data class RemoteProviderRow(
    val kind: String,
    val name: String,
    val meta: String,
    val updatable: Boolean,
    val healthCheckable: Boolean,
)

/** A bool setting has [checked]; an enum setting has [value] and [options]. */
data class RemoteSettingRow(
    val key: String,
    val label: String,
    val checked: Boolean? = null,
    val value: String? = null,
    val options: List<String> = emptyList(),
)

data class RemoteActionRow(val id: String, val label: String)

/** Sections below the overview; only the ones the TV advertises are shown. */
enum class RemoteSection { Profiles, Connections, Providers, Settings, Actions }
