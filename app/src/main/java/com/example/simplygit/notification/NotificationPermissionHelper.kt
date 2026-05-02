package com.example.simplygit.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helper for the A13+ [Manifest.permission.POST_NOTIFICATIONS] runtime
 * permission (SPEC §4.5 / R-10 Iteration 2).
 *
 * SPEC R-10 timing: the permission is requested on first "Save" in
 * `SyncPolicyScreen` — not earlier (an install-time pop-up would be
 * instinctively declined) and not later (the worker needs a chance to
 * deliver alerts before the first run).
 */
object NotificationPermissionHelper {

    /** Returns true on A12- (no runtime permission required). */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** @return the permission string on A13+, or `null` when not applicable. */
    fun permissionIfNeeded(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
}
