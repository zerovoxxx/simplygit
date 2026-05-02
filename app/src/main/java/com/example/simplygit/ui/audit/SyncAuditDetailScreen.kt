package com.example.simplygit.ui.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R
import com.example.simplygit.domain.model.SyncLogModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncAuditDetailScreen(
    logId: Long,
    onBack: () -> Unit,
    viewModel: SyncAuditDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(logId) { viewModel.load(logId) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audit_detail_title)) },
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
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val row = state.row
            if (row == null) {
                Text(stringResource(R.string.audit_empty))
            } else {
                DetailRow(row)
            }
        }
    }
}

@Composable
private fun DetailRow(row: SyncLogModel) {
    val start = TS_FMT.format(row.startedAt.atZone(ZoneId.systemDefault()))
    val end = row.endedAt?.let { TS_FMT.format(it.atZone(ZoneId.systemDefault())) }
    Text("id = ${row.id}")
    Text("repoId = ${row.repoId}")
    Text("trigger = ${row.trigger.name}")
    Text("result = ${row.result?.name ?: "(running)"}")
    Text("startedAt = $start")
    Text("endedAt = ${end ?: "-"}")
    Text(
        text = stringResource(
            R.string.audit_row_summary,
            row.commitsPulled,
            row.commitsPushed,
            row.filesChanged,
        ),
    )
    row.conflictClass?.let {
        Text(stringResource(R.string.audit_conflict_tag, it.displayLabel()))
    }
    // SPEC §4.7 Iteration 2 / fix CR P3-02: surface the original Throwable
    // class name next to the sanitized message so engineers can distinguish
    // TransportException / UnknownHostException / IOException etc.
    row.errorType?.let {
        Text(stringResource(R.string.audit_error_type_tag, it))
    }
    row.errorMsg?.let {
        Text(stringResource(R.string.audit_error_tag, it))
    }
}

private val TS_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
