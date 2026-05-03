package com.example.simplygit.notification

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
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

    /**
     * Heuristic: on A13+ the permission is **permanently denied** when
     * `shouldShowRequestPermissionRationale` returns `false` *and* the
     * permission is not granted. In that state another `launch()` is a
     * silent no-op, so the UI must redirect the user to the system
     * app-notification settings page instead.
     *
     * Returns `false` on A12- (no runtime permission) and before the user
     * has ever seen the system dialog (Android returns `false` there too,
     * but on a fresh install the caller should still try `launch()`).
     */
    fun isPermanentlyDenied(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (isGranted(activity)) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
