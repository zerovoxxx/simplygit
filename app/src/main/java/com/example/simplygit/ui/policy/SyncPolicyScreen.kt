package com.example.simplygit.ui.policy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.simplygit.R
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.notification.NotificationPermissionHelper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sync policy configuration screen (SPEC §4.7 Iteration 2).
 *
 * SPEC R-10: the runtime POST_NOTIFICATIONS permission is requested on the
 * first "Save" tap (once per session) so the user has seen the app context
 * before the system dialog appears.
 *
 * Hotfix 2026-05 (Iteration 2 follow-up):
 *  - Refresh `notificationGranted` on every `ON_RESUME` so that granting
 *    the permission through the system settings page (outside the app) is
 *    reflected the moment the user comes back.
 *  - If the user has permanently denied the permission
 *    (`shouldShowRequestPermissionRationale == false` on A13+), another
 *    `launcher.launch()` call becomes a silent no-op. In that state the
 *    warning strip upgrades its CTA to "去系统设置" so the user never gets
 *    stuck.
 *  - The "Save" action no longer races with the permission callback:
 *    the permission is requested **before** `Save` is dispatched, and if
 *    we decide to redirect to system settings the permission intent and
 *    the save intent both fire (save must never be blocked — SPEC §4.7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun SyncPolicyScreen(
    onBack: () -> Unit,
    onOpenSshKeys: () -> Unit = {},
    viewModel: SyncPolicyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Hotfix Bug #1: refresh permission status on every ON_RESUME so that
    // returning from the system app-notification settings page reflects
    // the latest grant state without requiring a policy-flow emission.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onIntent(SyncPolicyIntent.RefreshNotificationPermission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.onIntent(SyncPolicyIntent.RefreshNotificationPermission)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.policy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.notificationGranted && state.intervalMinutes != SyncPolicyModel.MANUAL_ONLY) {
                // Hotfix Bug #2: decide the CTA based on whether the system
                // will still show a runtime dialog, or whether it has been
                // permanently denied (in which case we have to deep-link to
                // the system settings page).
                val permanentlyDenied = activity?.let {
                    NotificationPermissionHelper.isPermanentlyDenied(it)
                } ?: false
                NotificationWarningCard(
                    message = stringResource(R.string.policy_notification_warn),
                    ctaLabel = stringResource(
                        if (permanentlyDenied) R.string.policy_notification_warn_cta_settings
                        else R.string.policy_notification_warn_cta_grant,
                    ),
                    onCtaClick = {
                        if (permanentlyDenied) {
                            context.openAppNotificationSettings()
                        } else {
                            NotificationPermissionHelper.permissionIfNeeded()
                                ?.let { permission ->
                                    NotificationPermissionHelper.markRequested(context)
                                    permissionLauncher.launch(permission)
                                }
                        }
                    },
                )
            }

            Text(stringResource(R.string.policy_section_interval), style = MaterialTheme.typography.titleMedium)
            IntervalRadio(
                label = stringResource(R.string.policy_interval_15),
                selected = state.intervalMinutes == 15,
                onSelect = { viewModel.onIntent(SyncPolicyIntent.ChangeInterval(15)) },
            )
            IntervalRadio(
                label = stringResource(R.string.policy_interval_30),
                selected = state.intervalMinutes == 30,
                onSelect = { viewModel.onIntent(SyncPolicyIntent.ChangeInterval(30)) },
            )
            IntervalRadio(
                label = stringResource(R.string.policy_interval_60),
                selected = state.intervalMinutes == 60,
                onSelect = { viewModel.onIntent(SyncPolicyIntent.ChangeInterval(60)) },
            )
            IntervalRadio(
                label = stringResource(R.string.policy_interval_manual),
                selected = state.intervalMinutes == SyncPolicyModel.MANUAL_ONLY,
                onSelect = { viewModel.onIntent(SyncPolicyIntent.ChangeInterval(SyncPolicyModel.MANUAL_ONLY)) },
            )

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.policy_section_constraints), style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                label = stringResource(R.string.policy_require_unmetered),
                checked = state.requireUnmetered,
                onCheckedChange = { viewModel.onIntent(SyncPolicyIntent.ChangeRequireUnmetered(it)) },
            )
            SwitchRow(
                label = stringResource(R.string.policy_require_charging),
                checked = state.requireCharging,
                onCheckedChange = { viewModel.onIntent(SyncPolicyIntent.ChangeRequireCharging(it)) },
            )

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.policy_section_commit_template), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.commitMessageTemplate,
                onValueChange = { viewModel.onIntent(SyncPolicyIntent.ChangeTemplate(it)) },
                label = { Text(stringResource(R.string.policy_commit_template_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val preview = remember(state.commitMessageTemplate) {
                val iso = DateTimeFormatter.ISO_INSTANT
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now())
                state.commitMessageTemplate.replace("%ISO%", iso)
            }
            Text(stringResource(R.string.policy_commit_template_preview, preview))

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // SPEC R-10: request POST_NOTIFICATIONS when the user first
                    // saves a non-manual policy. Hotfix Bug #2: if the user
                    // has permanently denied it, `launch()` is a silent no-op
                    // — redirect to the system settings page instead. Save is
                    // never blocked by the permission flow (SPEC §4.7: "不拦截保存").
                    if (state.intervalMinutes != SyncPolicyModel.MANUAL_ONLY &&
                        !NotificationPermissionHelper.isGranted(context)
                    ) {
                        val perm = NotificationPermissionHelper.permissionIfNeeded()
                        val permanentlyDenied = activity?.let {
                            NotificationPermissionHelper.isPermanentlyDenied(it)
                        } ?: false
                        when {
                            perm != null && !permanentlyDenied -> {
                                NotificationPermissionHelper.markRequested(context)
                                permissionLauncher.launch(perm)
                            }
                            permanentlyDenied -> context.openAppNotificationSettings()
                            // else: pre-A13 — permission is implicit.
                        }
                    }
                    viewModel.onIntent(SyncPolicyIntent.Save)
                },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save_policy))
            }

            // "SSH 密钥" 入口由首页 overflow 菜单迁移至此。设置页是"低频但
            // 长期管理"的合集，SSH 密钥生成 / 导入 / 删除属于典型的一次性
            // 配置动作，放在 Policy 之后不污染首页主操作区。
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.policy_section_security),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = onOpenSshKeys,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_open_ssh_keys))
            }
        }
    }
}

