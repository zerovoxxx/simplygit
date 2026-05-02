package com.example.simplygit.ui.policy

import com.example.simplygit.domain.model.SyncPolicyModel

/**
 * UI state for `SyncPolicyScreen` (SPEC §4.7 Iteration 2).
 *
 * Mirrors [SyncPolicyModel]; the ViewModel projects the Room row into this
 * shape and applies edits back through `UpdateSyncPolicyUseCase` on Save.
 */
data class SyncPolicyUiState(
    val intervalMinutes: Int = SyncPolicyModel.DEFAULT.intervalMinutes,
    val requireUnmetered: Boolean = SyncPolicyModel.DEFAULT.requireUnmetered,
    val requireCharging: Boolean = SyncPolicyModel.DEFAULT.requireCharging,
    val commitMessageTemplate: String = SyncPolicyModel.DEFAULT.commitMessageTemplate,
    val notificationGranted: Boolean = true,
    val saving: Boolean = false,
    val savedTick: Int = 0,
) {
    fun toModel(): SyncPolicyModel = SyncPolicyModel(
        intervalMinutes = intervalMinutes,
        requireUnmetered = requireUnmetered,
        requireCharging = requireCharging,
        commitMessageTemplate = commitMessageTemplate,
    )
}
