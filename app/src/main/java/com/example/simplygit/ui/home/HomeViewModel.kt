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
import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.repository.CredentialPublicView
import com.example.simplygit.domain.repository.CredentialRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.usecase.BindRemoteUseCase
import com.example.simplygit.domain.usecase.BindVaultOutcome
import com.example.simplygit.domain.usecase.BindVaultUseCase
import com.example.simplygit.domain.usecase.CloneRepoUseCase
import com.example.simplygit.domain.usecase.CommitLocalUseCase
import com.example.simplygit.domain.usecase.MissingBindingException
import com.example.simplygit.domain.usecase.MissingCredentialException
import com.example.simplygit.domain.usecase.PullRepoUseCase
import com.example.simplygit.domain.usecase.PushRepoUseCase
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
import java.util.Arrays
import javax.inject.Inject

/** User intents surfaced from [HomeScreen] (SPEC §4.5). */
sealed interface HomeIntent {
    data class SubmitCredential(val username: String, val email: String, val pat: CharArray) : HomeIntent
    data class PickVault(val uri: Uri) : HomeIntent
    data class SubmitRemote(val url: String) : HomeIntent
    data object DoClone : HomeIntent
    data object DoPull : HomeIntent
    data class DoCommit(val message: String) : HomeIntent
    data object DoPush : HomeIntent
    data object DismissError : HomeIntent
    data object Reset : HomeIntent
}

