package com.example.simplygit.domain.usecase

import com.example.simplygit.data.diagnostics.DiagnosticsLogger
import com.example.simplygit.data.git.JGitExceptionSanitizer
import com.example.simplygit.data.git.SanitizedGitException
import com.example.simplygit.data.git.SyncErrorKind
import com.example.simplygit.domain.model.ConflictClass
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.PAUSED_STATES
import com.example.simplygit.domain.model.ResolutionChoice
import com.example.simplygit.domain.model.ResolveRequest
import com.example.simplygit.domain.model.ResolveResult
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.model.SyncTrigger
import com.example.simplygit.domain.repository.ConflictRepository
import com.example.simplygit.domain.repository.CredentialRepository
import com.example.simplygit.domain.repository.GitRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SyncLogRepository
import java.time.Clock
import java.time.Instant
import java.util.Arrays
import javax.inject.Inject

/**
 * Integer-file conflict resolver (SPEC §4.3.1 Iteration 3).
 *
 * Pipeline (all idempotent / re-enterable on mid-flight crash):
 *  1. **Pre-check** — require `syncState == PAUSED_CONFLICT`.
 *  2. **startLog** — open a new audit row with trigger `MANUAL`.
 *  3. **Stage per file** — for each KEEP_OURS / TAKE_THEIRS path, call
 *     `conflictRepository.checkoutStage(...)`; any failure aborts with
 *     `PartialFailure` and leaves the state at PAUSED_CONFLICT.
 *  4. **Commit** if any non-SKIP file was staged, using a hard-coded
 *     message (SPEC §4.3.1 P1-6 — we deliberately do NOT reuse
 *     `SyncPolicyModel.commitMessageTemplate`).
 *  5. **Push**.
 *  6. **State wrap-up** — follow the SPEC §4.3.1 truth table:
 *     | committed | push | skipped | syncState       | result written      |
 *     |-----------|------|---------|-----------------|---------------------|
 *     | > 0       | ok   | 0       | IDLE            | CONFLICT_RESOLVED   |
 *     | > 0       | ok   | > 0     | PAUSED_CONFLICT | CONFLICT_RESOLVED   |
 *     | > 0       | fail | any     | PAUSED_CONFLICT | (sanitised kind)    |
 *     | 0         | —    | > 0     | unchanged       | (no audit row)      |
 *  7. **Diagnostic log** — one INFO line with the breakdown.
 *
 * Commit message template (SPEC §4.3.1): hard-coded English so it's stable
 * across locales and grepable in `git log`.
 */
