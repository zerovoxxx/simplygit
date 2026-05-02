package com.example.simplygit.data.git

/**
 * Classification tag attached to every [SanitizedGitException] (SPEC §4.2
 * Iteration 2 / fix I-1).
 *
 * `RunSyncUseCase` dispatches on `SanitizedGitException.kind` using a `when`
 * block; we deliberately avoid subclassing the exception (e.g. `AuthException`
 * / `NetworkException`) to keep the sealed type surface small and to keep the
 * catch-chain in Iteration 1 untouched (message / originalType semantics unchanged).
 */
sealed interface SyncErrorKind {
    /** HTTP 401 / 403 / "not authorized" — PAT expired or revoked. */
    data object Auth : SyncErrorKind

    /** UnknownHost / NoRouteToHost / ConnectException / SocketException / timeout. */
    data object Network : SyncErrorKind

    /** Everything else: JGit internal errors, OOM, local IO, unexpected state. */
    data object Unknown : SyncErrorKind
}