private const val CLIPBOARD_TAG = "simplygit-pat"
private const val CLIPBOARD_CLEAR_DELAY_MS = 60_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val credRepo: CredentialRepository,
    private val bindingRepo: RepoBindingRepository,
    private val bindVault: BindVaultUseCase,
    private val bindRemote: BindRemoteUseCase,
    private val cloneRepo: CloneRepoUseCase,
    private val pullRepo: PullRepoUseCase,
    private val commitLocal: CommitLocalUseCase,
    private val pushRepo: PushRepoUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _safState = MutableStateFlow<SafResolveUiState>(SafResolveUiState.None)
    val safState: StateFlow<SafResolveUiState> = _safState.asStateFlow()

    /**
     * SPEC §4.6 / L-6: the "已绑定 @username" badge must stay visible even while a Git
     * operation is Working / Error. We project the public view as a dedicated flow so
     * the UI no longer has to key off [HomeUiState.Bound].
     */
    val credentialView: StateFlow<CredentialPublicView?> =
        credRepo.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Exposed primarily to keep the single-intent pattern predictable; not used by Home yet.
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // SPEC §4.5 / M-4: project binding + credential into `Bound` whenever we are
        // *not* currently running an op. After a transient Error (which is keyboard-only
        // dismissed via [HomeIntent.DismissError]) we also allow the projection to
        // re-populate, so username/path stay fresh.
        combine(bindingRepo.observe(), credRepo.observe()) { binding, cred ->
            binding to cred
        }.onEach { (binding, cred) ->
            val prev = _uiState.value
            if (prev is HomeUiState.Working) return@onEach
            val keepLast = (prev as? HomeUiState.Bound)?.lastSuccess
            // Error states are preserved until explicitly dismissed (SPEC §5.2).
            if (prev is HomeUiState.Error) return@onEach
            _uiState.value = HomeUiState.Bound(
                treeUri = binding?.treeUri,
                localAbsPath = binding?.localAbsPath,
                remoteUrl = binding?.remoteUrl,
                username = cred?.username,
                lastSuccess = keepLast,
            )
        }.launchIn(viewModelScope)
    }

    fun onIntent(intent: HomeIntent) {
        // SPEC §4.5: single-operation serialization — drop intents while Working.
        if (_uiState.value is HomeUiState.Working && intent !is HomeIntent.DismissError) return
        when (intent) {
            is HomeIntent.SubmitCredential -> submitCredential(intent)
            is HomeIntent.PickVault -> pickVault(intent.uri)
            is HomeIntent.SubmitRemote -> viewModelScope.launch {
                runCatching { bindRemote(intent.url) }
            }
            HomeIntent.DoClone -> runOp(GitOp.CLONE) { cloneRepo() }
            HomeIntent.DoPull -> runOp(GitOp.PULL) { pullRepo() }
            is HomeIntent.DoCommit -> runOp(GitOp.COMMIT) { commitLocal(intent.message) }
            HomeIntent.DoPush -> runOp(GitOp.PUSH) { pushRepo() }
            HomeIntent.DismissError -> dismissError()
            HomeIntent.Reset -> _uiState.value = HomeUiState.Idle
        }
    }

    private fun submitCredential(intent: HomeIntent.SubmitCredential) {
        viewModelScope.launch {
            try {
                credRepo.save(intent.username.trim(), intent.email.trim(), intent.pat)
                // SPEC §4.6 / M-2: schedule clipboard clearing on the ViewModel scope so
                // it survives Composable teardown, Activity recreate etc.
                scheduleClipboardClear()
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
            val result = block()
            _uiState.value = when (result) {
                GitOpResult.Success -> snapshotBound(LastOpSummary(op, successDesc(op, null)))
                is GitOpResult.SuccessWithPayload ->
                    snapshotBound(LastOpSummary(op, successDesc(op, result.payload)))
                is GitOpResult.Failure -> HomeUiState.Error(op, toErrorKind(result.cause))
            }
        }
    }

    private fun dismissError() {
        val current = _uiState.value
        if (current is HomeUiState.Error) {
            // Fall back to a minimal Bound snapshot. The combine() in init will refresh
            // the fields as soon as the next emission arrives.
            viewModelScope.launch {
                _uiState.value = snapshotBound(last = null)
            }
        }
    }

    /** Collapses any throwable into a UI-safe [ErrorKind] (SPEC §6.3 / L-2). */
    private fun toErrorKind(cause: Throwable): ErrorKind = when (cause) {
        is MissingCredentialException -> ErrorKind.MissingCredential
        is MissingBindingException -> ErrorKind.MissingBinding
        is SafPermissionRevokedException -> ErrorKind.SafPermissionRevoked
        is SanitizedGitException -> ErrorKind.Sanitized(cause.message.orEmpty())
        else -> ErrorKind.Sanitized(cause.javaClass.simpleName)
    }

    private suspend fun snapshotBound(last: LastOpSummary?): HomeUiState.Bound {
        val binding = runCatching { bindingRepo.requireCurrent() }.getOrNull()
        val cred = credRepo.observe().firstOrNull()
        return HomeUiState.Bound(
            treeUri = binding?.treeUri,
            localAbsPath = binding?.localAbsPath,
            remoteUrl = binding?.remoteUrl,
            username = cred?.username,
            lastSuccess = last,
        )
    }

    /**
     * SPEC §5.2 / A8 / M-3: description is structured (no hardcoded English); UI layer
     * translates it via strings.xml.
     */
    private fun successDesc(op: GitOp, payload: Any?): String = when (op) {
        GitOp.PULL -> {
            val outcome = payload as? PullOutcome
            val count = outcome?.commitsPulled ?: 0
            val status = outcome?.mergeStatus
            // Use a stable machine-readable form: "<count>|<status-or-empty>". HomeScreen
            // parses this and routes to R.string.status_pulled_commits[_with_status].
            "$count|${status ?: ""}"
        }
        GitOp.CLONE, GitOp.COMMIT, GitOp.PUSH -> ""
    }

    /**
     * SPEC §4.6 / M-2: clears the clipboard 60s after a successful credential save,
     * provided the current clip was labeled by us.
     */
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

    /**
     * [ClipboardManager.clearPrimaryClip] is API 28+. On API 26–27 fall back to
     * overwriting the clipboard with an empty labeled clip.
     */
    private fun clearClipboardCompat(cm: ClipboardManager) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            cm.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_TAG, ""))
        }
    }
}

/** One-shot signals surfaced to [HomeScreen] (not currently consumed). */
sealed interface HomeEvent
