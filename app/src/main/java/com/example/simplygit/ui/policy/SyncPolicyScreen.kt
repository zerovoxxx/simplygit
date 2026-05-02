package com.example.simplygit.ui.policy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun SyncPolicyScreen(
    onBack: () -> Unit,
    viewModel: SyncPolicyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

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
                Surface(color = Color(0xFFFFF4E5)) {
                    Text(
                        text = stringResource(R.string.policy_notification_warn),
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF8A5A00),
                    )
                }
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
                    // saves a non-manual policy.
                    val perm = NotificationPermissionHelper.permissionIfNeeded()
                    if (perm != null &&
                        state.intervalMinutes != SyncPolicyModel.MANUAL_ONLY &&
                        !NotificationPermissionHelper.isGranted(context)
                    ) {
                        permissionLauncher.launch(perm)
                    }
                    viewModel.onIntent(SyncPolicyIntent.Save)
                },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save_policy))
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
