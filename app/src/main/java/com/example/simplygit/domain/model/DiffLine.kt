package com.example.simplygit.domain.model

/**
 * A single line in a unified-diff rendering (SPEC §4.2.1 Iteration 3).
 *
 * Parsed from JGit `DiffFormatter` output inside the Data layer — callers
 * never touch JGit directly.
 */
data class DiffLine(
    val kind: DiffLineKind,
    val oldLineNo: Int?,
    val newLineNo: Int?,
    val content: String,
)

enum class DiffLineKind {
    ADDED,
    REMOVED,
    CONTEXT,
    NO_NEWLINE,
    HUNK_HEADER,
}
