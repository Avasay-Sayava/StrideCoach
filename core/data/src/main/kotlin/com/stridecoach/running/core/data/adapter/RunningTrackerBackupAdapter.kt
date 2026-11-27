package com.stridecoach.running.core.data.adapter

import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.RunSource
import com.stridecoach.running.core.domain.model.RunSplit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import kotlin.math.roundToInt

enum class ImportFileKind {
    RUNNING_TRACKER_BACKUP,
    GOOGLE_TIMELINE,
    UNKNOWN
}

@Serializable
data class BackupDto(
    val version: String = "4.1.0",
    val createdAt: String = "",
    val runs: List<BackupRunDto> = emptyList()
)

@Serializable
data class BackupRunDto(
    val startTimestamp: Long,
    val distanceMeters: Int,
    val durationMillis: Long,
    val paceSecondsKM: Int = 0,
    val caloriesBurned: Int = 0,
    val name: String = "",
    val description: String = "",
    val path: String = "",
    val splits: List<BackupSplitDto> = emptyList()
)

@Serializable
data class BackupSplitDto(
    val splitNumber: Int,
    val distanceMeters: Int,
    val durationMillis: Long
)

class RunningTrackerBackupAdapter(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
) {
    fun importBackup(raw: String): List<RunRecord> {
        require(raw.isNotBlank()) { "Backup JSON is empty" }
        val dto = json.decodeFromString<BackupDto>(raw)
        return dto.runs.map(::toRunRecord)
    }

    fun importBackupStream(stream: InputStream): List<RunRecord> {
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return importBackup(raw)
    }

    fun exportBackup(runs: List<RunRecord>, nowMs: Long = System.currentTimeMillis()): String {
        val out = BackupDto(
            version = "4.1.0",
            createdAt = nowMs.toString(),
            runs = runs.sortedByDescending { it.startTimestampMillis }.map(::toRunDto)
        )
        return json.encodeToString(out)
    }

    fun detectImportKind(head: String): ImportFileKind = when {
        head.contains("\"semanticSegments\"") || head.contains("\"rawSignals\"") -> ImportFileKind.GOOGLE_TIMELINE
        head.contains("\"runs\"") && head.contains("\"startTimestamp\"") -> ImportFileKind.RUNNING_TRACKER_BACKUP
        head.contains("\"version\"") && head.contains("\"runs\"") -> ImportFileKind.RUNNING_TRACKER_BACKUP
        else -> ImportFileKind.UNKNOWN
    }

    private fun toRunRecord(r: BackupRunDto): RunRecord {
        val sp = r.splits.map { RunSplit(it.splitNumber, it.distanceMeters, it.durationMillis) }
        val pts = EncodedPolylineCodec.synthesizeReplayTrack(r.path, r.startTimestamp, r.durationMillis, sp)
        val pace = resolvePace(r.paceSecondsKM, r.distanceMeters, r.durationMillis)
        return RunRecord(
            id = "rt_${r.startTimestamp}",
            startTimestampMillis = r.startTimestamp,
            distanceMeters = r.distanceMeters,
            durationMillis = r.durationMillis,
            paceSecondsPerKm = pace,
            caloriesBurned = r.caloriesBurned,
            name = r.name.ifBlank { "Imported Run" },
            description = r.description,
            encodedPolyline = r.path,
            rawEncodedPolyline = r.path,
            splits = sp,
            trackPoints = pts,
            source = RunSource.BACKUP_IMPORT
        )
    }

    private fun resolvePace(rawPace: Int, distM: Int, durMs: Long): Int {
        if (rawPace > 0) return rawPace
        if (distM <= 0 || durMs <= 0L) return 0
        return ((durMs / 1000.0) / (distM / 1000.0)).roundToInt()
    }

    private fun toRunDto(r: RunRecord): BackupRunDto = BackupRunDto(
        startTimestamp = r.startTimestampMillis,
        distanceMeters = r.distanceMeters,
        durationMillis = r.durationMillis,
        paceSecondsKM = r.paceSecondsPerKm,
        caloriesBurned = r.caloriesBurned,
        name = r.name,
        description = r.description,
        path = r.encodedPolyline,
        splits = r.splits.map { BackupSplitDto(it.splitNumber, it.distanceMeters, it.durationMillis) }
    )
}
