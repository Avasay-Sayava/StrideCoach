package com.stridecoach.running.core.data.adapter

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.stridecoach.running.core.data.repository.AppPreferencesStore
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import com.stridecoach.running.core.domain.engine.TrainingPlanGenerator
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.WalkLog
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream

@Serializable
data class StrideCoachVaultDto(
    val version: Int = 1,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val runs: List<RunRecord> = emptyList(),
    val walkLogs: List<WalkLog> = emptyList(),
    val activePlan: TrainingPlanConfig? = null,
    val templates: List<StructuredWorkout> = emptyList()
)

data class FolderSyncSummary(
    val importedTimelineFiles: Int = 0,
    val importedBackupFiles: Int = 0,
    val addedActivities: Int = 0,
    val addedWalkDays: Int = 0,
    val vaultSaved: Boolean = false,
    val vaultRestored: Boolean = false,
    val latestTimelineModMs: Long = 0L
)

/**
 * Persists an uninstall-safe full vault snapshot (`stridecoach-vault.json`) and automatically
 * imports new/updated Google Timeline JSON or Running Tracker `.backup` files inside a user-selected folder.
 */
object VaultAndTimelineSyncManager {
    private const val VAULT_FILENAME = "stridecoach-vault.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val backupAdapter = RunningTrackerBackupAdapter()
    private val timelineAdapter = TimelineJsonImporter()

