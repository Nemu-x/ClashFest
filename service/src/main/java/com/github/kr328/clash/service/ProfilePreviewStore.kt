package com.github.kr328.clash.service

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.RuleState
import com.github.kr328.clash.service.model.YamlPreview
import com.github.kr328.clash.service.util.ProxyDialerYamlEdit
import com.github.kr328.clash.service.util.RuleApplyService
import com.github.kr328.clash.service.util.YamlPreviewSupport
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import java.io.File
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Dry-run previews of config edits and the commit that applies one: every editor first builds a
 * [CachedPreview] here, and [applyYamlPreview] writes it back (hash-checked, with rollback).
 */
internal class ProfilePreviewStore(private val env: ProfileEditEnv) {
    private val context = env.context
    private val ruleApplyService = env.ruleApplyService
    private val userLayerStore = env.userLayerStore
    private val previewJson = env.previewJson

    private fun syncLayerFromConfig(uuid: UUID) = env.syncLayerFromConfig(uuid)

    private val previewCache = LinkedHashMap<String, CachedPreview>()

    internal data class CachedFile(
        val relativePath: String,
        val sourceHash: String,
        val proposedYaml: String,
        val providerFile: Boolean = false,
    )

    internal data class CachedPreview(
        val uuid: UUID,
        val files: List<CachedFile>,
        val ruleStateJson: String? = null,
        /**
         * Overlay: a layer mutation to apply when this preview is committed — for edits whose user
         * delta cannot be re-extracted from config.yaml (raw proxy-/rule-provider YAML). In-memory
         * only (CachedPreview is never serialized), so a closure is fine. config-overlay-architecture.
         */
        val layerMutation: ((com.github.kr328.clash.service.util.UserLayer) -> com.github.kr328.clash.service.util.UserLayer)? = null,
    )

