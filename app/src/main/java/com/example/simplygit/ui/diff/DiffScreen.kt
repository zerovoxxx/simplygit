package com.example.simplygit.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R
import com.example.simplygit.domain.model.DiffLine
import com.example.simplygit.domain.model.DiffLineKind
import com.example.simplygit.domain.model.DiffSource

/**
 * Unified-diff view (SPEC §4.2.2 Iteration 3).
 *
 * Rendering notes:
 *  - LazyColumn keyed on `(oldLineNo, newLineNo, index)` — `index` breaks
 *    ties where a header / context line has `null` line numbers.
 *  - Row-level background colour comes from a small `when` — no runtime
 *    drawing logic, so `drawBehind` is avoided.
 *  - FontFamily.Monospace for the line content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(
    repoId: Long,
    path: String,
    source: DiffSource,
    onBack: () -> Unit,
    viewModel: DiffViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(repoId, path, source) { viewModel.load(repoId, path, source) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = path,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.kind == DiffPresentationKind.BINARY -> BinaryNotice(
                    oursSize = uiState.binaryOursSize,
                    theirsSize = uiState.binaryTheirsSize,
                )

                uiState.kind == DiffPresentationKind.FAILED -> FailedNotice(
                    key = uiState.failureMessageKey ?: "diff_failed_unknown",
                )

                else -> DiffList(
                    lines = uiState.lines,
                    truncated = uiState.kind == DiffPresentationKind.TRUNCATED,
                    totalLines = uiState.totalLines,
                    shownLines = uiState.shownLines,
                )
            }
        }
    }
}

@Composable
private fun DiffList(
    lines: List<DiffLine>,
    truncated: Boolean,
    totalLines: Int,
    shownLines: Int,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (truncated) {
            Surface(color = Color(0xFFFFE0B2), modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.diff_truncated_banner, shownLines, totalLines),
                    color = Color(0xFF1F1F1F),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                count = lines.size,
                key = { idx -> "diff_line_$idx" },
            ) { idx ->
                DiffRow(lines[idx])
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val bg = when (line.kind) {
        DiffLineKind.ADDED -> Color(0xFFDCEDC8)
        DiffLineKind.REMOVED -> Color(0xFFFFCDD2)
        DiffLineKind.HUNK_HEADER -> Color(0xFFECEFF1)
        DiffLineKind.CONTEXT -> Color.Transparent
        DiffLineKind.NO_NEWLINE -> Color(0xFFFFF9C4)
    }
    val prefix = when (line.kind) {
        DiffLineKind.ADDED -> "+"
        DiffLineKind.REMOVED -> "-"
        DiffLineKind.CONTEXT -> " "
        DiffLineKind.HUNK_HEADER -> "@"
        DiffLineKind.NO_NEWLINE -> "\\"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = (line.oldLineNo?.toString() ?: "").padStart(4),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp),
        )
        Text(
            text = (line.newLineNo?.toString() ?: "").padStart(4),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp),
        )
        Text(
            text = "$prefix ${line.content}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BinaryNotice(oursSize: Long, theirsSize: Long) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.diff_binary, oursSize, theirsSize))
    }
}

@Composable
private fun FailedNotice(key: String) {
    val resId = when (key) {
        "diff_failed_file_missing" -> R.string.diff_failed_file_missing
        "diff_failed_encoding" -> R.string.diff_failed_encoding
        "diff_failed_permission" -> R.string.diff_failed_permission
        else -> R.string.diff_failed_unknown
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(resId), color = Color(0xFFB71C1C))
    }
}
