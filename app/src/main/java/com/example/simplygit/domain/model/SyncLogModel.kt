package com.example.simplygit.domain.model

import java.time.Instant

/** Domain projection of a `sync_log` row (SPEC §6.1 Iteration 2). */
data class SyncLogModel(
    val id: Long,
    val repoId: Long,
    val startedAt: Instant,
    val endedAt: Instant?,
    val trigger: SyncTrigger,
    val result: SyncResult?,
    val commitsPulled: Int,
    val commitsPushed: Int,
    val filesChanged: Int,
    val conflictClass: ConflictClass?,
    val errorMsg: String?,
    /**
     * Original Throwable class name preserved by
     * `JGitExceptionSanitizer.sanitize` (SPEC §4.7 Iteration 2 / fix CR P3-02).
     * Null for non-Git / legacy rows.
     */
    val errorType: String? = null,
)
