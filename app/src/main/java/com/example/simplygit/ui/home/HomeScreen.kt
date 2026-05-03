package com.example.simplygit.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.simplygit.R
import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncState

/**
 * Home screen (SPEC §4.5 Iteration 1, §4.7 Iteration 2).
 *
 * SPEC I-7 (修订)：四个 Git 按钮（Clone / Pull / Commit / Push）继续走
 * Iteration 1 的**手动 UseCase 链路**，**不触碰** `syncState`。但为了让用户
 * 能在"同步审计"页看到自己触发的手动操作，[HomeViewModel.runOp] 会以
 * `SyncTrigger.MANUAL` 写入一条 `SyncLog`（成功 / 失败都写）；状态机独立性
 * 依然由 `RunSyncUseCase` 独占维护，离开 `PAUSED_*` 仍必须点"恢复同步"。
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
                    // 顶栏只保留两枚矢量图标：设置（齿轮）/ 历史日志（带
                    // 时钟的文档）。原先 Unicode 文本图标（⚙ / ⟳ / ⋮）
                    // 在系统字体下尺寸与基线不一致，同时也无法做到与
                    // SVG 资产等同的品牌表达；现改为 [painterResource]
                    // + `Modifier.size(24.dp)` 的标准 Material 图标范式。
                    //
                    // "SSH 密钥" 入口已从此处的 overflow 菜单迁移至
                    // SyncPolicyScreen（设置页），顶栏不再保留三点按钮。
                    IconButton(onClick = onOpenPolicy) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.action_open_policy),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = onOpenAudit) {
                            Icon(
                                painter = painterResource(R.drawable.ic_history_log),
                                contentDescription = stringResource(R.string.action_open_audit),
                                modifier = Modifier.size(24.dp),
                            )
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
                authType = bound?.authType ?: "PAT",
                onSubmit = { u, e, pat -> viewModel.onIntent(HomeIntent.SubmitCredential(u, e, pat)) },
                onClearClipboard = { viewModel.clearClipboardNow() },
                onUnbind = { viewModel.onIntent(HomeIntent.ClearCredential) },
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
    authType: String,
    onSubmit: (String, String, CharArray) -> Unit,
    onClearClipboard: () -> Unit,
    onUnbind: () -> Unit,
) {
    var u by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var confirmUnbind by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { pat = "" } }

    val bound = !username.isNullOrBlank()

    Text(stringResource(R.string.section_credential), style = MaterialTheme.typography.titleMedium)
    Text(
        text = if (!bound) stringResource(R.string.credential_missing)
        else stringResource(R.string.credential_bound, username!!),
    )
    if (bound) {
        // 已绑定：隐藏三个输入框，仅展示"解绑"按钮。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmUnbind = true }) {
                Text(stringResource(R.string.action_unbind_credential))
            }
            OutlinedButton(onClick = onClearClipboard) {
                Text(stringResource(R.string.action_clear_clipboard))
            }
        }
        if (confirmUnbind) {
            AlertDialog(
                onDismissRequest = { confirmUnbind = false },
                title = { Text(stringResource(R.string.unbind_confirm_title)) },
                text = { Text(stringResource(R.string.unbind_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUnbind = false
                        // 清空本地编辑态，避免上次未提交的用户名/邮箱/PAT 残留。
                        u = ""
                        e = ""
                        pat = ""
                        onUnbind()
                    }) { Text(stringResource(R.string.unbind_confirm_positive)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUnbind = false }) {
                        Text(stringResource(R.string.unbind_confirm_negative))
                    }
                },
            )
        }
        return
    }

    // 未绑定：展示三个输入框 + "保存凭证" + "清空剪贴板"。
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
            enabled = u.isNotBlank() && (authType == "SSH" || pat.isNotBlank()),
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

/**
 * Home top-level Git 操作区块 — Clone / Pull / Commit / Push。
 *
 * 之前存在独立的 "快捷操作"（仅手动 Pull / Push）+ 底部 "Git 操作"（Clone /
 * Pull / Commit / Push）两个冗余区块，交互上让人迷惑。这里统一成单个
 * OperationsSection，四个按钮并排 + 上方 Commit 信息框，并上移到 Vault /
 * Remote 之前，作为仓库已绑定后的主操作入口。
 *
 * 语义仍遵循 I-7：手动按钮调用 Iteration 1 的原 UseCase 链路，**不触碰**
 * `syncState`。但手动操作**会**写入 `SyncLog`（trigger = MANUAL），以便用户
 * 在"同步审计"页看到自己主动触发过的记录。
 */
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
        SafResolveUiState.PermissionNotPersisted -> Text(
            text = stringResource(R.string.vault_permission_not_persisted),
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
    var selectedAuthType by remember(bound.repoId, bound.authType) {
        mutableStateOf(bound.authType)
    }

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
            selected = selectedAuthType == "PAT",
            label = stringResource(R.string.auth_type_pat),
            onClick = {
                selectedAuthType = "PAT"
                onSubmitAuth("PAT", null)
            },
        )
        AuthModeRadio(
            selected = selectedAuthType == "SSH",
            label = stringResource(R.string.auth_type_ssh),
            onClick = {
                selectedAuthType = "SSH"
                sshKeys.firstOrNull()?.let { key ->
                    onSubmitAuth("SSH", key.keyId)
                }
            },
        )
    }
    if (selectedAuthType == "SSH" && sshKeys.isEmpty()) {
        OutlinedButton(onClick = onOpenSshKeys) {
            Text(stringResource(R.string.ssh_key_select_none))
        }
    }
    if (selectedAuthType == "SSH" && sshKeys.isNotEmpty()) {
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
private fun StatusSection(
    uiState: HomeUiState,
    onDismissError: () -> Unit,
) {
    Text(stringResource(R.string.section_log), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    when (uiState) {
        HomeUiState.Idle -> StatusCard(
            kind = StatusKind.Idle,
            title = stringResource(R.string.status_idle),
            subtitle = stringResource(R.string.status_idle_subtitle),
        )
        is HomeUiState.Working -> StatusCard(
            kind = StatusKind.Working,
            title = stringResource(R.string.status_working, uiState.op.name),
            subtitle = stringResource(R.string.status_working_subtitle),
        )
        is HomeUiState.Error -> StatusCard(
            kind = StatusKind.Error,
            title = stringResource(R.string.status_error_title, uiState.op.name),
            subtitle = errorText(uiState.messageKind),
            trailing = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.action_dismiss_error))
                }
            },
        )
        is HomeUiState.Bound -> {
            val last = uiState.lastSuccess
            if (last != null) {
                val (title, subtitle) = successLabel(last.op, last.description)
                StatusCard(
                    kind = StatusKind.Success,
                    title = title,
                    subtitle = subtitle,
                )
            } else {
                StatusCard(
                    kind = StatusKind.Idle,
                    title = stringResource(R.string.status_idle),
                    subtitle = stringResource(R.string.status_idle_subtitle),
                )
            }
        }
    }
}

