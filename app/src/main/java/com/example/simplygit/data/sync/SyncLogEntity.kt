package com.example.simplygit.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row shape for the `sync_log` table (SPEC §4.6 / §6.1 Iteration 2).
 *
 * - `repoId` FK with `ON DELETE CASCADE`: when a repository row goes away
 *   (e.g. user rebinds to a different Vault) its audit trail is cleaned up.
 * - `(repoId)` and `(startedAt)` are indexed so `observeRecent` and
 *   `recentConsecutiveFailures` stay O(log n).
 * - `errorMsg` MUST be fed through `JGitExceptionSanitizer` before write
 *   (SPEC §6.1 禁用项 — enforced by CI grep in A11d).
 */
@Entity(
    tableName = "sync_log",
    foreignKeys = [
        ForeignKey(
            entity = RepositoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["repoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("repoId"), Index("startedAt")],
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repoId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val trigger: String,
    val result: String?,
    val commitsPulled: Int = 0,
    val commitsPushed: Int = 0,
    val filesChanged: Int = 0,
    val conflictClass: String? = null,
    val errorCode: String? = null,
    val errorMsg: String? = null,
    /**
     * Original Throwable class name preserved through
     * `JGitExceptionSanitizer.sanitize` (SPEC §4.7 Iteration 2 / fix CR P3-02).
     * Surfaced in the Audit detail screen so an engineer can distinguish
     * `TransportException` vs `UnknownHostException` vs `IOException` without
     * needing to open `diagnostics-YYYY-MM-DD.log`. Still null-safe — legacy
     * rows and non-Git failures keep `null`.
     */
    val errorType: String? = null,
)
