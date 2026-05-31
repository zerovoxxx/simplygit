package com.example.simplygit.ui.diff

import com.example.simplygit.domain.model.DiffLine
import com.example.simplygit.domain.model.DiffSource

/** UI state for [DiffScreen] (SPEC §4.2.2 Iteration 3). */
data class DiffUiState(
    val repoId: Long = 0L,
    val path: String = "",
    val source: DiffSource = DiffSource.WORKING_VS_HEAD,
    val loading: Boolean = true,
    val kind: DiffPresentationKind = DiffPresentationKind.FULL,
    val lines: List<DiffLine> = emptyList(),
    val totalLines: Int = 0,
    val shownLines: Int = 0,
    val binaryOursSize: Long = 0L,
    val binaryTheirsSize: Long = 0L,
    val failureMessageKey: String? = null,
)

enum class DiffPresentationKind {
    FULL,
    TRUNCATED,
    BINARY,
    FAILED,
}
