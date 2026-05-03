@file:Suppress("UNCHECKED_CAST")

package com.example.simplygit.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simplygit.data.git.PullOutcome
import com.example.simplygit.data.git.SafPermissionRevokedException
import com.example.simplygit.data.git.SanitizedGitException
import com.example.simplygit.data.git.JGitExceptionSanitizer
import com.example.simplygit.data.sync.RepositoryDao
import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.RepositoryStateSnapshot
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.model.SyncTrigger
import com.example.simplygit.domain.repository.CredentialPublicView
import com.example.simplygit.domain.repository.CredentialRepository
import com.example.simplygit.domain.repository.RepoBindingPartial
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SshKeyRepository
import com.example.simplygit.domain.repository.SyncLogRepository
import com.example.simplygit.domain.repository.SyncPolicyRepository
import com.example.simplygit.domain.usecase.BindRemoteUseCase
import com.example.simplygit.domain.usecase.BindVaultOutcome
import com.example.simplygit.domain.usecase.BindVaultUseCase
import com.example.simplygit.domain.usecase.CloneRepoUseCase
import com.example.simplygit.domain.usecase.CommitLocalUseCase
import com.example.simplygit.domain.usecase.MissingBindingException
import com.example.simplygit.domain.usecase.MissingCredentialException
import com.example.simplygit.domain.usecase.PullRepoUseCase
import com.example.simplygit.domain.usecase.PushRepoUseCase
import com.example.simplygit.domain.usecase.ResumeFromPauseUseCase
import com.example.simplygit.domain.usecase.SaveAuthTypeUseCase
import com.example.simplygit.notification.NotificationPermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Arrays
import javax.inject.Inject

/** User intents surfaced from [HomeScreen] (SPEC §4.5 / §4.7). */
sealed interface HomeIntent {
    data class SubmitCredential(val username: String, val email: String, val pat: CharArray) : HomeIntent
    data class PickVault(val uri: Uri) : HomeIntent
    data object PickVaultPermissionFailed : HomeIntent
    data class SubmitRemote(val url: String) : HomeIntent
    data object DoClone : HomeIntent
    data object DoPull : HomeIntent
    data class DoCommit(val message: String) : HomeIntent
    data object DoPush : HomeIntent
    data object DismissError : HomeIntent
    data object Reset : HomeIntent
    data object ResumeSync : HomeIntent
    data object RefreshNotificationPermission : HomeIntent
    /**
     * SPEC §4.4.3 Iteration 3: user flipped the auth-mode radio on the
     * bind form. `keyId` is ignored when [authType] is `"PAT"`; when it is
     * `"SSH"` the ViewModel builds `authRef = "ssh_$keyId"`.
     */
    data class SubmitAuthType(val authType: String, val keyId: String?) : HomeIntent

    /**
     * User tapped the "解绑" button on the credential section — wipes the
     * stored GitHub username / email / PAT. The bind form reappears on the
     * next recomposition because `credentialView` becomes `null`.
     */
    data object ClearCredential : HomeIntent
}

private const val CLIPBOARD_TAG = "simplygit-pat"
private const val CLIPBOARD_CLEAR_DELAY_MS = 60_000L

