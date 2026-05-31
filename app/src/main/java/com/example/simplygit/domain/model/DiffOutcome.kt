package com.example.simplygit.domain.model

/**
 * Result of a diff request (SPEC §4.2.1 Iteration 3).
 *
 * Split into a sealed hierarchy so each state carries only the fields that
 * semantically apply (G6 single-semantic field rule).
 */
sealed interface DiffOutcome {
    /** Full parse: every line of the diff fits under the downgrade threshold. */
    data class Full(val lines: List<DiffLine>) : DiffOutcome

    /**
     * Diff exceeded the line-count threshold; only a prefix was parsed.
     *  - [shownLines] equals `lines.size` (≤ 5,000 per SPEC §4.2.1).
     *  - [totalLines] is an estimate derived from bytes / avg line length.
     */
    data class Truncated(
        val lines: List<DiffLine>,
        val totalLines: Int,
        val shownLines: Int,
    ) : DiffOutcome

    /** Content is binary; show a size summary instead of trying to render. */
    data class Binary(val oursSize: Long, val theirsSize: Long) : DiffOutcome

    /** A recoverable failure — carry a tagged reason so UI can pick localised copy. */
    data class Failed(val reason: DiffFailure) : DiffOutcome
}

/** Reasons a diff cannot be produced (SPEC §4.2.1 / R8). */
enum class DiffFailure {
    FILE_MISSING,
    ENCODING_UNSUPPORTED,
    PERMISSION_LOST,
    UNKNOWN,
}
