package com.example.simplygit.domain.usecase

import android.net.Uri
import com.example.simplygit.data.diagnostics.DiagnosticsLogger
import com.example.simplygit.data.git.JGitExceptionSanitizer
import com.example.simplygit.data.git.SanitizedGitException
import com.example.simplygit.data.git.SyncErrorKind
import com.example.simplygit.data.saf.SafPathResolver
import com.example.simplygit.domain.model.ConflictClass
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.PAUSED_STATES
import com.example.simplygit.domain.model.RunSyncOutcome
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.model.SyncTrigger
import com.example.simplygit.domain.model.UNRESOLVABLE_CONFLICTS
import com.example.simplygit.domain.repository.CredentialRepository
import com.example.simplygit.domain.repository.GitRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SyncLogRepository
import com.example.simplygit.domain.repository.SyncPolicyRepository
import com.example.simplygit.domain.service.DebounceGuard
import com.example.simplygit.domain.service.NotificationPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Arrays
import javax.inject.Inject

/**
 * Core silent-sync link for Iteration 2 (SPEC §4.3 / §4.5).
 *
 * Sequence (fault-tolerant, single-repo N4):
 *  1. Short-circuit when `syncState ∈ PAUSED_STATES`.
 *  2. Probe persisted SAF permission; on loss transition to `PAUSED_FS`.
 *  3. Debounce: skip when the Vault has changes newer than [QUIET_WINDOW].
 *  4. Load credential identity + PAT; missing → `PAUSED_AUTH`.
 *  5. `pullAndClassify` + classify into [ConflictClass]; unresolvable ones
 *     → `PAUSED_CONFLICT` + notification.
 *  6. `commitAllIfDirty` with the configured message template.
 *  7. `push`.
 *  8. Persist audit row + prune expired logs + return `IDLE`.
 *
 * Failure dispatch is driven exclusively by [SanitizedGitException.kind]
 * (SPEC I-1): `Auth` → `PAUSED_AUTH`; `Network` / `Unknown` with < 3
 * consecutive failures → stay `IDLE` so WorkManager's exponential backoff
 * retries; ≥ 3 → `BROKEN` + one-shot notification.
 *
 * PAT lifecycle: the `CharArray` copy obtained from
 * [CredentialRepository.loadPatOnce] is zeroed in a `finally` block even on
 * exceptional paths (SPEC R3).
 */
