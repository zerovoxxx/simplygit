package com.example.simplygit.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R
import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncState

/**
 * Home screen (SPEC §4.5 Iteration 1, §4.7 Iteration 2).
 *
 * SPEC I-7: the four Git buttons (Clone / Pull / Commit / Push) continue to
 * operate the Iteration 1 manual path — they do NOT touch `syncState` nor
 * produce `SyncLog` rows. Their purpose is diagnosis; the user still has to
 * click "Resume sync" to leave a paused state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun HomeScreen(
    onOpenPolicy: () -> Unit = {},
    onOpenAudit: () -> Unit = {},
    onBrowseRepo: (repoId: Long) -> Unit = {},
    onResolveConflict: (repoId: Long) -> Unit = {},
    onOpenSshKeys: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val safState by viewModel.safState.collectAsState()
    val credView by viewModel.credentialView.collectAsState()
    val sshKeys by viewModel.sshKeys.collectAsState()
    val tofuPrompt by viewModel.tofuPrompt.collectAsState()
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            viewModel.onIntent(HomeIntent.PickVault(uri))
        }
    }

    val bound = uiState as? HomeUiState.Bound

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenPolicy) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                    Box {
                        IconButton(onClick = onOpenAudit) {
                            Text("🕒", style = MaterialTheme.typography.titleLarge)
                        }
                        val pending = bound?.pendingAlertCount ?: 0
                        if (pending > 0) {
                            Badge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 4.dp, top = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.home_badge_pending, pending),
                                )
                            }
                        }
                    }
                    // SPEC §4.4.3 / §5.1 Iteration 3 (P0-2): "SSH 密钥" lives
                    // in an overflow menu so the AppBar primary surface stays
                    // reserved for the most frequent actions.
                    var overflow by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(
                            expanded = overflow,
                            onDismissRequest = { overflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_open_ssh_keys)) },
                                onClick = {
                                    overflow = false
                                    onOpenSshKeys()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncStateBanner(
                bound = bound,
                onResume = { viewModel.onIntent(HomeIntent.ResumeSync) },
                onViewLogs = onOpenAudit,
                onResolveConflict = { bound?.repoId?.let { onResolveConflict(it) } },
            )

            CredentialSection(
                username = credView?.username,
                onSubmit = { u, e, pat -> viewModel.onIntent(HomeIntent.SubmitCredential(u, e, pat)) },
                onClearClipboard = { viewModel.clearClipboardNow() },
            )

            VaultSection(
                currentAbsPath = bound?.localAbsPath,
                safState = safState,
                onPick = { pickerLauncher.launch(null) },
            )

            RemoteSection(
                currentUrl = bound?.remoteUrl,
                onSubmit = { viewModel.onIntent(HomeIntent.SubmitRemote(it)) },
            )

            // SPEC §4.4.3 Iteration 3 (P0-2): auth-mode radio directly under
            // the bind form so users can flip between PAT / SSH without
            // leaving Home.
            AuthModeSection(
                bound = bound,
                sshKeys = sshKeys,
                onSubmitAuth = { authType, keyId ->
                    viewModel.onIntent(HomeIntent.SubmitAuthType(authType, keyId))
                },
                onOpenSshKeys = onOpenSshKeys,
            )

            // SPEC §4.1.2 / §5.1 Iteration 3: "Browse Vault" entry point.
            BrowseRepoSection(
                enabled = bound?.repoId != null && bound.repoId > 0L,
                onBrowse = { bound?.repoId?.let { onBrowseRepo(it) } },
            )

            OperationsSection(
                enabled = uiState !is HomeUiState.Working,
                onClone = { viewModel.onIntent(HomeIntent.DoClone) },
                onPull = { viewModel.onIntent(HomeIntent.DoPull) },
                onCommit = { msg -> viewModel.onIntent(HomeIntent.DoCommit(msg)) },
                onPush = { viewModel.onIntent(HomeIntent.DoPush) },
            )

            StatusSection(
                uiState = uiState,
                onDismissError = { viewModel.onIntent(HomeIntent.DismissError) },
            )
        }
    }

    // SPEC §4.4.2 Iteration 3 (P0-6 TOFU): first-connect confirmation. The
    // dialog sits outside the Scaffold body so it overlays regardless of
    // scroll state.
    tofuPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTofuPrompt() },
            title = { Text(stringResource(R.string.tofu_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.tofu_confirm_body,
                        prompt.host,
                        prompt.fingerprint,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmTofu(prompt) }) {
                    Text(stringResource(R.string.tofu_confirm_positive))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissTofuPrompt() }) {
                    Text(stringResource(R.string.tofu_confirm_negative))
                }
            },
        )
    }
}

@Composable
private fun SyncStateBanner(
    bound: HomeUiState.Bound?,
    onResume: () -> Unit,
    onViewLogs: () -> Unit,
    onResolveConflict: () -> Unit,
) {
    if (bound == null) return
    // SPEC §4.6 Iteration 2 / fix CR P2-01: migration disabled overrides the
    // regular state banner so the user is pushed toward a re-bind flow rather
    // than silently stuck on an empty Home.
    if (bound.migrationDisabled) {
        Surface(color = Color(0xFFFFCDD2), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.banner_migration_disabled),
                color = Color(0xFF1F1F1F),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        return
    }
    val (bgColor, text) = bannerColorAndText(bound)
    var confirmResume by remember { mutableStateOf(false) }
    Surface(color = bgColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, color = Color(0xFF1F1F1F), modifier = Modifier.padding(end = 8.dp))
            // SPEC §4.5 Iteration 2: state machine rule requires that
            // PAUSED_* / BROKEN → IDLE is **only** driven by
            // ResumeFromPauseUseCase (the "Resume sync" button). BROKEN
            // additionally surfaces "View logs" as a diagnosis entry per §4.7.
            // SPEC §4.3.2 Iteration 3 (P0-2 / R10): PAUSED_CONFLICT now
            // surfaces "解决冲突" alongside "恢复同步".
            when (bound.syncState) {
                SyncState.PAUSED_CONFLICT -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onResolveConflict) {
                        Text(stringResource(R.string.action_resolve_conflict))
                    }
                    OutlinedButton(onClick = { confirmResume = true }) {
                        Text(stringResource(R.string.action_resume_sync))
                    }
                }
                SyncState.PAUSED_AUTH,
                SyncState.PAUSED_FS,
                -> OutlinedButton(onClick = { confirmResume = true }) {
                    Text(stringResource(R.string.action_resume_sync))
                }
                SyncState.BROKEN -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onViewLogs) {
                        Text(stringResource(R.string.action_view_logs))
                    }
                    OutlinedButton(onClick = { confirmResume = true }) {
                        Text(stringResource(R.string.action_resume_sync))
                    }
                }
                else -> Spacer(Modifier.size(0.dp))
            }
        }
    }
    if (confirmResume) {
        AlertDialog(
            onDismissRequest = { confirmResume = false },
            title = { Text(stringResource(R.string.resume_confirm_title)) },
            text = { Text(stringResource(R.string.resume_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResume = false
                    onResume()
                }) { Text(stringResource(R.string.resume_confirm_positive)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResume = false }) {
                    Text(stringResource(R.string.resume_confirm_negative))
                }
            },
        )
    }
}

@Composable
private fun bannerColorAndText(bound: HomeUiState.Bound): Pair<Color, String> = when (bound.syncState) {
    SyncState.IDLE -> Color(0xFFE8F5E9) to when {
        bound.intervalMinutes == SyncPolicyModel.MANUAL_ONLY ->
            stringResource(R.string.banner_idle_manual_only)
        bound.remoteUrl.isNullOrBlank() || bound.username.isNullOrBlank() ->
            stringResource(R.string.banner_idle_never)
        else ->
            stringResource(R.string.banner_idle_next_sync, bound.intervalMinutes)
    }
    SyncState.RUNNING -> Color(0xFFE3F2FD) to stringResource(R.string.banner_running)
    SyncState.PAUSED_CONFLICT -> Color(0xFFFFE0B2) to stringResource(R.string.banner_paused_conflict)
    SyncState.PAUSED_AUTH -> Color(0xFFFFE0B2) to stringResource(R.string.banner_paused_auth)
    SyncState.PAUSED_FS -> Color(0xFFFFE0B2) to stringResource(R.string.banner_paused_fs)
    SyncState.BROKEN -> Color(0xFFFFCDD2) to stringResource(R.string.banner_broken)
}

@Composable
private fun CredentialSection(
    username: String?,
    onSubmit: (String, String, CharArray) -> Unit,
    onClearClipboard: () -> Unit,
) {
    var u by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { pat = "" } }

    Text(stringResource(R.string.section_credential), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (username.isNullOrBlank()) stringResource(R.string.credential_missing)
        else stringResource(R.string.credential_bound, username),
    )
    OutlinedTextField(
        value = u,
        onValueChange = { u = it },
        label = { Text(stringResource(R.string.label_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = e,
        onValueChange = { e = it },
        label = { Text(stringResource(R.string.label_email)) },
        placeholder = {
            if (u.isNotBlank()) Text(stringResource(R.string.hint_email_default, u))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pat,
        onValueChange = { pat = it },
        label = { Text(stringResource(R.string.label_pat)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = u.isNotBlank() && pat.isNotBlank(),
            onClick = {
                val buf = pat.toCharArray()
                pat = ""
                onSubmit(u, e, buf)
            },
        ) { Text(stringResource(R.string.action_save_credential)) }
        OutlinedButton(onClick = onClearClipboard) {
            Text(stringResource(R.string.action_clear_clipboard))
        }
    }
}

@Composable
private fun VaultSection(
    currentAbsPath: String?,
    safState: SafResolveUiState,
    onPick: () -> Unit,
) {
    Text(stringResource(R.string.section_vault), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (currentAbsPath.isNullOrBlank()) stringResource(R.string.vault_missing)
        else stringResource(R.string.vault_bound, currentAbsPath),
    )
    when (safState) {
        SafResolveUiState.NotPrimary -> Text(
            text = stringResource(R.string.vault_not_primary),
            color = Color.Red,
        )
        SafResolveUiState.NotReadable -> Text(
            text = stringResource(R.string.vault_not_readable),
            color = Color.Red,
        )
        else -> Unit
    }
    Button(onClick = onPick) { Text(stringResource(R.string.action_pick_vault)) }
}

@Composable
private fun RemoteSection(
    currentUrl: String?,
    onSubmit: (String) -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl.orEmpty()) }

    Text(stringResource(R.string.section_remote), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (currentUrl.isNullOrBlank()) stringResource(R.string.remote_missing)
        else stringResource(R.string.remote_bound, currentUrl),
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.label_remote_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = url.isNotBlank(),
        onClick = { onSubmit(url.trim()) },
    ) { Text(stringResource(R.string.action_save_remote)) }
}

@Composable
private fun BrowseRepoSection(
    enabled: Boolean,
    onBrowse: () -> Unit,
) {
    if (!enabled) return
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onBrowse,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_browse_repo)) }
    }
}

/**
 * SPEC §4.4.3 / §5.1 Iteration 3 (P0-2): auth-mode radio row with an
 * SSH-key picker that appears only when SSH is selected. Persisting the
 * choice is decoupled from the Remote URL text field — flipping the radio
 * writes `repository.auth_type` + `repository.authRef` immediately.
 */
