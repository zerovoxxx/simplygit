package com.example.simplygit.ui.audit

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R
import com.example.simplygit.domain.model.ConflictClass
import com.example.simplygit.domain.model.SyncLogModel
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncTrigger
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun SyncAuditScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    viewModel: SyncAuditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // SPEC G9: when an export finishes successfully, hand off to ACTION_SEND
    // with the FileProvider content:// URI.
    LaunchedEffect(state.exportResult) {
        val result = state.exportResult
        if (result is ExportResult.Success) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, result.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(send, context.getString(R.string.action_export_logs)),
                )
            }
            viewModel.clearExportResult()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (state.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.audit_empty),
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.id }) { row ->
                        AuditRow(row = row, onClick = { onOpenDetail(row.id) })
                        HorizontalDivider()
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    enabled = !state.exporting,
                    onClick = { showConfirm = true },
                ) {
                    Text(stringResource(R.string.action_export_logs))
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.export_confirm_title)) },
            text = { Text(stringResource(R.string.export_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    viewModel.requestExport()
                }) { Text(stringResource(R.string.export_confirm_positive)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.export_confirm_negative))
                }
            },
        )
    }

    val failure = state.exportResult as? ExportResult.Failure
    if (failure != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearExportResult,
            title = { Text(stringResource(R.string.action_export_logs)) },
            text = { Text(stringResource(R.string.export_failure, failure.message)) },
            confirmButton = {
                TextButton(onClick = viewModel::clearExportResult) {
                    Text(stringResource(R.string.resume_confirm_negative))
                }
            },
        )
    }
}

@Composable
private fun AuditRow(row: SyncLogModel, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val startedText = TS_FMT.format(row.startedAt.atZone(ZoneId.systemDefault()))
        val endedText = row.endedAt?.let { TS_FMT.format(it.atZone(ZoneId.systemDefault())) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (endedText == null) {
                    stringResource(R.string.audit_row_time_running, startedText)
                } else {
                    stringResource(R.string.audit_row_time, startedText, endedText)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(row.resultLabel(), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = stringResource(
                R.string.audit_row_summary,
                row.commitsPulled,
                row.commitsPushed,
                row.filesChanged,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        val trigger = when (row.trigger) {
            SyncTrigger.PERIODIC -> stringResource(R.string.audit_trigger_periodic)
            SyncTrigger.MANUAL -> stringResource(R.string.audit_trigger_manual)
            SyncTrigger.CATCHUP -> stringResource(R.string.audit_trigger_catchup)
        }
        Text(trigger, style = MaterialTheme.typography.labelSmall)
        if (row.conflictClass != null) {
            Text(
                text = stringResource(R.string.audit_conflict_tag, row.conflictClass.displayLabel()),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun SyncLogModel.resultLabel(): String = when (result) {
    SyncResult.OK -> stringResource(R.string.audit_result_ok)
    SyncResult.CONFLICT -> stringResource(R.string.audit_result_conflict)
    SyncResult.NETWORK_ERR -> stringResource(R.string.audit_result_network)
    SyncResult.AUTH_ERR -> stringResource(R.string.audit_result_auth)
    SyncResult.FS_ERR -> stringResource(R.string.audit_result_fs)
    SyncResult.ABORTED -> stringResource(R.string.audit_result_aborted)
    SyncResult.SKIPPED_DEBOUNCE -> stringResource(R.string.audit_result_skipped_debounce)
    SyncResult.SKIPPED_PAUSED -> stringResource(R.string.audit_result_skipped_paused)
    null -> stringResource(R.string.status_working, "SYNC")
}

@Composable
internal fun ConflictClass.displayLabel(): String = stringResource(
    when (this) {
        ConflictClass.FAST_FORWARD -> R.string.conflict_class_fast_forward
        ConflictClass.AUTO_MERGED -> R.string.conflict_class_auto_merged
        ConflictClass.TEXT_LINE_CONFLICT -> R.string.conflict_class_text_line
        ConflictClass.BINARY_CONFLICT -> R.string.conflict_class_binary
        ConflictClass.DELETE_MODIFY -> R.string.conflict_class_delete_modify
        ConflictClass.REMOTE_REWRITE -> R.string.conflict_class_remote_rewrite
    },
)

private val TS_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)