@Suppress("LongParameterList")
class RunSyncUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val credRepo: CredentialRepository,
    private val syncPolicyRepo: SyncPolicyRepository,
    private val syncLogRepo: SyncLogRepository,
    private val safPathResolver: SafPathResolver,
    private val gitRepo: GitRepository,
    private val debounce: DebounceGuard,
    private val notifier: NotificationPublisher,
    private val jgitExceptionSanitizer: JGitExceptionSanitizer,
    private val diagnostics: DiagnosticsLogger,
    private val clock: Clock,
) {

    @Suppress("LongMethod", "ComplexMethod")
    suspend operator fun invoke(trigger: SyncTrigger): RunSyncOutcome {
        val binding = bindingRepo.currentOrNull() ?: return RunSyncOutcome.NoBinding
        val repoId = binding.id
        val now = Instant.now(clock)
        syncLogRepo.recoverStaleRunning(
            repoId = repoId,
            staleBefore = now.minus(RUNNING_STALE_TIMEOUT),
            endedAt = now,
        )
        val snapshot = syncLogRepo.loadRepoState(repoId)

        // (1) Pause / in-flight gate.
        if (snapshot.syncState in PAUSED_STATES) {
            return RunSyncOutcome.SkippedPaused(snapshot.syncState)
        }
        if (snapshot.syncState == SyncState.RUNNING) {
            return RunSyncOutcome.SkippedRunning
        }

        val logId = syncLogRepo.tryStartRun(repoId, trigger, now) ?: run {
            val latest = syncLogRepo.loadRepoState(repoId).syncState
            return if (latest in PAUSED_STATES) {
                RunSyncOutcome.SkippedPaused(latest)
            } else {
                RunSyncOutcome.SkippedRunning
            }
        }

        var pat: CharArray? = null
        return try {
            // (2) SAF permission self-check.
            val treeUri = runCatching { Uri.parse(binding.treeUri) }.getOrNull()
            if (treeUri == null || !safPathResolver.hasPersistedPermission(treeUri)) {
                syncLogRepo.pauseAndFinish(
                    repoId = repoId,
                    logId = logId,
                    state = SyncState.PAUSED_FS,
                    result = SyncResult.FS_ERR,
                    endedAt = Instant.now(clock),
                )
                notifier.publishFsPermissionLost(repoId)
                return RunSyncOutcome.PausedFs
            }

            // (3) Quiet-window debounce.
            if (debounce.withinQuietWindow(binding.localAbsPath, QUIET_WINDOW)) {
                syncLogRepo.finishLog(
                    logId = logId,
                    result = SyncResult.SKIPPED_DEBOUNCE,
                    endedAt = Instant.now(clock),
                )
                syncLogRepo.updateSyncState(repoId, SyncState.IDLE)
                return RunSyncOutcome.SkippedDebounce
            }

            // (4) Identity + auth buffer. PAT mode requires a PAT; SSH mode
            // passes an empty placeholder because JGitDataSource dispatches on authType.
            val identity = credRepo.snapshotIdentity() ?: run {
                syncLogRepo.pauseAndFinish(
                    repoId = repoId,
                    logId = logId,
                    state = SyncState.PAUSED_AUTH,
                    result = SyncResult.AUTH_ERR,
                    endedAt = Instant.now(clock),
                )
                notifier.publishAuthFailed(repoId)
                return RunSyncOutcome.MissingCredential
            }
            pat = loadAuthBuffer(binding) ?: run {
                syncLogRepo.pauseAndFinish(
                    repoId = repoId,
                    logId = logId,
                    state = SyncState.PAUSED_AUTH,
                    result = SyncResult.AUTH_ERR,
                    endedAt = Instant.now(clock),
                )
                notifier.publishAuthFailed(repoId)
                return RunSyncOutcome.MissingCredential
            }

            // (5) Pull + classify.
            val pullResult = gitRepo.pullAndClassify(binding, identity.username, pat)
            if (pullResult.classification in UNRESOLVABLE_CONFLICTS) {
                syncLogRepo.pauseAndFinish(
                    repoId = repoId,
                    logId = logId,
                    state = SyncState.PAUSED_CONFLICT,
                    result = SyncResult.CONFLICT,
                    endedAt = Instant.now(clock),
                    conflictClass = pullResult.classification,
                )
                notifier.publishConflict(repoId, pullResult.classification)
                return RunSyncOutcome.PausedConflict(pullResult.classification)
            }

            // (6) Commit iff dirty.
            val policy = syncPolicyRepo.current()
            val commit = gitRepo.commitAllIfDirty(
                binding = binding,
                message = buildCommitMessage(policy),
                authorName = identity.username,
                authorEmail = identity.email,
            )

            // (7) Push. This method returns GitOpResult instead of throwing;
            // never ignore it or the audit row can incorrectly report OK.
            //
            // Fix bug_report_20260503: `GitRepositoryImpl.push` now returns
            // `SuccessWithPayload(PushOutcome)` on success — treat it as a
            // first-class success branch (previously it was folded into the
            // "unexpected payload" error path).
            val pushOutcome: com.example.simplygit.domain.model.PushOutcome? =
                when (val pushResult = gitRepo.push(binding, identity.username, pat)) {
                    GitOpResult.Success -> null
                    is GitOpResult.SuccessWithPayload ->
                        pushResult.payload as? com.example.simplygit.domain.model.PushOutcome
                    is GitOpResult.Failure -> {
                        val sanitized = pushResult.cause as? SanitizedGitException
                            ?: jgitExceptionSanitizer.sanitize(pushResult.cause)
                        diagnostics.logGitOpFailure("SYNC_PUSH", sanitized)
                        return handleSanitized(repoId, logId, sanitized)
                    }
                }

            // (8) Persist + prune.
            val endedAt = Instant.now(clock)
            // Fix bug_report_20260503: surface the real `commitsPushed` from
            // JGit's RemoteRefUpdate walk instead of the old
            // "1 if local commit created else 0" approximation. A local commit
            // may push to an up-to-date remote (0) and a no-op push may still
            // succeed (also 0) — now faithfully reported.
            syncLogRepo.finishLog(
                logId = logId,
                result = SyncResult.OK,
                endedAt = endedAt,
                commitsPulled = pullResult.commitsPulled,
                commitsPushed = pushOutcome?.commitsPushed ?: (if (commit != null) 1 else 0),
                filesChanged = commit?.filesChanged ?: 0,
            )
            syncLogRepo.updateSyncState(repoId, SyncState.IDLE)
            syncLogRepo.pruneExpired(endedAt)
            RunSyncOutcome.Ok
        } catch (e: SanitizedGitException) {
            diagnostics.logGitOpFailure("SYNC", e)
            handleSanitized(repoId, logId, e)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    syncLogRepo.abortRun(
                        repoId = repoId,
                        logId = logId,
                        endedAt = Instant.now(clock),
                        errorMsg = "worker cancelled",
                        errorType = e.javaClass.simpleName,
                    )
                }
            }
            throw e
        } catch (e: Throwable) {
            // Defensive: anything not already sanitized (e.g. OOM / local IO)
            // still goes through the sanitizer so errorMsg never leaks raw text.
            val sanitized = jgitExceptionSanitizer.sanitize(e)
            diagnostics.logGitOpFailure("SYNC", sanitized)
            handleSanitized(repoId, logId, sanitized)
        } finally {
            pat?.let { Arrays.fill(it, '\u0000') }
        }
    }

    private suspend fun handleSanitized(
        repoId: Long,
        logId: Long,
        e: SanitizedGitException,
    ): RunSyncOutcome = when (e.kind) {
        SyncErrorKind.Auth -> {
            syncLogRepo.pauseAndFinish(
                repoId = repoId,
                logId = logId,
                state = SyncState.PAUSED_AUTH,
                result = SyncResult.AUTH_ERR,
                endedAt = Instant.now(clock),
                errorMsg = e.message,
                errorType = e.originalType,
            )
            notifier.publishAuthFailed(repoId)
            RunSyncOutcome.PausedAuth
        }
        SyncErrorKind.Network -> finishTransient(
            repoId = repoId,
            logId = logId,
            result = SyncResult.NETWORK_ERR,
            errorMsg = e.message,
            errorType = e.originalType,
            outcome = RunSyncOutcome.NetworkErr,
        )
        // InvalidState should never escape Data-layer sanitization during a background sync
        // (it signals a domain-level precondition violation, not a JGit exception). Fold it
        // into Unknown so `sync_log.result = ABORTED` still lands — identical telemetry to
        // the existing `SyncErrorKind.Unknown` branch.
        SyncErrorKind.InvalidState,
        SyncErrorKind.Unknown,
        -> finishTransient(
            repoId = repoId,
            logId = logId,
            result = SyncResult.ABORTED,
            errorMsg = e.message,
            errorType = e.originalType,
            outcome = RunSyncOutcome.UnknownErr,
        )
    }

    private suspend fun finishTransient(
        repoId: Long,
        logId: Long,
        result: SyncResult,
        errorMsg: String?,
        errorType: String?,
        outcome: RunSyncOutcome,
    ): RunSyncOutcome {
        syncLogRepo.finishLog(
            logId = logId,
            result = result,
            endedAt = Instant.now(clock),
            errorMsg = errorMsg,
            errorType = errorType,
        )
        val streak = syncLogRepo.recentConsecutiveFailures(repoId)
        if (streak >= BROKEN_STREAK_THRESHOLD) {
            syncLogRepo.updateSyncState(repoId, SyncState.BROKEN)
            notifier.publishNetworkBroken(repoId)
        } else {
            syncLogRepo.updateSyncState(repoId, SyncState.IDLE)
        }
        return outcome
    }

    private suspend fun loadAuthBuffer(binding: com.example.simplygit.domain.model.RepoBinding): CharArray? =
        when (binding.authType) {
            "PAT" -> credRepo.loadPatOnce()
            "SSH" -> CharArray(0)
            else -> error("unknown authType=${binding.authType}")
        }

    private fun buildCommitMessage(policy: SyncPolicyModel): String {
        val iso = ISO_FMT.format(Instant.now(clock))
        return policy.commitMessageTemplate.replace("%ISO%", iso)
    }

    companion object {
        val QUIET_WINDOW: Duration = Duration.ofMinutes(2)
        val RUNNING_STALE_TIMEOUT: Duration = Duration.ofMinutes(30)
        const val BROKEN_STREAK_THRESHOLD: Int = 3
        val ISO_FMT: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}
