package com.example.simplygit.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.shape.CircleShape
import com.example.simplygit.R
import com.example.simplygit.domain.model.FileTreeNode
import com.example.simplygit.domain.model.FileType
import com.example.simplygit.domain.model.GitFileStatus

/**
 * Flat `LazyColumn` file-tree (SPEC §4.1.2 Iteration 3).
 *
 * Performance notes:
 *  - Keyed by [FileTreeNode.path] so recomposition is skipped for unchanged rows.
 *  - Coloured status dot is a [Surface] with a static colour — no per-frame
 *    redraw logic.
 *  - Directory-first ordering is enforced in DAO's `ORDER BY type DESC`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoBrowserScreen(
    repoId: Long,
    onBack: () -> Unit,
    onOpenDiff: (path: String) -> Unit,
    viewModel: RepoBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(repoId) { viewModel.initialize(repoId) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.triggerRescan() }) {
                        if (uiState.isRescanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Text("↻", style = MaterialTheme.typography.titleLarge)
                        }
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
            if (uiState.noBinding) {
                EmptyStateNoBinding()
                return@Scaffold
            }
            BreadcrumbRow(
                segments = uiState.breadcrumb,
                onNavigateTo = { viewModel.loadPath(it) },
            )
            if (uiState.repoTooLarge) {
                Surface(color = Color(0xFFFFE0B2), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.browser_repo_too_large),
                        color = Color(0xFF1F1F1F),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (uiState.currentPath.isNotEmpty()) {
                    item(key = "__up__") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.navigateUp() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("↑", style = MaterialTheme.typography.titleMedium)
                            Text("..", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                items(
                    items = uiState.currentEntries,
                    key = { node -> "entry_${node.path}" },
                ) { node ->
                    FileTreeRow(
                        node = node,
                        onClick = {
                            if (node.type == FileType.DIR) {
                                viewModel.loadPath(node.path)
                            } else if (node.gitStatus != GitFileStatus.CLEAN) {
                                onOpenDiff(node.path)
                            }
                        },
                    )
                }
                if (uiState.currentEntries.isEmpty() && !uiState.isRescanning) {
                    item(key = "__empty__") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.browser_empty))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateNoBinding() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.browser_no_binding))
    }
}

@Composable
private fun BreadcrumbRow(
    segments: List<BreadcrumbSegment>,
    onNavigateTo: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        segments.forEachIndexed { index, seg ->
            if (index > 0) Text(" > ", style = MaterialTheme.typography.bodySmall)
            Text(
                text = seg.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { onNavigateTo(seg.path) },
            )
        }
    }
}

@Composable
private fun FileTreeRow(node: FileTreeNode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(node.aggregatedStatus)
        Text(
            text = if (node.type == FileType.DIR) "📁 ${node.name}" else node.name,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun StatusDot(status: GitFileStatus) {
    val color = when (status) {
        GitFileStatus.CLEAN -> Color(0xFFBDBDBD)
        GitFileStatus.MODIFIED -> Color(0xFFFFB300)
        GitFileStatus.UNTRACKED -> Color(0xFF64B5F6)
        GitFileStatus.STAGED -> Color(0xFF81C784)
        GitFileStatus.CONFLICT -> Color(0xFFE53935)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape),
    ) {
        Surface(color = color, modifier = Modifier.fillMaxSize()) {}
    }
}
