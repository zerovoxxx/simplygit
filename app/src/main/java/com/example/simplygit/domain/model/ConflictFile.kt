package com.example.simplygit.domain.model

import com.example.simplygit.data.git.SyncErrorKind

/**
 * A single conflicting path surfaced to the resolve screen (SPEC §4.3.1
 * Iteration 3).
 */
data class ConflictFile(
    val path: String,
    val kind: ConflictFileKind,
    val oursSize: Long,
    val theirsSize: Long,
)

enum class ConflictFileKind {
    TEXT,
    BINARY,
    DELETE_VS_MODIFY,
}

/** User selection per conflicting path. */
enum class ResolutionChoice {
    KEEP_OURS,
    TAKE_THEIRS,

    /** Leave the conflict unresolved; state stays at `PAUSED_CONFLICT`. */
    SKIP,
}

/** Aggregated request from [com.example.simplygit.ui.conflict.ConflictResolveScreen]. */
data class ResolveRequest(
    val repoId: Long,
    val choices: Map<String, ResolutionChoice>,
)

/**
 * Result returned by `ResolveConflictUseCase` (SPEC §4.3.1 truth table).
 *
 * All legal state transitions are captured by the four variants below.
 */
sealed interface ResolveResult {
    /**
     * Non-SKIP files were successfully committed. Whether `push` landed is
     * encoded in [pushOk]; [remainingSkipped] keeps track of SKIP-ed files
     * that still need user attention (syncState stays `PAUSED_CONFLICT` when
     * `remainingSkipped > 0` **or** `pushOk = false`).
     */
    data class Success(
        val committedFiles: Int,
        val pushOk: Boolean,
        val remainingSkipped: Int,
    ) : ResolveResult

    /** Some paths failed to stage — others may still be staged. */
    data class PartialFailure(
        val failedPaths: List<String>,
        val reason: SyncErrorKind,
    ) : ResolveResult

    /** No file was committed (precondition failed / request invalid). */
    data class Failure(val reason: SyncErrorKind) : ResolveResult

    /**
     * Bug fix (bug_report_20260503_p16x / BUG-003): the resolve commit
     * landed locally but the subsequent push hit
     * [com.example.simplygit.data.ssh.SshHostKeyFirstConnectException]
     * (SPEC §6.2 single white-listed bypass). The UI must surface a TOFU
     * confirmation dialog so the user can accept / cancel the fingerprint,
     * then retry the push. Previous code swallowed this into `pushOk = false`
     * and the user could not recover from the conflict screen.
     *
     * @property committedFiles how many files were already committed before
     *   the push attempt (same semantics as [Success.committedFiles]).
     * @property remainingSkipped unresolved SKIP-ed conflicts that will keep
     *   `syncState == PAUSED_CONFLICT` until the user revisits them.
     */
    data class NeedsHostKeyConfirmation(
        val host: String,
        val fingerprint: String,
        val committedFiles: Int,
        val remainingSkipped: Int,
    ) : ResolveResult
}
