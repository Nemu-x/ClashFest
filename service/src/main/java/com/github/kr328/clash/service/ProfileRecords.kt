package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.util.AgeKeyUrl
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.ProfileComposer
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.*

/**
 * Owns [ProfileManager]'s profile rows: creating, cloning, patching and renaming drafts under the
 * profile-order lock, merging imported + pending rows into a [Profile], the last-updated
 * timestamp, and arming the auto-update schedule.
 */
internal class ProfileRecords(
    private val context: Context,
    private val store: ServiceStore,
) {
    // Serializes nextProfileOrder() + Pending insert pairs. Without it, two concurrent
    // imports (e.g. auto-update + manual click) can both read the same MAX(profileOrder)
    // and produce duplicate ordering values that compare non-deterministically.
    private val profileOrderLock = Mutex()

    suspend fun create(type: Profile.Type, name: String, source: String): UUID {
        val uuid = generateProfileUUID()
        // Operator links may carry the age decryption key in the URL fragment
        // (`…#AGE-SECRET-KEY-…`) — split it into the profile field so the user
        // never types a key by hand. Every import surface (URL form, clipboard,
        // QR, deep-link) funnels through here.
        val split = AgeKeyUrl.split(source)
        profileOrderLock.withLock {
            val profileOrder = nextProfileOrder()
            val pending = Pending(
                uuid = uuid,
                name = name,
                type = type,
                source = split.source,
                interval = 0,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                profileOrder = profileOrder,
                ageSecretKey = split.ageSecretKey,
            )

            PendingDao().insert(pending)
        }

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    suspend fun clone(uuid: UUID): UUID {
        val newUUID = generateProfileUUID()

        val imported = ImportedDao().queryByUUID(uuid)
            ?: throw FileNotFoundException("profile $uuid not found")

        cloneImportedFiles(uuid, newUUID)

        profileOrderLock.withLock {
            val profileOrder = nextProfileOrder()
            val pending = Pending(
                uuid = newUUID,
                name = imported.name,
                type = Profile.Type.File,
                source = imported.source,
                interval = imported.interval,
                upload = imported.upload,
                total = imported.total,
                download = imported.download,
                expire = imported.expire,
                profileOrder = profileOrder,
                ageSecretKey = imported.ageSecretKey,
            )

            PendingDao().insert(pending)
        }

        return newUUID
    }

    suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?) {
        // A key pasted as part of the URL (fragment) wins over the separate
        // field — it is the more deliberate input, and leaving it inside
        // `source` would leak it into every place that renders the URL.
        val fragmentSplit = AgeKeyUrl.split(source)
        @Suppress("NAME_SHADOWING") val source = fragmentSplit.source
        @Suppress("NAME_SHADOWING") val ageSecretKey = fragmentSplit.ageSecretKey ?: ageSecretKey
        val locked = store.subscriptionShareLinksLockedFor(uuid)
        val resolvedSource =
            if (!locked) {
                source
            } else {
                PendingDao().queryByUUID(uuid)?.source
                    ?: ImportedDao().queryByUUID(uuid)?.source
                    ?: source
            }
        val pending = PendingDao().queryByUUID(uuid)

        if (pending == null) {
            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile $uuid not found")

            cloneImportedFiles(uuid)

            PendingDao().insert(
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = resolvedSource,
                    interval = interval,
                    upload = 0,
                    total = 0,
                    download = 0,
                    expire = 0,
                    profileOrder = imported.profileOrder,
                    ageSecretKey = ageSecretKey?.takeIf { it.isNotBlank() },
                )
            )
        } else {
            val newPending = pending.copy(
                name = name,
                source = resolvedSource,
                interval = interval,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                ageSecretKey = ageSecretKey?.takeIf { it.isNotBlank() },
            )

            PendingDao().update(newPending)
        }
    }

    suspend fun renameImported(uuid: UUID, name: String) {
        withContext(Dispatchers.IO) {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return@withContext
            val imported = ImportedDao().queryByUUID(uuid) ?: return@withContext
            if (imported.name == trimmed) return@withContext
            ImportedDao().update(imported.copy(name = trimmed))
            // Mirror to a pending draft if one happens to exist (full edit
            // flow in progress) — otherwise we'd silently overwrite the
            // user's in-progress edit on the next commit. No-op when no draft.
            PendingDao().queryByUUID(uuid)?.let { pending ->
                PendingDao().update(pending.copy(name = trimmed))
            }
            context.sendProfileChanged(uuid)
        }
    }

    internal suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)
        return buildProfile(uuid, imported, pending)
    }

    /**
     * Merge the imported + pending rows for [uuid] into a [Profile]. Pure (no DB) so callers that
     * already loaded the rows in bulk (see [ProfileManager.queryAll]) avoid an N+1 of per-uuid queries.
     */
    internal fun buildProfile(uuid: UUID, imported: Imported?, pending: Pending?): Profile? {
        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: return null
        val type = pending?.type ?: imported?.type ?: return null
        val source = pending?.source ?: imported?.source ?: return null
        val interval = pending?.interval ?: imported?.interval ?: return null
        val upload = pending?.upload ?: imported?.upload ?: return null
        val download = pending?.download ?: imported?.download ?: return null
        val total = pending?.total ?: imported?.total ?: return null
        val expire = pending?.expire ?: imported?.expire ?: return null

        return Profile(
            uuid,
            name,
            type,
            source,
            active != null && imported?.uuid == active,
            interval,
            upload,
            download,
            total,
            expire,
            resolveUpdatedAt(uuid),
            imported != null,
            pending != null,
            ageSecretKey = pending?.ageSecretKey ?: imported?.ageSecretKey,
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long {
        context.pendingDir.resolve(uuid.toString()).directoryLastModified?.let { return it }
        val importedDir = context.importedDir.resolve(uuid.toString())
        // subscription.yaml is written only when the subscription is fetched / committed; the
        // directory-wide max also caught config.yaml, which is re-composed on every VPN start and
        // mode switch, so "last updated" drifted to "last connected".
        File(importedDir, ProfileComposer.SUBSCRIPTION_FILE).takeIf { it.isFile }
            ?.let { return it.lastModified() }
        return importedDir.directoryLastModified ?: -1
    }

    private suspend fun nextProfileOrder(): Long {
        val importedMax = ImportedDao().queryMaxProfileOrder() ?: -1L
        val pendingMax = PendingDao().queryMaxProfileOrder() ?: -1L
        return maxOf(importedMax, pendingMax) + 1L
    }

    private fun cloneImportedFiles(source: UUID, target: UUID = source) {
        val s = context.importedDir.resolve(source.toString())
        val t = context.pendingDir.resolve(target.toString())

        if (!s.exists())
            throw FileNotFoundException("profile $source not found")

        t.deleteRecursively()

        s.copyRecursively(t)
    }

    internal suspend fun scheduleUpdate(uuid: UUID, startImmediately: Boolean) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        if (startImmediately) {
            ProfileReceiver.schedule(context, imported)
        } else {
            ProfileReceiver.scheduleNext(context, imported)
        }
    }
}