    suspend fun writeVaultSnapshot(ctx: Context, repo: StrideCoachRepository, folderUriStr: String): Boolean {
        if (folderUriStr.isBlank()) return false
        val treeUri = runCatching { Uri.parse(folderUriStr) }.getOrNull() ?: return false
        val dto = StrideCoachVaultDto(
            runs = repo.getAllRunsForVault(),
            walkLogs = repo.getAllWalkLogs(),
            activePlan = repo.getActivePlan(),
            templates = repo.getAllTemplatesForVault()
        )
        val payload = json.encodeToString(dto)
        val target = findOrCreateChild(ctx, treeUri, VAULT_FILENAME, "application/json") ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(target, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(payload)
            }
            true
        }.getOrDefault(false)
    }

    suspend fun restoreFromVaultStream(stream: InputStream, repo: StrideCoachRepository): Int {
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dto = json.decodeFromString<StrideCoachVaultDto>(raw)
        if (dto.runs.isNotEmpty()) repo.saveRuns(dto.runs)
        if (dto.walkLogs.isNotEmpty()) repo.mergeWalkLogs(dto.walkLogs)
        dto.activePlan?.let { cfg ->
            val days = TrainingPlanGenerator().generate(cfg)
            repo.activatePlan(cfg, days)
        }
        dto.templates.forEach { repo.saveWorkoutTemplate(it) }
        return dto.runs.size
    }

    suspend fun syncFolderNow(
        ctx: Context,
        repo: StrideCoachRepository,
        prefs: AppPreferencesStore,
        forceAll: Boolean = false
    ): FolderSyncSummary {
        val p = prefs.preferences.first()
        if (p.syncFolderUri.isBlank()) return FolderSyncSummary()
        val treeUri = runCatching { Uri.parse(p.syncFolderUri) }.getOrNull() ?: return FolderSyncSummary()

        val children = listChildren(ctx, treeUri)
        var importedTl = 0
        var importedBak = 0
        var addedActs = 0
        var addedWalkDays = 0
        var restoredVault = false
        var maxMod = p.lastTimelineFileModMillis

        val curRuns = repo.getAllRunsForVault()
        if (curRuns.isEmpty() || forceAll) {
            val vaultEntry = children.firstOrNull { it.name.equals(VAULT_FILENAME, ignoreCase = true) }
            if (vaultEntry != null && curRuns.isEmpty()) {
                val restoredCount = runCatching {
                    ctx.contentResolver.openInputStream(vaultEntry.uri)?.use { restoreFromVaultStream(it, repo) } ?: 0
                }.getOrDefault(0)
                if (restoredCount > 0) restoredVault = true
            }
        }

        val existingRuns = repo.getAllRunsWithSplits()
        for (child in children) {
            if (child.name.equals(VAULT_FILENAME, ignoreCase = true)) continue
            if (!forceAll && child.lastModified <= p.lastTimelineFileModMillis) continue

            val lower = child.name.lowercase()
            if (lower.endsWith(".backup")) {
                val parsed = runCatching {
                    ctx.contentResolver.openInputStream(child.uri)?.use { backupAdapter.importBackupStream(it) }
                }.getOrNull().orEmpty()
                if (parsed.isNotEmpty()) {
                    addedActs += repo.saveRunsDeduplicated(parsed)
                    importedBak++
                }
                if (child.lastModified > maxMod) maxMod = child.lastModified
            } else if (lower.endsWith(".json")) {
                val head = peekHeader(ctx, child.uri)
                val kind = backupAdapter.detectImportKind(head)
                if (kind == ImportFileKind.GOOGLE_TIMELINE) {
                    val sum = runCatching {
                        ctx.contentResolver.openInputStream(child.uri)?.use {
                            timelineAdapter.importTimelineStream(it, existingRuns)
                        }
                    }.getOrNull()
                    if (sum != null) {
                        if (sum.walkLogs.isNotEmpty()) {
                            repo.mergeWalkLogs(sum.walkLogs)
                            addedWalkDays += sum.walkLogs.size
                        }
                        if (sum.activityRecords.isNotEmpty()) {
                            addedActs += repo.saveRunsDeduplicated(sum.activityRecords)
                        }
                        importedTl++
                    }
                    if (child.lastModified > maxMod) maxMod = child.lastModified
                } else if (kind == ImportFileKind.RUNNING_TRACKER_BACKUP) {
                    val parsed = runCatching {
                        ctx.contentResolver.openInputStream(child.uri)?.use { backupAdapter.importBackupStream(it) }
                    }.getOrNull().orEmpty()
                    if (parsed.isNotEmpty()) {
                        addedActs += repo.saveRunsDeduplicated(parsed)
                        importedBak++
                    }
                    if (child.lastModified > maxMod) maxMod = child.lastModified
                }
            }
        }

        val vaultSaved = writeVaultSnapshot(ctx, repo, p.syncFolderUri)
        val now = System.currentTimeMillis()
        prefs.update { cur ->
            cur.copy(
                lastFolderSyncMillis = now,
                lastTimelineFileModMillis = maxOf(cur.lastTimelineFileModMillis, maxMod)
            )
        }
        return FolderSyncSummary(
            importedTimelineFiles = importedTl,
            importedBackupFiles = importedBak,
            addedActivities = addedActs,
            addedWalkDays = addedWalkDays,
            vaultSaved = vaultSaved,
            vaultRestored = restoredVault,
            latestTimelineModMs = maxMod
        )
    }

    private data class ChildDoc(val uri: Uri, val name: String, val lastModified: Long)

    private fun listChildren(ctx: Context, treeUri: Uri): List<ChildDoc> {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = mutableListOf<ChildDoc>()
        runCatching {
            ctx.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mod = c.getLong(2)
                    val mime = c.getString(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out.add(ChildDoc(docUri, name, mod))
                }
            }
        }
        return out
    }

    private fun findOrCreateChild(ctx: Context, treeUri: Uri, name: String, mime: String): Uri? {
        val existing = listChildren(ctx, treeUri).firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.uri
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        return runCatching {
            DocumentsContract.createDocument(ctx.contentResolver, parentUri, mime, name)
        }.getOrNull()
    }

    private fun peekHeader(ctx: Context, uri: Uri): String {
        val buf = ByteArray(4096)
        val n = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.read(buf) } ?: 0
        }.getOrDefault(0)
        return if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
    }
}