@Composable
private fun NotificationWarningCard(
    message: String,
    ctaLabel: String,
    onCtaClick: () -> Unit,
) {
    // Click is intentionally attached to the inner `TextButton` only.
    // A prior revision also made the outer `Surface` clickable, which on
    // some devices caused the pointer-input chain to consume the event
    // before `TextButton` could observe it, so tapping "去系统设置" was a
    // no-op. Keeping exactly one hit target avoids that race.
    //
    // Layout note: use a single `Row` (not Column + Spacer + aligned
    // button) so the card stays visually compact. The CTA is vertically
    // centered against the message body and shares the same row height.
    Surface(
        color = Color(0xFFFFF4E5),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = Color(0xFF8A5A00),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // Compact CTA: shrink the built-in 48dp min-height button
            // padding while preserving the semantic `TextButton` ripple.
            TextButton(
                onClick = onCtaClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    ctaLabel,
                    color = Color(0xFF8A5A00),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun IntervalRadio(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.height(0.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Unwrap a Compose [android.content.Context] into the hosting [Activity].
 * Needed for `ActivityCompat.shouldShowRequestPermissionRationale`, which
 * requires an `Activity` reference (application context won't do).
 */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Open the system per-app notification settings page.
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` takes an *extra* (`EXTRA_APP_PACKAGE`)
 * and must **not** carry a `data` URI — mixing the two makes the system
 * fail to resolve any matching Activity, which surfaced as "点了没反应"
 * in the previous revision. We therefore try the clean `action + extra`
 * form first, and only fall back to the per-app **details** page
 * (`ACTION_APPLICATION_DETAILS_SETTINGS`, which does use a `package:` URI)
 * when the notification-settings screen is genuinely unavailable — a
 * condition observed on a small number of heavily-customised OEM ROMs.
 */
private fun android.content.Context.openAppNotificationSettings() {
    val notifIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (notifIntent.resolveActivity(packageManager) != null) {
        runCatching { startActivity(notifIntent) }
            .onFailure { android.util.Log.w(TAG_POLICY, "open notif settings failed", it) }
        return
    }
    // Fallback: app details page always exists.
    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(detailsIntent) }
        .onFailure { android.util.Log.w(TAG_POLICY, "open app details failed", it) }
}

private const val TAG_POLICY = "SyncPolicyScreen"
