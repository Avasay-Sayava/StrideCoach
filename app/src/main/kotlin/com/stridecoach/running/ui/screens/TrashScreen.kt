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
    val totalCount = trashedRuns.size + trashedTemplates.size
    val dtFmt = remember { DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KdeBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        DrillTopBar("Trash & Recovery ($totalCount)", onBack)
        if (totalCount > 0) {
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (totalCount == 0) {
                item("empty") {
                    StrideCard {
                        Text(
                            "Trash is empty. Deleted runs, walks, rides, and workout templates appear here so you can restore them anytime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KdeMuted
                        )
                    }
                }
            }
            if (trashedRuns.isNotEmpty()) {
                item("runs_hdr") {
                    Text("DELETED ACTIVITIES (${trashedRuns.size})", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
                }
                items(trashedRuns, key = { "tr_${it.id}" }) { r ->
                    StrideCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatusPill(r.activityKind.singularTag, KdePurple)
                                    Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(
                                    "${"%.2f".format(r.km())} km • ${formatDuration(r.durationMillis)} • Deleted ${r.deletedAtMillis?.let { dtFmt.format(Instant.ofEpochMilli(it)) } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KdeMuted
                                )
                            }
                            Row {
                                IconButton(onClick = { onRestoreRun(r.id) }) {
                                    Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore", tint = PaceOnTarget)
                                }
                                IconButton(onClick = { onDeleteRunForever(r.id) }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Delete Forever", tint = PaceTooSlow)
                                }
                            }
                        }
                    }
                }
            }
            if (trashedTemplates.isNotEmpty()) {
                item("tpl_hdr") {
                    Text("DELETED WORKOUT TEMPLATES (${trashedTemplates.size})", style = MaterialTheme.typography.labelLarge, color = KdeMuted, fontWeight = FontWeight.Bold)
                }
                items(trashedTemplates, key = { "tt_${it.id}" }) { t ->
                    StrideCard {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Row {
                                IconButton(onClick = { onRestoreTemplate(t.id) }) {
                                    Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore Template", tint = PaceOnTarget)
                                }
                                IconButton(onClick = { onDeleteTemplateForever(t.id) }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Delete Forever", tint = PaceTooSlow)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
