package com.github.kr328.clash.design.model

import java.util.UUID

class ProfilePageState {
    var allUpdating = false
    private val updating = mutableSetOf<UUID>()
    private val failed = mutableSetOf<UUID>()

    fun beginUpdate(uuid: UUID): Boolean {
        if (!updating.add(uuid)) return false
        failed.remove(uuid)
        return true
    }

    fun finishUpdate(uuid: UUID, success: Boolean) {
        updating.remove(uuid)
        if (success) failed.remove(uuid) else failed.add(uuid)
    }

    fun recordObservedUpdate(uuid: UUID, success: Boolean) {
        if (!isUpdating(uuid)) finishUpdate(uuid, success)
    }

    fun isUpdating(uuid: UUID): Boolean = uuid in updating

    fun hasUpdateError(uuid: UUID): Boolean = uuid in failed

    fun retainProfiles(uuids: Set<UUID>) {
        updating.retainAll(uuids)
        failed.retainAll(uuids)
    }

    /** Profile currently running a bulk ping (UI spinner on speedometer). */
    var pingingUuid: UUID? = null
}