    suspend fun applyYamlPreview(previewId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val cached = synchronized(previewCache) { previewCache.remove(previewId) } ?: return@withContext false
            if (ImportedDao().queryByUUID(cached.uuid) == null) return@withContext false
            val dir = File(context.importedDir, cached.uuid.toString())

            // Read + verify hashes for every file before touching anything on disk, and
            // keep the originals in memory so we can roll a partially-written batch back
            // if a later write fails. The actual write uses tmp + rename per file so a
            // single-file write is atomic on the local FS even if the process is killed
            // mid-flight.
            val originals = LinkedHashMap<File, String>()
            val targets = LinkedHashMap<CachedFile, File>()
            for (file in cached.files) {
                val target = resolveCachedFile(dir, file) ?: return@withContext false
                if (!target.isFile) return@withContext false
                val current = target.readText()
                if (YamlPreviewSupport.sha256(current) != file.sourceHash) return@withContext false
                originals[target] = current
                targets[file] = target
            }

            val written = ArrayList<File>(cached.files.size)
            try {
                for (file in cached.files) {
                    val target = targets.getValue(file)
                    atomicWriteText(target, file.proposedYaml)
                    written += target
                }
                cached.ruleStateJson?.let {
                    ruleApplyService.saveStateJson(cached.uuid, it)
                }
                // Overlay: keep the user layer in sync with the just-committed config.yaml so the
                // edit survives the next subscription update (config-overlay-architecture). The
                // clean sections are re-derived from config.yaml; raw provider edits carry an
                // explicit layer mutation (their user delta can't be re-extracted from config.yaml).
                runCatching { syncLayerFromConfig(cached.uuid) }
                cached.layerMutation?.let { mut -> userLayerStore.update(cached.uuid, mut) }
                context.sendProfileChanged(cached.uuid)
                true
            } catch (e: Exception) {
                Log.w("applyYamlPreview write failed, rolling back ${written.size} file(s)", e)
                written.forEach { target ->
                    runCatching { originals[target]?.let { atomicWriteText(target, it) } }
                }
                false
            }
        }
    }

    private fun atomicWriteText(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.applying")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("Failed to rename ${tmp.name} to ${target.name}")
        }
    }

    suspend fun previewRuleDryRun(
        uuid: UUID,
        title: String,
        dryRun: RuleApplyService.RuleDryRun?,
    ): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null || dryRun == null) return@withContext null
            try {
                createPreview(
                    uuid = uuid,
                    title = title,
                    files = listOf(
                        CachedFile(
                            relativePath = "config.yaml",
                            sourceHash = YamlPreviewSupport.sha256(dryRun.currentYaml),
                            proposedYaml = dryRun.proposedYaml,
                        )
                    ),
                    currentYaml = dryRun.currentYaml,
                    proposedYaml = dryRun.proposedYaml,
                    ruleStateJson = previewJson.encodeToString(RuleState.serializer(), dryRun.normalizedState),
                )
            } catch (e: Exception) {
                createInvalidPreview(title, "", "", e)
            }
        }
    }

    suspend fun previewConfigMutation(
        uuid: UUID,
        title: String,
        layerMutation: ((com.github.kr328.clash.service.util.UserLayer) -> com.github.kr328.clash.service.util.UserLayer)? = null,
        mutate: (String) -> String,
    ): String? {
        return withContext(Dispatchers.IO) {
            if (ImportedDao().queryByUUID(uuid) == null) return@withContext null
            val dir = File(context.importedDir, uuid.toString())
            val file = File(dir, "config.yaml")
            if (!file.isFile) return@withContext null
            val current = file.readText()
            try {
                val proposed = mutate(current)
                YamlPreviewSupport.validateConfigYaml(proposed)
                createPreview(
                    uuid = uuid,
                    title = title,
                    files = listOf(filePreview(dir, "config.yaml", current, proposed)),
                    currentYaml = current,
                    proposedYaml = proposed,
                    layerMutation = layerMutation,
                )
            } catch (e: Exception) {
                createInvalidPreview(title, current, current, e)
            }
        }
    }

    fun filePreview(
        dir: File,
        relativePath: String,
        current: String,
        proposed: String,
        providerFile: Boolean = false,
    ): CachedFile {
        YamlPreviewSupport.validateConfigYaml(proposed)
        val cached = CachedFile(
            relativePath = relativePath,
            sourceHash = YamlPreviewSupport.sha256(current),
            proposedYaml = proposed,
            providerFile = providerFile,
        )
        require(resolveCachedFile(dir, cached) != null) { "Unsafe preview file path" }
        return cached
    }

    private fun resolveCachedFile(dir: File, file: CachedFile): File? {
        if (file.providerFile) return ProxyDialerYamlEdit.resolveProviderPath(dir, file.relativePath)
        if (file.relativePath != "config.yaml") return null
        return runCatching {
            val profileRoot = dir.canonicalFile
            File(profileRoot, "config.yaml").canonicalFile.takeIf { it.parentFile == profileRoot }
        }.getOrNull()
    }

    fun createPreview(
        uuid: UUID,
        title: String,
        files: List<CachedFile>,
        currentYaml: String = joinPreviewFiles(
            files,
            File(context.importedDir, uuid.toString()),
            useProposed = false,
        ),
        proposedYaml: String = joinPreviewFiles(
            files,
            File(context.importedDir, uuid.toString()),
            useProposed = true,
        ),
        ruleStateJson: String? = null,
        layerMutation: ((com.github.kr328.clash.service.util.UserLayer) -> com.github.kr328.clash.service.util.UserLayer)? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        synchronized(previewCache) {
            previewCache[id] = CachedPreview(uuid, files, ruleStateJson, layerMutation)
            while (previewCache.size > 16) {
                previewCache.remove(previewCache.keys.first())
            }
        }
        return previewJson.encodeToString(
            YamlPreview(
                id = id,
                title = title,
                currentYaml = YamlPreviewSupport.boundedPreviewText(currentYaml),
                proposedYaml = YamlPreviewSupport.boundedPreviewText(proposedYaml),
                diff = YamlPreviewSupport.unifiedDiff(currentYaml, proposedYaml),
                valid = true,
            )
        )
    }

    fun createInvalidPreview(title: String, current: String, proposed: String, error: Throwable): String {
        return previewJson.encodeToString(
            YamlPreview(
                id = "",
                title = title,
                currentYaml = YamlPreviewSupport.boundedPreviewText(current),
                proposedYaml = YamlPreviewSupport.boundedPreviewText(proposed),
                diff = YamlPreviewSupport.unifiedDiff(current, proposed),
                valid = false,
                error = error.message ?: error.toString(),
            )
        )
    }

    private fun joinPreviewFiles(files: List<CachedFile>, dir: File, useProposed: Boolean): String {
        return files.joinToString("\n\n") { file ->
            val body = if (useProposed) {
                file.proposedYaml
            } else {
                requireNotNull(resolveCachedFile(dir, file)) { "Unsafe preview file path" }.readText()
            }
            "# ${file.relativePath}\n$body"
        }
    }
}
