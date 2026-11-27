package com.stridecoach.running.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.ui.components.StatusPill
import com.stridecoach.running.ui.components.StrideCard
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.PaceTooSlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TrashScreen(
    trashedRuns: List<RunRecord>,
    trashedTemplates: List<StructuredWorkout>,
    onBack: () -> Unit,
    onRestoreRun: (String) -> Unit,
    onDeleteRunForever: (String) -> Unit,
    onRestoreTemplate: (String) -> Unit,
    onDeleteTemplateForever: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onEmptyTrash: () -> Unit
) {
    val total = trashedRuns.size + trashedTemplates.size
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()) }

    Column(
        modifier = Modifier.fillMaxSize().background(KdeBg).statusBarsPadding().navigationBarsPadding()
    ) {
        DrillTopBar("Trash & Recovery ($total)", onBack)
        if (total > 0) TrashBulkActions(onRestoreAll, onEmptyTrash)
        TrashItemsList(trashedRuns, trashedTemplates, fmt, onRestoreRun, onDeleteRunForever, onRestoreTemplate, onDeleteTemplateForever)
    }
}

@Composable
private fun TrashItemsList(
    trashedRuns: List<RunRecord>,
    trashedTemplates: List<StructuredWorkout>,
    fmt: DateTimeFormatter,
    onRestoreRun: (String) -> Unit,
    onDeleteRunForever: (String) -> Unit,
    onRestoreTemplate: (String) -> Unit,
    onDeleteTemplateForever: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (trashedRuns.isEmpty() && trashedTemplates.isEmpty()) {
            item("empty") { EmptyTrashCard() }
        }
        if (trashedRuns.isNotEmpty()) {
            item("runs_hdr") { TrashSectionHeader("DELETED ACTIVITIES (${trashedRuns.size})") }
            items(trashedRuns, key = { "tr_${it.id}" }) { r ->
                TrashedRunCard(r, fmt, onRestoreRun, onDeleteRunForever)
            }
        }
        if (trashedTemplates.isNotEmpty()) {
            item("tpl_hdr") { TrashSectionHeader("DELETED WORKOUT TEMPLATES (${trashedTemplates.size})") }
            items(trashedTemplates, key = { "tt_${it.id}" }) { t ->
                TrashedTemplateCard(t, onRestoreTemplate, onDeleteTemplateForever)
            }
        }
    }
}

@Composable
private fun TrashedRunTitle(tag: String, name: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(tag, KdePurple)
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyTrashCard() {
    StrideCard {
        Text(
            "Trash is empty. Deleted runs, walks, rides, and workout templates appear here so you can restore them anytime.",
            style = MaterialTheme.typography.bodySmall,
            color = KdeMuted
        )
    }
}

@Composable
private fun TrashSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
}

@Composable
private fun TrashBulkActions(onRestoreAll: () -> Unit, onEmptyTrash: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = onRestoreAll, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
            Text("Restore All", maxLines = 1, softWrap = false)
        }
        OutlinedButton(onClick = onEmptyTrash, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
            Text("Empty Trash", color = PaceTooSlow, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun TrashedRunCard(
    r: RunRecord,
    fmt: DateTimeFormatter,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val deletedAt = r.deletedAtMillis?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: ""
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                TrashedRunTitle(r.activityKind.singularTag, r.name)
                Text(
                    "${"%.2f".format(r.km())} km • ${formatDuration(r.durationMillis)} • Deleted $deletedAt",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdeMuted
                )
            }
            TrashRowButtons("Restore", "Delete Forever", { onRestore(r.id) }, { onDelete(r.id) })
        }
    }
}

@Composable
private fun TrashedTemplateCard(
    t: StructuredWorkout,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    StrideCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TrashRowButtons("Restore Template", "Delete Forever", { onRestore(t.id) }, { onDelete(t.id) })
        }
    }
}

@Composable
private fun TrashRowButtons(restoreDesc: String, deleteDesc: String, onRestore: () -> Unit, onDelete: () -> Unit) {
    Row {
        IconButton(onClick = onRestore) { Icon(Icons.Default.RestoreFromTrash, contentDescription = restoreDesc, tint = PaceOnTarget) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteForever, contentDescription = deleteDesc, tint = PaceTooSlow) }
    }
}