/**
 * 同步状态卡片的视觉变体（色带 + 图标 + 背景色）。使用固定的柔和配色，避免
 * 把 JGit / MergeStatus 等技术细节直接暴露给用户；所有文案在外层通过
 * string resource 映射后传入。
 */
private enum class StatusKind { Idle, Working, Success, Error }

private data class StatusPalette(
    val background: Color,
    val accent: Color,
    val iconSymbol: String,
    val iconTint: Color,
)

@Composable
private fun paletteFor(kind: StatusKind): StatusPalette = when (kind) {
    StatusKind.Idle -> StatusPalette(
        background = Color(0xFFF2F4F7),
        accent = Color(0xFFBDBDBD),
        iconSymbol = "–",
        iconTint = Color(0xFF616161),
    )
    StatusKind.Working -> StatusPalette(
        background = Color(0xFFE3F2FD),
        accent = Color(0xFF1976D2),
        iconSymbol = "⟳",
        iconTint = Color(0xFF0D47A1),
    )
    StatusKind.Success -> StatusPalette(
        background = Color(0xFFE8F5E9),
        accent = Color(0xFF2E7D32),
        iconSymbol = "✓",
        iconTint = Color(0xFF1B5E20),
    )
    StatusKind.Error -> StatusPalette(
        background = Color(0xFFFFEBEE),
        accent = Color(0xFFC62828),
        iconSymbol = "!",
        iconTint = Color(0xFFB71C1C),
    )
}

