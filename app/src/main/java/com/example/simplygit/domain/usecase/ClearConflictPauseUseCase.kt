package com.example.simplygit.domain.usecase

import com.example.simplygit.domain.model.ConflictClass
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.repository.FileTreeRepository
import com.example.simplygit.domain.repository.SyncLogRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Closes a `PAUSED_CONFLICT` run by writing a [SyncResult.CONFLICT_RESOLVED]
 * audit row and flipping [SyncState] back to [SyncState.IDLE] (SPEC §4.3.1
 * Iteration 3 / P0-1 closure).
 *
 * Semantic split from [ResumeFromPauseUseCase]:
 *  - `ResumeFromPauseUseCase` handles the generic "recovery" path for every
 *    paused state (PAUSED_AUTH / PAUSED_FS / BROKEN) — no commit-semantics.
 *  - `ClearConflictPauseUseCase` is invoked from inside
 *    `ResolveConflictUseCase` after a successful resolve + push; it records
 *    an auditable CONFLICT_RESOLVED row.
 *
 * Both MAY operate on `PAUSED_CONFLICT`: the "Resume" button is a tolerant
 * fallback — its call site does NOT log CONFLICT_RESOLVED. This mirrors the
 * UI's dual entry points (Resolve / Resume) both being visible.
 *
 * After the state flip we also trigger a file-tree rescan so the browser
 * view drops the stale `CONFLICT` dots immediately — SPEC §4.1.1 "触发时机"
 * #4. Failures are swallowed in a `runCatching` (and the caller treats a
 * "rescan after resolve" failure as diagnostic noise) so a rescan hiccup
 * can never poison the already-successful resolve flow.
 */
class ClearConflictPauseUseCase @Inject constructor(
    private val syncLogRepository: SyncLogRepository,
    private val fileTreeRepository: FileTreeRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        repoId: Long,
        logId: Long,
        conflictClass: ConflictClass? = null,
    ) {
        syncLogRepository.pauseAndFinish(
            repoId = repoId,
            logId = logId,
            state = SyncState.IDLE,
            result = SyncResult.CONFLICT_RESOLVED,
            endedAt = Instant.now(clock),
            conflictClass = conflictClass,
        )
        // SPEC §4.1.1 trigger #4: refresh file-tree cache after successful
        // resolution. Wrapped in runCatching so a rescan failure (e.g. SAF
        // permission race) never destabilises the resolve flow.
        runCatching { fileTreeRepository.rescan(repoId) }
    }
}
