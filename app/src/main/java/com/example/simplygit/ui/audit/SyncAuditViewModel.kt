package com.example.simplygit.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simplygit.domain.repository.SyncLogRepository
import com.example.simplygit.domain.usecase.ExportLogsUseCase
import com.example.simplygit.domain.usecase.LoadSyncLogUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncAuditViewModel @Inject constructor(
    private val logRepo: SyncLogRepository,
    private val exportLogs: ExportLogsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncAuditUiState())
    val state: StateFlow<SyncAuditUiState> = _state.asStateFlow()

    init {
        logRepo.observeRecent(limit = AUDIT_RECENT_LIMIT).onEach { rows ->
            _state.value = _state.value.copy(rows = rows)
        }.launchIn(viewModelScope)
    }

    fun requestExport() {
        if (_state.value.exporting) return
        _state.value = _state.value.copy(exporting = true, exportResult = null)
        viewModelScope.launch {
            val result = runCatching { exportLogs() }
            _state.value = _state.value.copy(
                exporting = false,
                exportResult = result.fold(
                    onSuccess = { ExportResult.Success(it.displayPath, it.uri) },
                    onFailure = { ExportResult.Failure(it.javaClass.simpleName) },
                ),
            )
        }
    }

    fun clearExportResult() {
        _state.value = _state.value.copy(exportResult = null)
    }

    private companion object {
        const val AUDIT_RECENT_LIMIT = 30
    }
}

@HiltViewModel
class SyncAuditDetailViewModel @Inject constructor(
    private val loadLog: LoadSyncLogUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncAuditDetailUiState())
    val state: StateFlow<SyncAuditDetailUiState> = _state.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val row = loadLog(id)
            _state.value = SyncAuditDetailUiState(row = row, loading = false)
        }
    }
}
