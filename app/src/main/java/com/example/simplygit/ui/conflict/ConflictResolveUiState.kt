package com.example.simplygit.ui.conflict

import com.example.simplygit.domain.model.ConflictFile
import com.example.simplygit.domain.model.ResolutionChoice

/** UI state for [ConflictResolveScreen] (SPEC §4.3.2 Iteration 3). */
data class ConflictResolveUiState(
    val repoId: Long = 0L,
    val loading: Boolean = true,
    val files: List<ConflictFile> = emptyList(),
    /** path → selection; defaults to KEEP_OURS when the user has not picked yet. */
    val selections: Map<String, ResolutionChoice> = emptyMap(),
    val submitting: Boolean = false,
    val result: SubmissionResult? = null,
)

sealed interface SubmissionResult {
    data class Succeeded(
        val committedFiles: Int,
        val pushOk: Boolean,
        val remainingSkipped: Int,
    ) : SubmissionResult
    data class PartialFailed(val failedPaths: List<String>) : SubmissionResult
    data object Failed : SubmissionResult
}
