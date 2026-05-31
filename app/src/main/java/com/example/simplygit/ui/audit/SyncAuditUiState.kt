package com.example.simplygit.ui.audit

import com.example.simplygit.domain.model.SyncLogModel

data class SyncAuditUiState(
    val rows: List<SyncLogModel> = emptyList(),
    val exporting: Boolean = false,
    val exportResult: ExportResult? = null,
)

sealed interface ExportResult {
    data class Success(val path: String, val uri: android.net.Uri) : ExportResult
    data class Failure(val message: String) : ExportResult
}

data class SyncAuditDetailUiState(
    val row: SyncLogModel? = null,
    val loading: Boolean = true,
)