@Composable
private fun AuthModeSection(
    bound: HomeUiState.Bound?,
    sshKeys: List<com.example.simplygit.domain.model.SshKeyIndexEntry>,
    onSubmitAuth: (authType: String, keyId: String?) -> Unit,
    onOpenSshKeys: () -> Unit,
) {
    if (bound == null || bound.repoId == 0L) return
    Text(
        stringResource(R.string.section_auth_type),
        style = MaterialTheme.typography.titleMedium,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AuthModeRadio(
            selected = bound.authType == "PAT",
            label = stringResource(R.string.auth_type_pat),
            onClick = { onSubmitAuth("PAT", null) },
        )
        AuthModeRadio(
            selected = bound.authType == "SSH",
            label = stringResource(R.string.auth_type_ssh),
            onClick = {
                val firstKey = sshKeys.firstOrNull()
                if (firstKey != null) {
                    onSubmitAuth("SSH", firstKey.keyId)
                }
            },
        )
    }
    if (bound.authType == "SSH") {
        if (sshKeys.isEmpty()) {
            OutlinedButton(onClick = onOpenSshKeys) {
                Text(stringResource(R.string.ssh_key_select_none))
            }
        } else {
            Text(
                stringResource(R.string.ssh_key_select_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sshKeys.forEach { key ->
                    val currentRef = bound.authRef
                    val selected = currentRef == key.keyId
                    AuthModeRadio(
                        selected = selected,
                        label = stringResource(
                            R.string.ssh_key_select_option,
                            key.keyId,
                            key.fingerprintSha256,
                        ),
                        onClick = { onSubmitAuth("SSH", key.keyId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthModeRadio(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OperationsSection(
    enabled: Boolean,
    onClone: () -> Unit,
    onPull: () -> Unit,
    onCommit: (String) -> Unit,
    onPush: () -> Unit,
) {
    var msg by remember { mutableStateOf("") }

    Text(stringResource(R.string.section_ops), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = msg,
        onValueChange = { msg = it },
        label = { Text(stringResource(R.string.label_commit_message)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(enabled = enabled, onClick = onClone) { Text(stringResource(R.string.action_clone)) }
        Button(enabled = enabled, onClick = onPull) { Text(stringResource(R.string.action_pull)) }
        Button(
            enabled = enabled && msg.isNotBlank(),
            onClick = { onCommit(msg.trim()) },
        ) { Text(stringResource(R.string.action_commit)) }
        Button(enabled = enabled, onClick = onPush) { Text(stringResource(R.string.action_push)) }
    }
}

@Composable
private fun StatusSection(
    uiState: HomeUiState,
    onDismissError: () -> Unit,
) {
    Text(stringResource(R.string.section_log), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(2.dp))
    when (uiState) {
        HomeUiState.Idle -> Text(stringResource(R.string.status_idle))
        is HomeUiState.Working -> Text(stringResource(R.string.status_working, uiState.op.name))
        is HomeUiState.Error -> {
            Text(
                text = stringResource(
                    R.string.status_error,
                    uiState.op.name,
                    errorText(uiState.messageKind),
                ),
                color = Color.Red,
            )
            OutlinedButton(onClick = onDismissError) {
                Text(stringResource(R.string.action_dismiss_error))
            }
        }
        is HomeUiState.Bound -> {
            val last = uiState.lastSuccess
            if (last != null) {
                Text(successLabel(last.op, last.description))
            } else {
                Text(stringResource(R.string.status_idle))
            }
        }
    }
}

@Composable
private fun errorText(kind: ErrorKind): String = when (kind) {
    is ErrorKind.Sanitized -> kind.message
    ErrorKind.MissingCredential -> stringResource(R.string.error_missing_credential)
    ErrorKind.MissingBinding -> stringResource(R.string.error_binding_missing)
    ErrorKind.SafPermissionRevoked -> stringResource(R.string.error_saf_permission_revoked)
}

@Composable
private fun successLabel(op: GitOp, desc: String): String = when (op) {
    GitOp.CLONE -> stringResource(R.string.status_success_clone)
    GitOp.PULL -> {
        val parts = desc.split("|", limit = 2)
        val count = parts.firstOrNull()?.toIntOrNull() ?: 0
        val status = parts.getOrNull(1).orEmpty()
        val core = if (status.isBlank()) {
            stringResource(R.string.status_pulled_commits, count)
        } else {
            stringResource(R.string.status_pulled_commits_with_status, count, status)
        }
        stringResource(R.string.status_success_pull, core)
    }
    GitOp.COMMIT -> stringResource(R.string.status_success_commit)
    GitOp.PUSH -> stringResource(R.string.status_success_push)
}
