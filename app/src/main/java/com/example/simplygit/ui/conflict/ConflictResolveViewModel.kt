package com.example.simplygit.ui.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simplygit.domain.model.ResolutionChoice
import com.example.simplygit.domain.model.ResolveRequest
import com.example.simplygit.domain.model.ResolveResult
import com.example.simplygit.domain.repository.ConflictRepository
import com.example.simplygit.domain.usecase.ResolveConflictUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [ConflictResolveScreen] (SPEC §4.3.2 Iteration 3).
 *
 * State survives a process death via the standard saved-state machinery
 * (TODO for future iterations — the current implementation keeps selections
 * in-memory only; aligns with SPEC §3.1.3's "中断再回列表时标识已选" which
 * depends on the user still being on-screen).
 */
@HiltViewModel
class ConflictResolveViewModel @Inject constructor(
    private val conflictRepository: ConflictRepository,
    private val resolveConflict: ResolveConflictUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConflictResolveUiState())
    val uiState: StateFlow<ConflictResolveUiState> = _uiState.asStateFlow()

    fun load(repoId: Long) {
        _uiState.update { it.copy(repoId = repoId, loading = true, result = null) }
        viewModelScope.launch {
            val files = conflictRepository.listConflicts(repoId)
            _uiState.update { state ->
                state.copy(
                    files = files,
                    loading = false,
                    // Default: keep ours (conservative — user explicit action picks theirs).
                    selections = files.associate { it.path to ResolutionChoice.KEEP_OURS },
                )
            }
        }
    }

    fun onSelect(path: String, choice: ResolutionChoice) {
        _uiState.update { state ->
            state.copy(selections = state.selections + (path to choice))
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.submitting) return
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val req = ResolveRequest(repoId = state.repoId, choices = state.selections)
            val outcome = resolveConflict(req)
            _uiState.update { s ->
                s.copy(
                    submitting = false,
                    result = when (outcome) {
                        is ResolveResult.Success -> SubmissionResult.Succeeded(
                            committedFiles = outcome.committedFiles,
                            pushOk = outcome.pushOk,
                            remainingSkipped = outcome.remainingSkipped,
                        )
                        is ResolveResult.PartialFailure -> SubmissionResult.PartialFailed(
                            outcome.failedPaths,
                        )
                        is ResolveResult.Failure -> SubmissionResult.Failed
                    },
                )
            }
        }
    }

    fun dismissResult() {
        _uiState.update { it.copy(result = null) }
    }
}