@Suppress("LongParameterList", "LongMethod")
class ResolveConflictUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val conflictRepository: ConflictRepository,
    private val gitRepository: GitRepository,
    private val credentialRepository: CredentialRepository,
    private val syncLogRepository: SyncLogRepository,
    private val clearConflictPauseUseCase: ClearConflictPauseUseCase,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val jgitExceptionSanitizer: JGitExceptionSanitizer,
    private val clock: Clock,
) {
    suspend operator fun invoke(req: ResolveRequest): ResolveResult {
        val binding = bindingRepo.currentOrNull()
            ?: return ResolveResult.Failure(SyncErrorKind.InvalidState)
        if (binding.id != req.repoId) {
            return ResolveResult.Failure(SyncErrorKind.InvalidState)
        }

        // (1) Pre-check.
        val snap = syncLogRepository.loadRepoState(req.repoId)
        if (snap.syncState != SyncState.PAUSED_CONFLICT) {
            return ResolveResult.Failure(SyncErrorKind.InvalidState)
        }

        val nonSkip = req.choices.filterValues { it != ResolutionChoice.SKIP }
        val skipCount = req.choices.count { it.value == ResolutionChoice.SKIP }
        if (nonSkip.isEmpty()) {
            // All-skip: user cancelled intent — leave state unchanged.
            return ResolveResult.Failure(SyncErrorKind.InvalidState)
        }

        // (2) startLog — mark the operation as MANUAL.
        val now = Instant.now(clock)
        val logId = syncLogRepository.startLog(req.repoId, SyncTrigger.MANUAL, now)

        // (3) Stage per file.
        val failed = mutableListOf<String>()
        for ((path, choice) in nonSkip) {
            runCatching {
                conflictRepository.checkoutStage(req.repoId, path, choice)
            }.onFailure { failed.add(path) }
        }
        if (failed.isNotEmpty()) {
            syncLogRepository.finishLog(
                logId = logId,
                result = SyncResult.ABORTED,
                endedAt = Instant.now(clock),
                errorMsg = "partial-failure: ${failed.size} path(s)",
            )
            return ResolveResult.PartialFailure(failed, SyncErrorKind.Unknown)
        }

        // (4) Commit.
        val identity = credentialRepository.snapshotIdentity()
            ?: run {
                syncLogRepository.finishLog(
                    logId = logId,
                    result = SyncResult.AUTH_ERR,
                    endedAt = Instant.now(clock),
                )
                return ResolveResult.Failure(SyncErrorKind.Auth)
            }
        val oursCount = nonSkip.count { it.value == ResolutionChoice.KEEP_OURS }
        val theirsCount = nonSkip.count { it.value == ResolutionChoice.TAKE_THEIRS }
        val committedFiles = runCatching {
            conflictRepository.commitResolved(
                repoId = req.repoId,
                message = buildCommitMessage(oursCount, theirsCount, skipCount),
                authorName = identity.username,
                authorEmail = identity.email,
            )
        }.getOrElse { t ->
            // BUG-002 fix (bug_report_20260503_snao): `commitResolved` already
            // sanitizes internally, but defensively re-wrap anything else so
            // `errorMsg` / `errorType` never expose raw `javaClass.simpleName`
            // and the UI can map `kind` back onto a localised message (R8).
            val sanitized = t as? SanitizedGitException
                ?: jgitExceptionSanitizer.sanitize(t)
            syncLogRepository.finishLog(
                logId = logId,
                result = SyncResult.ABORTED,
                endedAt = Instant.now(clock),
                errorMsg = sanitized.message,
                errorType = sanitized.originalType,
            )
            return ResolveResult.PartialFailure(nonSkip.keys.toList(), sanitized.kind)
        }

        // (5) Push.
        var pat: CharArray? = null
        val pushOk: Boolean = try {
            pat = credentialRepository.loadPatOnce() ?: CharArray(0)
            val result = gitRepository.push(binding, identity.username, pat)
            result is GitOpResult.Success
        } catch (e: Throwable) {
            diagnosticsLogger.logInfo(
                tag = "conflict_push_failed",
                message = "type=${e.javaClass.simpleName}",
            )
            false
        } finally {
            pat?.let { Arrays.fill(it, '\u0000') }
        }

        // (6) State wrap-up — truth table.
        val endedAt = Instant.now(clock)
        return if (pushOk && skipCount == 0) {
            clearConflictPauseUseCase(req.repoId, logId, conflictClass = null)
            diagnosticsLogger.logInfo(
                "conflict_resolved",
                "ours=$oursCount theirs=$theirsCount skipped=0 pushOk=true",
            )
            ResolveResult.Success(
                committedFiles = committedFiles,
                pushOk = true,
                remainingSkipped = 0,
            )
        } else if (pushOk && skipCount > 0) {
            // Local commit + push done, but user left some conflicts unresolved.
            syncLogRepository.finishLog(
                logId = logId,
                result = SyncResult.CONFLICT_RESOLVED,
                endedAt = endedAt,
                errorMsg = "remainingSkipped=$skipCount",
            )
            // State stays PAUSED_CONFLICT — we don't call ClearConflictPauseUseCase.
            diagnosticsLogger.logInfo(
                "conflict_partial_resolved",
                "ours=$oursCount theirs=$theirsCount skipped=$skipCount pushOk=true",
            )
            ResolveResult.Success(
                committedFiles = committedFiles,
                pushOk = true,
                remainingSkipped = skipCount,
            )
        } else {
            // pushOk = false — local commit landed, push will be retried.
            syncLogRepository.finishLog(
                logId = logId,
                result = SyncResult.NETWORK_ERR,
                endedAt = endedAt,
                errorMsg = "push-failed-after-resolve",
            )
            diagnosticsLogger.logInfo(
                "conflict_resolved_push_failed",
                "ours=$oursCount theirs=$theirsCount skipped=$skipCount pushOk=false",
            )
            ResolveResult.Success(
                committedFiles = committedFiles,
                pushOk = false,
                remainingSkipped = skipCount,
            )
        }
    }

    /**
     * SPEC §4.3.1 P1-6: hard-coded English template, NOT the sync-policy one.
     * Kept in ISO-ish form so it's stable across locales.
     */
    private fun buildCommitMessage(ours: Int, theirs: Int, skipped: Int): String {
        val total = ours + theirs
        val suffix = if (skipped > 0) ", skipped=$skipped" else ""
        return "SimplyGit: resolved $total conflicts (ours=$ours, theirs=$theirs$suffix)"
    }

    @Suppress("unused")
    private val pausedSentinel: Set<SyncState> = PAUSED_STATES
}