@Composable
private fun StatusCard(
    kind: StatusKind,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = paletteFor(kind)
    Surface(
        color = palette.background,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧状态色条：用 4dp 宽的彩色竖条强化"成功 / 失败 / 进行中"的
            // 快速识别，避免纯色卡片在色弱 / 高对比度主题下辨识度不足。
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 32.dp)
                    .background(
                        color = palette.accent,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.size(10.dp))
            // 圆形图标徽章
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = palette.accent.copy(alpha = 0.15f),
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = palette.iconSymbol,
                    color = palette.iconTint,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color(0xFF1F1F1F),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = Color(0xFF555555),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.size(8.dp))
                trailing()
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

/**
 * 把 `(op, description)` 翻译成产品化的 `(主标题, 副文案)` 二元组。
 *
 * 其中 Pull 的 `description` 是 `"<count>|<mergeStatusName>"`，
 * `mergeStatusName` 来自 JGit `MergeResult.MergeStatus.name()`（例如
 * `ALREADY_UP_TO_DATE` / `FAST_FORWARD` / `MERGED`）。这里集中做本地化
 * 映射，避免把原始 enum 名外泄给终端用户。
 */
@Composable
private fun successLabel(op: GitOp, desc: String): Pair<String, String?> = when (op) {
    GitOp.CLONE -> stringResource(R.string.status_success_clone_title) to
        stringResource(R.string.status_success_clone_subtitle)
    GitOp.COMMIT -> stringResource(R.string.status_success_commit_title) to
        stringResource(R.string.status_success_commit_subtitle)
    GitOp.PUSH -> stringResource(R.string.status_success_push_title) to
        stringResource(R.string.status_success_push_subtitle)
    GitOp.PULL -> {
        val parts = desc.split("|", limit = 2)
        val count = parts.firstOrNull()?.toIntOrNull() ?: 0
        val status = parts.getOrNull(1).orEmpty()
        val subtitle = pullSubtitleFor(count, status)
        stringResource(R.string.status_success_pull_title) to subtitle
    }
}

/**
 * Pull 副文案分档：
 *  - `ALREADY_UP_TO_DATE` 或 `count == 0` → "已是最新，无新提交"
 *  - `FAST_FORWARD` / `FAST_FORWARD_SQUASHED` → "已拉取 N 个新提交"
 *  - `MERGED` / `MERGED_SQUASHED` / `MERGED_NOT_COMMITTED` 等 → "已合并 N 个新提交"
 *  - 其他（包括空 status）→ 退化为"已拉取 N 个新提交"
 */
@Composable
private fun pullSubtitleFor(count: Int, mergeStatusName: String): String {
    if (count == 0 || mergeStatusName == "ALREADY_UP_TO_DATE") {
        return stringResource(R.string.status_pull_up_to_date)
    }
    return when {
        mergeStatusName.startsWith("FAST_FORWARD") ->
            stringResource(R.string.status_pull_fast_forward, count)
        mergeStatusName.startsWith("MERGED") ->
            stringResource(R.string.status_pull_merged, count)
        else -> stringResource(R.string.status_pull_generic, count)
    }
}
