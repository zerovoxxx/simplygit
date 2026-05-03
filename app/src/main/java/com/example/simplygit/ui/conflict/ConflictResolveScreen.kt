package com.example.simplygit.ui.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R
import com.example.simplygit.domain.model.ConflictFile
import com.example.simplygit.domain.model.ConflictFileKind
import com.example.simplygit.domain.model.ResolutionChoice

/**
 * Integer-file conflict-resolve screen (SPEC §4.3.2 Iteration 3).
 *
 * Flow:
 *  - Load conflicts via ViewModel;
 *  - User picks Ours / Theirs / Skip per row (default Ours);
 *  - "Commit all" → if any Skip, a second-confirm dialog appears;
 *  - Submission dispatches `ResolveConflictUseCase`;
 *  - The result surface is driven by [SubmissionResult] — success closes
 *    and pops, partial surfaces a failure list + retry link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolveScreen(
    repoId: Long,
    onBack: () -> Unit,
    onPreviewDiff: (path: String) -> Unit,
    viewModel: ConflictResolveViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var confirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(repoId) { viewModel.load(repoId) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.conflict_title_count, uiState.files.size))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            if (uiState.files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.conflict_none))
                }
                return@Column
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(uiState.files, key = { it.path }) { file ->
                    ConflictRow(
                        file = file,
                        choice = uiState.selections[file.path] ?: ResolutionChoice.KEEP_OURS,
                        onSelect = { viewModel.onSelect(file.path, it) },
                        onPreview = { onPreviewDiff(file.path) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { confirmDialog = true },
                    enabled = !uiState.submitting,
                ) {
                    if (uiState.submitting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(stringResource(R.string.action_commit_all))
                }
            }
        }
    }

    if (confirmDialog) {
        val skipCount = uiState.selections.count { it.value == ResolutionChoice.SKIP }
        AlertDialog(
            onDismissRequest = { confirmDialog = false },
            title = { Text(stringResource(R.string.conflict_confirm_title)) },
            text = {
                Text(
                    if (skipCount > 0) {
                        stringResource(R.string.conflict_confirm_body_with_skip, skipCount)
                    } else {
                        stringResource(R.string.conflict_confirm_body)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDialog = false
                    viewModel.submit()
                }) { Text(stringResource(R.string.conflict_confirm_positive)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDialog = false }) {
                    Text(stringResource(R.string.conflict_confirm_negative))
                }
            },
        )
    }

    uiState.result?.let { result ->
        ResultDialog(
            result = result,
            onDismiss = {
                viewModel.dismissResult()
                if (result is SubmissionResult.Succeeded && result.remainingSkipped == 0 && result.pushOk) {
                    onBack()
                }
            },
            onRetry = { viewModel.submit() },
        )
    }
}

@Composable
private fun ConflictRow(
    file: ConflictFile,
    choice: ResolutionChoice,
    onSelect: (ResolutionChoice) -> Unit,
    onPreview: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = kindBadge(file.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6A1B9A),
                )
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPreview) {
                    Text(stringResource(R.string.conflict_preview_diff))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChoiceRadio(
                    selected = choice == ResolutionChoice.KEEP_OURS,
                    label = stringResource(R.string.conflict_choice_ours),
                    onClick = { onSelect(ResolutionChoice.KEEP_OURS) },
                )
                ChoiceRadio(
                    selected = choice == ResolutionChoice.TAKE_THEIRS,
                    label = stringResource(R.string.conflict_choice_theirs),
                    onClick = { onSelect(ResolutionChoice.TAKE_THEIRS) },
                )
                ChoiceRadio(
                    selected = choice == ResolutionChoice.SKIP,
                    label = stringResource(R.string.conflict_choice_skip),
                    onClick = { onSelect(ResolutionChoice.SKIP) },
                )
            }
        }
    }
}

@Composable
private fun ChoiceRadio(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun kindBadge(kind: ConflictFileKind): String = when (kind) {
    ConflictFileKind.TEXT -> stringResource(R.string.conflict_kind_text)
    ConflictFileKind.BINARY -> stringResource(R.string.conflict_kind_binary)
    ConflictFileKind.DELETE_VS_MODIFY -> stringResource(R.string.conflict_kind_delete_modify)
}

@Composable
private fun ResultDialog(
    result: SubmissionResult,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val (title, body, showRetry) = when (result) {
        is SubmissionResult.Succeeded -> Triple(
            stringResource(R.string.conflict_result_success_title),
            when {
                !result.pushOk -> stringResource(
                    R.string.conflict_result_push_failed,
                    result.committedFiles,
                )
                result.remainingSkipped > 0 -> stringResource(
                    R.string.conflict_result_partial,
                    result.committedFiles,
                    result.remainingSkipped,
                )
                else -> stringResource(
                    R.string.conflict_result_fully_done,
                    result.committedFiles,
                )
            },
            !result.pushOk,
        )
        is SubmissionResult.PartialFailed -> Triple(
            stringResource(R.string.conflict_result_partial_fail_title),
            stringResource(
                R.string.conflict_result_partial_fail_body,
                result.failedPaths.size,
            ),
            true,
        )
        SubmissionResult.Failed -> Triple(
            stringResource(R.string.conflict_result_failed_title),
            stringResource(R.string.conflict_result_failed_body),
            false,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            if (showRetry) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.conflict_retry_push)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_ok)) }
            }
        },
        dismissButton = if (showRetry) {
            { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.conflict_close)) } }
        } else null,
    )
}
