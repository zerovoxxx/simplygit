package com.example.simplygit.ui.diff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simplygit.domain.model.DiffFailure
import com.example.simplygit.domain.model.DiffOutcome
import com.example.simplygit.domain.model.DiffSource
import com.example.simplygit.domain.repository.DiffRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** ViewModel for [DiffScreen] (SPEC §4.2.2 Iteration 3). */
@HiltViewModel
class DiffViewModel @Inject constructor(
    private val diffRepository: DiffRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiffUiState())
    val uiState: StateFlow<DiffUiState> = _uiState.asStateFlow()

    fun load(repoId: Long, path: String, source: DiffSource) {
        _uiState.update {
            it.copy(repoId = repoId, path = path, source = source, loading = true)
        }
        viewModelScope.launch {
            when (val outcome = diffRepository.diff(repoId, path, source)) {
                is DiffOutcome.Full -> _uiState.update {
                    it.copy(
                        loading = false,
                        kind = DiffPresentationKind.FULL,
                        lines = outcome.lines,
                    )
                }
                is DiffOutcome.Truncated -> _uiState.update {
                    it.copy(
                        loading = false,
                        kind = DiffPresentationKind.TRUNCATED,
                        lines = outcome.lines,
                        totalLines = outcome.totalLines,
                        shownLines = outcome.shownLines,
                    )
                }
                is DiffOutcome.Binary -> _uiState.update {
                    it.copy(
                        loading = false,
                        kind = DiffPresentationKind.BINARY,
                        binaryOursSize = outcome.oursSize,
                        binaryTheirsSize = outcome.theirsSize,
                    )
                }
                is DiffOutcome.Failed -> _uiState.update {
                    it.copy(
                        loading = false,
                        kind = DiffPresentationKind.FAILED,
                        failureMessageKey = outcome.reason.localisationKey(),
                    )
                }
            }
        }
    }

    private fun DiffFailure.localisationKey(): String = when (this) {
        DiffFailure.FILE_MISSING -> "diff_failed_file_missing"
        DiffFailure.ENCODING_UNSUPPORTED -> "diff_failed_encoding"
        DiffFailure.PERMISSION_LOST -> "diff_failed_permission"
        DiffFailure.UNKNOWN -> "diff_failed_unknown"
    }
}
