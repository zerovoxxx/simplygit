package com.example.simplygit.domain.usecase

import android.net.Uri
import com.example.simplygit.data.diagnostics.DiagnosticsLogger
import com.example.simplygit.data.git.JGitExceptionSanitizer
import com.example.simplygit.data.git.SanitizedGitException
import com.example.simplygit.data.git.SyncErrorKind
import com.example.simplygit.data.saf.SafPathResolver
import com.example.simplygit.domain.model.ConflictClass
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
        val snapshot = syncLogRepo.loadRepoState(repoId)

        // (1) Pause gate.
        if (snapshot.syncState in PAUSED_STATES) {
            return RunSyncOutcome.SkippedPaused(snapshot.syncState)
        }

        val now = Instant.now(clock)
        val logId = syncLogRepo.startLog(repoId, trigger, now)
        syncLogRepo.updateSyncState(repoId, SyncState.RUNNING)

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

            // (4) Identity + PAT.
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
            pat = credRepo.loadPatOnce() ?: run {
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

            // (7) Push.
            gitRepo.push(binding, identity.username, pat)

            // (8) Persist + prune.
            val endedAt = Instant.now(clock)
            syncLogRepo.finishLog(
                logId = logId,
                result = SyncResult.OK,
                endedAt = endedAt,
                commitsPulled = pullResult.commitsPulled,
                commitsPushed = if (commit != null) 1 else 0,
                filesChanged = commit?.filesChanged ?: 0,
            )
            syncLogRepo.updateSyncState(repoId, SyncState.IDLE)
            syncLogRepo.pruneExpired(endedAt)
            RunSyncOutcome.Ok
        } catch (e: SanitizedGitException) {
            diagnostics.logGitOpFailure("SYNC", e)
            handleSanitized(repoId, logId, e)
        } catch (e: Throwable) {
            // Defensive: anything not already sanitized (e.g. OOM, cancellation)
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

    private fun buildCommitMessage(policy: SyncPolicyModel): String {
        val iso = ISO_FMT.format(Instant.now(clock))
        return policy.commitMessageTemplate.replace("%ISO%", iso)
    }

    companion object {
        val QUIET_WINDOW: Duration = Duration.ofMinutes(2)
        const val BROKEN_STREAK_THRESHOLD: Int = 3
        val ISO_FMT: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}
