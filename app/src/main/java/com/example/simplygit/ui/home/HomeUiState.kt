package com.example.simplygit.ui.home

import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncState

/**
 * UI state for [HomeScreen] (SPEC §4.5 Iteration 1, §4.7 Iteration 2).
 *
 * Intentionally excludes `pat` and `email` — Compose may snapshot state, so any
 * secret placed here would risk leaking through `savedInstanceState` /
 * recent-task thumbnails.
 *
 * SPEC §4.7 Iteration 2: extended with [syncState] / [intervalMinutes] /
 * [pendingAlertCount] / [notificationGranted] so the SyncStateBanner and
 * Home TopBar badge can render without hitting Room directly from the UI.
 */
sealed interface HomeUiState {
    data object Idle : HomeUiState

    data class Bound(
        val treeUri: String?,
        val localAbsPath: String?,
        val remoteUrl: String?,
        val username: String?,
        val lastSuccess: LastOpSummary? = null,
        val syncState: SyncState = SyncState.IDLE,
        val intervalMinutes: Int = SyncPolicyModel.DEFAULT.intervalMinutes,
        val pendingAlertCount: Int = 0,
        val notificationGranted: Boolean = true,
        /**
         * True when the DataStore → Room migration has failed three times in
         * a row (SPEC §4.6 Iteration 2 / fix CR P2-01). Surfaces a re-bind
         * prompt banner instead of the regular SyncStateBanner.
         */
        val migrationDisabled: Boolean = false,
    ) : HomeUiState

    data class Working(val op: GitOp, val startedAt: Long) : HomeUiState

    /** [messageKind] has already been sanitized by
     *  [com.example.simplygit.data.git.JGitExceptionSanitizer] (when originating from JGit)
     *  or converted from a well-known domain error. */
    data class Error(val op: GitOp, val messageKind: ErrorKind) : HomeUiState
}

/** Marker for error text resolution at the UI layer (strings.xml, SPEC §4.6). */
sealed interface ErrorKind {
    /** Already sanitized free-form message from SanitizedGitException / unknown throwable. */
    data class Sanitized(val message: String) : ErrorKind
    data object MissingCredential : ErrorKind
    data object MissingBinding : ErrorKind
    data object SafPermissionRevoked : ErrorKind
}

data class LastOpSummary(val op: GitOp, val description: String)

/** Outcomes of resolving a SAF picker result (surfaced to UI for red-text hints). */
sealed interface SafResolveUiState {
    data object None : SafResolveUiState
    data class Bound(val absPath: String) : SafResolveUiState
    data object NotPrimary : SafResolveUiState
    data object NotReadable : SafResolveUiState
}
