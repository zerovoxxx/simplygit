package com.example.simplygit.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row shape for the `repository` table (SPEC §4.6 / §6.1 Iteration 2).
 *
 * Iteration 2 fix I-5: `syncPolicyId` references `sync_policy.id` with
 * `ON DELETE RESTRICT` so a policy cannot be silently deleted while a repo
 * still points at it. N4 keeps the row count at 1 for this iteration.
 *
 * - [localAbsPath] is nullable: SAF `ResolveResult` may return `NotPrimary`
 *   / `NotReadable` and leave the absolute path unresolved. `RunSyncUseCase`
 *   short-circuits to `RunSyncOutcome.NoBinding` when this column is null
 *   (§4.6 mapping rule).
 * - [syncState] / [lastSyncResult] are stored as their enum `.name` strings.
 */
@Entity(
    tableName = "repository",
    foreignKeys = [
        ForeignKey(
            entity = SyncPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["syncPolicyId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("syncPolicyId")],
)
data class RepositoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val remoteUrl: String,
    /** "github_pat" in Iteration 2 (placeholder for future auth types). */
    val authRef: String,
    val localTreeUri: String,
    val localAbsPath: String?,
    val defaultBranch: String,
    val syncPolicyId: Long,
    val syncState: String,
    val lastSyncAt: Long?,
    val lastSyncResult: String?,
    val createdAt: Long,
)
