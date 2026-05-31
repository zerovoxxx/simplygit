package com.example.simplygit.runtime

/**
 * WorkManager unique-name constants (SPEC §4.4 Iteration 2).
 *
 * Keep these in one file so the scheduler and any diagnostic callers
 * (e.g. A1 `adb shell cmd jobscheduler get-job-state`) reference the same
 * strings.
 */
internal object WorkTags {
    const val UNIQUE_PERIODIC = "simplygit.sync.periodic"
    const val UNIQUE_CATCHUP = "simplygit.sync.catchup"
    const val KEY_TRIGGER = "trigger"
}
