package com.example.simplygit.data.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Row shape for the `sync_policy` table (SPEC §4.6 / §6.1 Iteration 2).
 *
 * Iteration 2 only stores a single row (N4: multi-repo deferred to Phase 3+);
 * the table is pre-structured to carry one row per repo in the future.
 */
@Entity(tableName = "sync_policy")
data class SyncPolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intervalMinutes: Int,
    val requireUnmetered: Boolean,
    val requireCharging: Boolean,
    val commitMessageTemplate: String,
)