@HiltViewModel
@Suppress("LongParameterList")
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val credRepo: CredentialRepository,
    private val bindingRepo: RepoBindingRepository,
    private val syncPolicyRepo: SyncPolicyRepository,
    private val syncLogRepo: SyncLogRepository,
    private val repositoryDao: RepositoryDao,
    private val sshKeyRepo: SshKeyRepository,
    private val bindVault: BindVaultUseCase,
    private val bindRemote: BindRemoteUseCase,
    private val cloneRepo: CloneRepoUseCase,
    private val pullRepo: PullRepoUseCase,
    private val commitLocal: CommitLocalUseCase,
    private val pushRepo: PushRepoUseCase,
    private val resumeSync: ResumeFromPauseUseCase,
    private val saveAuthType: SaveAuthTypeUseCase,
    private val jgitExceptionSanitizer: JGitExceptionSanitizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _safState = MutableStateFlow<SafResolveUiState>(SafResolveUiState.None)
    val safState: StateFlow<SafResolveUiState> = _safState.asStateFlow()

    /**
     * SPEC §4.4.2 Iteration 3 (P0-6 TOFU): pending first-connect dialog.
     * Non-null triggers [HomeScreen] to render the confirm modal.
     */
    private val _tofuPrompt = MutableStateFlow<TofuPrompt?>(null)
    val tofuPrompt: StateFlow<TofuPrompt?> = _tofuPrompt.asStateFlow()

    private val _notificationGranted = MutableStateFlow(
        NotificationPermissionHelper.isGranted(appContext),
    )

    /**
     * SPEC §4.6 Iteration 2 / fix CR P2-01: `true` when DataStore → Room
     * migration has failed [RepoBindingRepository.MAX_MIGRATION_RETRIES]
     * times. Polled once at cold start + re-polled on binding writes — its
     * value is stable until the next app launch so we don't need a Flow.
     */
    private val _migrationDisabled = MutableStateFlow(false)

    /**
     * SPEC §4.6 / L-6: the "已绑定 @username" badge must stay visible even while a Git
     * operation is Working / Error. We project the public view as a dedicated flow so
     * the UI no longer has to key off [HomeUiState.Bound].
     */
    val credentialView: StateFlow<CredentialPublicView?> =
        credRepo.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * SPEC §4.4.3 Iteration 3: SSH key list published to the bind-form
     * auth-mode radio's sub-dropdown. Empty list means the user has not
     * generated or imported a key yet — UI surfaces a link to
     * [Routes.SSH_KEYS] in that case.
     */
    val sshKeys: StateFlow<List<com.example.simplygit.domain.model.SshKeyIndexEntry>> =
        sshKeyRepo.observeIndex()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // SPEC §4.6 (CR P2-01): read the migration-disabled flag once; its
        // value is stable until the next cold start.
        viewModelScope.launch {
            _migrationDisabled.value = runCatching { bindingRepo.isMigrationDisabled() }
                .getOrDefault(false)
        }

        // SPEC §4.5 Iteration 1 + §4.7 Iteration 2:
        // merge binding / credential / sync-state / policy / pending-count /
        // notification-granted / migration-disabled into a single Bound projection.
        //
        // 注意：这里使用 [RepoBindingRepository.observePartial] 而不是 observe()，
        // 因为 observe() 会在 Vault 或 Remote 任一字段为空时整体映射成 null，
        // 导致用户在"先选 Vault 再填远程"的正常流程中，选完 Vault 后首页仍显示
        // "未绑定 Vault 目录"。UI 只关心各字段的有无，完整性交给下游 UseCase
        // （requireCurrent / currentOrNull 走 toBindingOrNull）去把关。
        combine(
            bindingRepo.observePartial(),
            credRepo.observe(),
            syncLogRepo.observeRepoStateOrDefault(),
            syncPolicyRepo.observe(),
            repositoryDao.observePausedCount(),
            _notificationGranted,
            _migrationDisabled,
        ) { values ->
            val binding = values[0] as RepoBindingPartial?
            val cred = values[1] as CredentialPublicView?
            val state = values[2] as RepositoryStateSnapshot
            val policy = values[3] as SyncPolicyModel
            val pending = values[4] as Int
            val notifGranted = values[5] as Boolean
            val migrationDisabled = values[6] as Boolean
            HomeBoundSnapshot(
                binding = binding,
                cred = cred,
                syncState = state.syncState,
                intervalMinutes = policy.intervalMinutes,
                pendingCount = pending,
                notificationGranted = notifGranted,
                migrationDisabled = migrationDisabled,
            )
        }.onEach { snapshot ->
            val prev = _uiState.value
            if (prev is HomeUiState.Working) return@onEach
            val keepLast = (prev as? HomeUiState.Bound)?.lastSuccess
            if (prev is HomeUiState.Error) return@onEach
            _uiState.value = HomeUiState.Bound(
                treeUri = snapshot.binding?.treeUri,
                localAbsPath = snapshot.binding?.localAbsPath,
                remoteUrl = snapshot.binding?.remoteUrl,
                username = snapshot.cred?.username,
                lastSuccess = keepLast,
                syncState = snapshot.syncState,
                intervalMinutes = snapshot.intervalMinutes,
                pendingAlertCount = snapshot.pendingCount,
                notificationGranted = snapshot.notificationGranted,
                migrationDisabled = snapshot.migrationDisabled,
                repoId = snapshot.binding?.id ?: 0L,
                authType = snapshot.binding?.authType ?: "PAT",
                authRef = snapshot.binding?.authRef ?: "github_pat",
            )
        }.launchIn(viewModelScope)
    }

    fun onIntent(intent: HomeIntent) {
        // SPEC §4.5 Iteration 1: single-op serialization — drop intents while Working.
        if (_uiState.value is HomeUiState.Working &&
            intent !is HomeIntent.DismissError &&
            intent !is HomeIntent.RefreshNotificationPermission
        ) {
            return
        }
        when (intent) {
            is HomeIntent.SubmitCredential -> submitCredential(intent)
            is HomeIntent.PickVault -> pickVault(intent.uri)
            HomeIntent.PickVaultPermissionFailed -> _safState.value = SafResolveUiState.PermissionNotPersisted
            is HomeIntent.SubmitRemote -> viewModelScope.launch {
                runCatching { bindRemote(intent.url) }
            }
            HomeIntent.DoClone -> runOp(GitOp.CLONE) { cloneRepo() }
            HomeIntent.DoPull -> runOp(GitOp.PULL) { pullRepo() }
            is HomeIntent.DoCommit -> runOp(GitOp.COMMIT) { commitLocal(intent.message) }
            HomeIntent.DoPush -> runOp(GitOp.PUSH) { pushRepo() }
            HomeIntent.DismissError -> dismissError()
            HomeIntent.Reset -> _uiState.value = HomeUiState.Idle
            HomeIntent.ResumeSync -> viewModelScope.launch { runCatching { resumeSync() } }
            HomeIntent.RefreshNotificationPermission ->
                _notificationGranted.value = NotificationPermissionHelper.isGranted(appContext)
            is HomeIntent.SubmitAuthType -> submitAuthType(intent)
            HomeIntent.ClearCredential -> viewModelScope.launch {
                runCatching { credRepo.clear() }
            }
        }
    }

    /**
     * SPEC §4.4.3 Iteration 3: flip the binding between PAT and SSH.
     *
     *  - PAT: [authRef] is fixed to the single-slot key `"github_pat"`;
     *  - SSH: the caller must have picked a `keyId`, which becomes
     *    `ssh_<keyId>` — we fail silently (log + no-op) when `keyId` is null
     *    so stale UI state cannot corrupt the binding row.
     */
    private fun submitAuthType(intent: HomeIntent.SubmitAuthType) {
        viewModelScope.launch {
            runCatching {
                when (intent.authType) {
                    "PAT" -> saveAuthType("PAT", "github_pat")
                    "SSH" -> {
                        val id = intent.keyId
                        require(!id.isNullOrBlank()) { "keyId required for SSH" }
                        // Callers pass keyId **without** the "ssh_" prefix.
                        val ref = if (id.startsWith("ssh_")) id else "ssh_$id"
                        saveAuthType("SSH", ref)
                    }
                    else -> error("unknown authType=${intent.authType}")
                }
            }
        }
    }

    private fun submitCredential(intent: HomeIntent.SubmitCredential) {
        viewModelScope.launch {
            try {
                if (intent.pat.isEmpty()) {
                    credRepo.saveIdentity(intent.username.trim(), intent.email.trim())
                } else {
                    credRepo.save(intent.username.trim(), intent.email.trim(), intent.pat)
                    scheduleClipboardClear()
                }
            } finally {
                Arrays.fill(intent.pat, '\u0000')
            }
        }
    }

    private fun pickVault(uri: Uri) {
        viewModelScope.launch {
            when (val r = bindVault(uri)) {
                is BindVaultOutcome.Bound -> _safState.value = SafResolveUiState.Bound(r.absPath)
                BindVaultOutcome.NotPrimary -> _safState.value = SafResolveUiState.NotPrimary
                BindVaultOutcome.NotReadable -> _safState.value = SafResolveUiState.NotReadable
            }
        }
    }

    private fun runOp(op: GitOp, block: suspend () -> GitOpResult) {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Working(op, System.currentTimeMillis())

            // 手动操作审计：在仓库行已存在（repoId > 0）时开一条 `SyncLog`，
            // `trigger = MANUAL`。SPEC I-7 原本规定"手动按钮不落 SyncLog"，
            // 但用户反馈在"同步审计"页完全看不到自己主动触发的操作，与直觉
            // 不符 —— 遂放开这条限制：**只写 SyncLog，不动 `syncState`**，
            // 状态机独立性仍然由 `RunSyncUseCase` 独占维护。
            //
            // Clone 之前通常也已有 binding row（Vault + Remote 已填），因此
            // 这里不对 op 做白名单过滤；若确实没有 binding row（repoId = 0）
            // 就跳过审计写入，避免污染 repoId=0 的占位行。
            val repoIdForAudit = bindingRepo.observePartial().firstOrNull()?.id ?: 0L
            val manualLogId: Long? = if (repoIdForAudit > 0L) {
                runCatching {
                    syncLogRepo.startLog(
                        repoId = repoIdForAudit,
                        trigger = SyncTrigger.MANUAL,
                        now = Instant.now(),
                    )
                }.getOrNull()
            } else {
                null
            }

            val result = block()
            _uiState.value = when (result) {
                GitOpResult.Success -> {
                    finishManualLogSuccess(manualLogId, op, payload = null)
                    snapshotBound(LastOpSummary(op, successDesc(op, null)))
                }
                is GitOpResult.SuccessWithPayload -> {
                    finishManualLogSuccess(manualLogId, op, payload = result.payload)
                    snapshotBound(LastOpSummary(op, successDesc(op, result.payload)))
                }
                is GitOpResult.Failure -> {
                    // SPEC §4.4.2 Iteration 3 (P0-6): TOFU first-connect
                    // bypasses the normal Error state — we surface a confirm
                    // dialog instead so the user can accept or cancel the
                    // fingerprint. Cancelling simply returns to the last
                    // bound snapshot without writing known_hosts.
                    val tofu = result.cause as?
                        com.example.simplygit.data.ssh.SshHostKeyFirstConnectException
                    if (tofu != null) {
                        // TOFU 首连确认本身不应被当作"手动操作失败"记入审计，
                        // 因为后续用户确认 / 取消会重跑。这里直接 abort 当前
                        // 审计行（若有）并让重试写一条新的。
                        if (manualLogId != null) {
                            runCatching {
                                syncLogRepo.finishLog(
                                    logId = manualLogId,
                                    result = SyncResult.ABORTED,
                                    endedAt = Instant.now(),
                                )
                            }
                        }
                        _tofuPrompt.value = TofuPrompt(
                            host = tofu.host,
                            fingerprint = tofu.fingerprint,
                            pendingOp = op,
                        )
                        snapshotBound(last = null)
                    } else {
                        finishManualLogFailure(manualLogId, result.cause)
                        HomeUiState.Error(op, toErrorKind(result.cause))
                    }
                }
            }
        }
    }

    /**
     * 手动操作成功后写审计：按 op 类型映射到合适的 [SyncResult] 与统计字段。
     * 不动 `syncState`（维持 I-7 状态机独立性）。
     */
    private suspend fun finishManualLogSuccess(
        logId: Long?,
        op: GitOp,
        payload: Any?,
    ) {
        if (logId == null) return
        val pullOutcome = payload as? PullOutcome
        runCatching {
            syncLogRepo.finishLog(
                logId = logId,
                result = SyncResult.OK,
                endedAt = Instant.now(),
                commitsPulled = if (op == GitOp.PULL) (pullOutcome?.commitsPulled ?: 0) else 0,
                commitsPushed = if (op == GitOp.PUSH) 1 else 0,
                filesChanged = 0,
            )
        }
    }

    /**
     * 手动操作失败后写审计：复用 [JGitExceptionSanitizer] 已产出的
     * [SanitizedGitException.kind] 做最小分类，与 [RunSyncUseCase] 的错误分派
     * 保持一致。**不动 `syncState`**、**不更新 `BROKEN` 失败计数的触发逻辑**
     * —— 但写入的行会被 `recentConsecutiveFailures()` 扫描到，属于预期行为。
     */
    private suspend fun finishManualLogFailure(logId: Long?, cause: Throwable) {
        if (logId == null) return
        val (result, errMsg, errType) = when (cause) {
            is SanitizedGitException -> Triple(
                when (cause.kind) {
                    com.example.simplygit.data.git.SyncErrorKind.Auth -> SyncResult.AUTH_ERR
                    com.example.simplygit.data.git.SyncErrorKind.Network -> SyncResult.NETWORK_ERR
                    com.example.simplygit.data.git.SyncErrorKind.InvalidState,
                    com.example.simplygit.data.git.SyncErrorKind.Unknown,
                    -> SyncResult.ABORTED
                },
                cause.message,
                cause.originalType,
            )
            is SafPermissionRevokedException -> Triple(
                SyncResult.FS_ERR,
                cause.javaClass.simpleName,
                cause.javaClass.simpleName,
            )
            is MissingCredentialException -> Triple(
                SyncResult.AUTH_ERR,
                cause.javaClass.simpleName,
                cause.javaClass.simpleName,
            )
            is MissingBindingException -> Triple(
                SyncResult.ABORTED,
                cause.javaClass.simpleName,
                cause.javaClass.simpleName,
            )
            else -> {
                // BUG-003 fix (bug_report_20260503_snao): any Throwable that
                // slipped past `GitOpPreflight.afterOp`'s sanitizer wrapping
                // (e.g. a future UseCase forgetting the contract) must still
                // be redacted before landing in `sync_log.errorMsg` (R8).
                val sanitized = jgitExceptionSanitizer.sanitize(cause)
                Triple(SyncResult.ABORTED, sanitized.message, sanitized.originalType)
            }
        }
        runCatching {
            syncLogRepo.finishLog(
                logId = logId,
                result = result,
                endedAt = Instant.now(),
                errorMsg = errMsg,
                errorType = errType,
            )
        }
    }

    /**
     * SPEC §4.4.2 Iteration 3 (P0-6 TOFU): called when the user taps
     * "confirm" on the first-connect dialog — persists the fingerprint and
     * retries the pending op. Cancellation routes through
     * [dismissTofuPrompt].
     */
    fun confirmTofu(prompt: TofuPrompt) {
        viewModelScope.launch {
            runCatching {
                sshKeyRepo.acceptHostKey(prompt.host, prompt.fingerprint)
            }
            _tofuPrompt.value = null
            when (prompt.pendingOp) {
                GitOp.CLONE -> onIntent(HomeIntent.DoClone)
                GitOp.PULL -> onIntent(HomeIntent.DoPull)
                GitOp.PUSH -> onIntent(HomeIntent.DoPush)
                GitOp.COMMIT -> Unit // commit never hits the network
            }
        }
    }

    fun dismissTofuPrompt() {
        _tofuPrompt.value = null
    }

    private fun dismissError() {
        val current = _uiState.value
        if (current is HomeUiState.Error) {
            viewModelScope.launch {
                _uiState.value = snapshotBound(last = null)
            }
        }
    }

    private fun toErrorKind(cause: Throwable): ErrorKind = when (cause) {
        is MissingCredentialException -> ErrorKind.MissingCredential
        is MissingBindingException -> ErrorKind.MissingBinding
        is SafPermissionRevokedException -> ErrorKind.SafPermissionRevoked
        is SanitizedGitException -> ErrorKind.Sanitized(cause.message.orEmpty())
        else -> ErrorKind.Sanitized(cause.javaClass.simpleName)
    }

    private suspend fun snapshotBound(last: LastOpSummary?): HomeUiState.Bound {
        // 使用 observePartial 而非 requireCurrent：局部绑定（只有 Vault 或只有
        // Remote）场景下也要能刷新到 UI，不然 dismiss error / 完成手动 op 后
        // 首页可能回退成"未绑定任何字段"的错觉。
        val binding = bindingRepo.observePartial().firstOrNull()
        val cred = credRepo.observe().firstOrNull()
        val state = syncLogRepo.observeRepoStateOrDefault().firstOrNull()
            ?: RepositoryStateSnapshot(
                repoId = binding?.id ?: 0L,
                syncState = SyncState.IDLE,
                lastSyncAt = null,
                lastSyncResult = null,
            )
        val policy = syncPolicyRepo.current()
        val pending = repositoryDao.observePausedCount().firstOrNull() ?: 0
        return HomeUiState.Bound(
            treeUri = binding?.treeUri,
            localAbsPath = binding?.localAbsPath,
            remoteUrl = binding?.remoteUrl,
            username = cred?.username,
            lastSuccess = last,
            syncState = state.syncState,
            intervalMinutes = policy.intervalMinutes,
            pendingAlertCount = pending,
            notificationGranted = _notificationGranted.value,
            migrationDisabled = _migrationDisabled.value,
            repoId = binding?.id ?: 0L,
            authType = binding?.authType ?: "PAT",
            authRef = binding?.authRef ?: "github_pat",
        )
    }

    private fun successDesc(op: GitOp, payload: Any?): String = when (op) {
        GitOp.PULL -> {
            val outcome = payload as? PullOutcome
            val count = outcome?.commitsPulled ?: 0
            val status = outcome?.mergeStatus
            "$count|${status ?: ""}"
        }
        GitOp.CLONE, GitOp.COMMIT, GitOp.PUSH -> ""
    }

    private fun scheduleClipboardClear() {
        viewModelScope.launch {
            delay(CLIPBOARD_CLEAR_DELAY_MS)
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@launch
            val current = cm.primaryClip ?: return@launch
            if (current.description?.label == CLIPBOARD_TAG) clearClipboardCompat(cm)
        }
    }

    fun clearClipboardNow() {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clearClipboardCompat(cm)
    }

    private fun clearClipboardCompat(cm: ClipboardManager) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            cm.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_TAG, ""))
        }
    }
}

private data class HomeBoundSnapshot(
    val binding: RepoBindingPartial?,
    val cred: CredentialPublicView?,
    val syncState: SyncState,
    val intervalMinutes: Int,
    val pendingCount: Int,
    val notificationGranted: Boolean,
    val migrationDisabled: Boolean,
)

/** Returns a fallback IDLE snapshot when no binding row exists yet. */
private fun SyncLogRepository.observeRepoStateOrDefault() =
    kotlinx.coroutines.flow.flow {
        // We don't know the repoId until a binding exists. Emit an IDLE default first
        // so the combine() has a value, then delegate to the repo's Flow.
        emit(
            RepositoryStateSnapshot(
                repoId = 0L,
                syncState = SyncState.IDLE,
                lastSyncAt = null,
                lastSyncResult = null,
            ),
        )
        observeRepoState(0L).collect { emit(it) }
    }

/** One-shot signals surfaced to [HomeScreen] (not currently consumed). */
sealed interface HomeEvent
