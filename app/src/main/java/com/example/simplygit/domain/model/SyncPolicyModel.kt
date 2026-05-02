package com.example.simplygit.domain.model

/**
 * Domain projection of the `sync_policy` row (SPEC §6.1 Iteration 2).
 *
 * - [intervalMinutes]: one of [VALID_INTERVALS] or [MANUAL_ONLY]; any other value
 *   must be normalised before persistence.
 * - [requireUnmetered]: maps to `NetworkType.UNMETERED` vs `CONNECTED` on the
 *   WorkManager `Constraints`.
 * - [requireCharging]: maps to `Constraints.setRequiresCharging(true)`.
 * - [commitMessageTemplate]: free-form text, `%ISO%` placeholder is substituted
 *   with the ISO-8601 timestamp at commit time.
 */
data class SyncPolicyModel(
    val intervalMinutes: Int,
    val requireUnmetered: Boolean,
    val requireCharging: Boolean,
    val commitMessageTemplate: String,
) {
    companion object {
        const val MANUAL_ONLY: Int = -1

        /** Periodic intervals exposed by the UI (SPEC §4.7). */
        val VALID_INTERVALS: Set<Int> = setOf(15, 30, 60, MANUAL_ONLY)

        val DEFAULT: SyncPolicyModel = SyncPolicyModel(
            intervalMinutes = 15,
            requireUnmetered = false,
            requireCharging = false,
            commitMessageTemplate = "chore(sync): auto-commit at %ISO%",
        )
    }
}
