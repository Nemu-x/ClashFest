package com.github.kr328.clash.design.model

import java.util.UUID

class ProfilePageState(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    var allUpdating = false
    private val updating = mutableSetOf<UUID>()
    private val failed = mutableSetOf<UUID>()
    private val successful = mutableSetOf<UUID>()
    private val explicitCompletions = mutableMapOf<UUID, Long>()

    fun beginUpdate(uuid: UUID): Boolean {
        if (!updating.add(uuid)) return false
        failed.remove(uuid)
        successful.remove(uuid)
        explicitCompletions.remove(uuid)
        return true
    }

    fun finishUpdate(uuid: UUID, success: Boolean) {
        if (updating.remove(uuid)) explicitCompletions[uuid] = now()
        if (success) {
            failed.remove(uuid)
            successful.add(uuid)
        } else {
            failed.add(uuid)
            successful.remove(uuid)
        }
    }

    fun cancelUpdate(uuid: UUID) {
        updating.remove(uuid)
        failed.remove(uuid)
    }

    /** Manual completion wins over delayed broadcasts from its retry attempts. */
    fun recordObservedUpdate(uuid: UUID, success: Boolean): Boolean {
        if (isUpdating(uuid)) return false
        if (explicitCompletions[uuid]?.let { now() - it < 3000L } == true) return false
        explicitCompletions.remove(uuid)
        finishUpdate(uuid, success)
        return true
    }

    fun isUpdating(uuid: UUID): Boolean = uuid in updating
    fun hasUpdateError(uuid: UUID): Boolean = uuid in failed
    fun wasUpdatedSuccessfully(uuid: UUID): Boolean = uuid in successful

    fun retainProfiles(uuids: Set<UUID>) {
        updating.retainAll(uuids)
        failed.retainAll(uuids)
        successful.retainAll(uuids)
        explicitCompletions.keys.retainAll(uuids)
    }

}
