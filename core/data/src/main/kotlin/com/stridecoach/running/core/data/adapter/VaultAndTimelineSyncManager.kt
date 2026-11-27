package com.stridecoach.running.core.data.adapter

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.stridecoach.running.core.data.repository.AppPreferencesStore as PrefsStore
import com.stridecoach.running.core.data.repository.StrideCoachRepository as Repo
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

private data class ChildDoc(val uri: Uri, val name: String, val modMs: Long)

private class SyncAcc(var maxMod: Long) {
    var tlFiles = 0
    var bakFiles = 0
    var addedActs = 0
    var addedWalks = 0
}

object VaultAndTimelineSyncManager {
    private const val VAULT_NAME = "stridecoach-vault.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val bak = RunningTrackerBackupAdapter()
    private val tl = TimelineJsonImporter()

    suspend fun writeVaultSnapshot(ctx: Context, repo: Repo, folderUri: String): Boolean {
        if (folderUri.isBlank()) return false
        val tree = runCatching { Uri.parse(folderUri) }.getOrNull() ?: return false
        val dto = StrideCoachVaultDto(
            runs = repo.getAllRunsForVault(),
            walkLogs = repo.getAllWalkLogs(),
            activePlan = repo.getActivePlan(),
            templates = repo.getAllTemplatesForVault()
        )
        val target = findOrCreateChild(ctx, tree, VAULT_NAME, "application/json") ?: return false
        val payload = json.encodeToString(dto)
        return runCatching {
            ctx.contentResolver.openOutputStream(target, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(payload) }
            true
        }.getOrDefault(false)
    }

    suspend fun restoreFromVaultStream(stream: InputStream, repo: Repo): Int {
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dto = json.decodeFromString<StrideCoachVaultDto>(raw)
        if (dto.runs.isNotEmpty()) repo.saveRuns(dto.runs)
        if (dto.walkLogs.isNotEmpty()) repo.mergeWalkLogs(dto.walkLogs)
        dto.activePlan?.let { cfg -> repo.activatePlan(cfg, TrainingPlanGenerator().generate(cfg)) }
        dto.templates.forEach { repo.saveWorkoutTemplate(it) }
        return dto.runs.size
    }

    suspend fun syncFolderNow(ctx: Context, repo: Repo, prefs: PrefsStore, forceAll: Boolean = false): FolderSyncSummary {
        val p = prefs.preferences.first()
        val tree = p.syncFolderUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return FolderSyncSummary()
        val docs = listChildren(ctx, tree)
        val restored = restoreVaultIfNeeded(ctx, repo, docs)
        val existing = repo.getAllRunsWithSplits()
        val acc = SyncAcc(p.lastTimelineFileModMillis)

        for (doc in docs) {
            if (doc.name.equals(VAULT_NAME, ignoreCase = true)) continue
            if (!forceAll && doc.modMs <= p.lastTimelineFileModMillis) continue
            processDoc(ctx, repo, doc, existing, acc)
        }

        val saved = writeVaultSnapshot(ctx, repo, p.syncFolderUri)
        val now = System.currentTimeMillis()
        prefs.update { cur -> cur.copy(lastFolderSyncMillis = now, lastTimelineFileModMillis = maxOf(cur.lastTimelineFileModMillis, acc.maxMod)) }
        return FolderSyncSummary(acc.tlFiles, acc.bakFiles, acc.addedActs, acc.addedWalks, saved, restored, acc.maxMod)
    }

    private suspend fun restoreVaultIfNeeded(ctx: Context, repo: Repo, docs: List<ChildDoc>): Boolean {
        if (repo.getAllRunsForVault().isNotEmpty()) return false
        val entry = docs.firstOrNull { it.name.equals(VAULT_NAME, ignoreCase = true) } ?: return false
        val count = runCatching { ctx.contentResolver.openInputStream(entry.uri)?.use { restoreFromVaultStream(it, repo) } ?: 0 }.getOrDefault(0)
        return count > 0
    }

    private suspend fun processDoc(ctx: Context, repo: Repo, doc: ChildDoc, existing: List<RunRecord>, acc: SyncAcc) {
        val lower = doc.name.lowercase()
        if (lower.endsWith(".backup")) {
            importBackupDoc(ctx, repo, doc, acc)
            return
        }
        if (!lower.endsWith(".json")) return
        when (bak.detectImportKind(peekHeader(ctx, doc.uri))) {
            ImportFileKind.GOOGLE_TIMELINE -> importTimelineDoc(ctx, repo, doc, existing, acc)
            ImportFileKind.RUNNING_TRACKER_BACKUP -> importBackupDoc(ctx, repo, doc, acc)
            else -> Unit
        }
    }

    private suspend fun importBackupDoc(ctx: Context, repo: Repo, doc: ChildDoc, acc: SyncAcc) {
        val parsed = runCatching { ctx.contentResolver.openInputStream(doc.uri)?.use { bak.importBackupStream(it) } }.getOrNull().orEmpty()
        if (parsed.isNotEmpty()) {
            acc.addedActs += repo.saveRunsDeduplicated(parsed)
            acc.bakFiles++
        }
        if (doc.modMs > acc.maxMod) acc.maxMod = doc.modMs
    }

    private suspend fun importTimelineDoc(ctx: Context, repo: Repo, doc: ChildDoc, existing: List<RunRecord>, acc: SyncAcc) {
        val sum = runCatching { ctx.contentResolver.openInputStream(doc.uri)?.use { tl.importTimelineStream(it, existing) } }.getOrNull()
        if (sum != null) {
            if (sum.walkLogs.isNotEmpty()) {
                repo.mergeWalkLogs(sum.walkLogs)
                acc.addedWalks += sum.walkLogs.size
            }
            if (sum.activityRecords.isNotEmpty()) acc.addedActs += repo.saveRunsDeduplicated(sum.activityRecords)
            acc.tlFiles++
        }
        if (doc.modMs > acc.maxMod) acc.maxMod = doc.modMs
    }

    private fun listChildren(ctx: Context, tree: Uri): List<ChildDoc> {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val proj = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)
        val out = mutableListOf<ChildDoc>()
        runCatching { ctx.contentResolver.query(uri, proj, null, null, null)?.use { c -> collectDocs(c, tree, out) } }
        return out
    }

    private fun collectDocs(c: android.database.Cursor, tree: Uri, out: MutableList<ChildDoc>) {
        while (c.moveToNext()) {
            val id = c.getString(0)
            val name = c.getString(1) ?: continue
            if (c.getString(3) == Document.MIME_TYPE_DIR) continue
            out.add(ChildDoc(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(2)))
        }
    }

    private fun findOrCreateChild(ctx: Context, tree: Uri, name: String, mime: String): Uri? {
        listChildren(ctx, tree).firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.uri }
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return runCatching { DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name) }.getOrNull()
    }

    private fun peekHeader(ctx: Context, uri: Uri): String {
        val buf = ByteArray(4096)
        val n = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.read(buf) } ?: 0 }.getOrDefault(0)
        return if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
    }
}
