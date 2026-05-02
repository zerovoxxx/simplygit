package com.example.simplygit.data.git

/**
 * Every exception surfaced to UI / logs goes through [JGitExceptionSanitizer] and is
 * re-wrapped as this type (SPEC §4.4). The original class name is preserved in
 * [originalType] for local debugging.
 *
 * SPEC Iteration 2 (fix I-1): [kind] carries the coarse classification used by
 * `RunSyncUseCase` to decide between PAUSED_AUTH / network-backoff / BROKEN
 * transitions. The constructor keeps [kind] optional (defaulting to
 * [SyncErrorKind.Unknown]) so the Iteration 1 call chain — which consumed
 * `message` / `originalType` only — compiles without modification.
 */
class SanitizedGitException(
    message: String,
    val originalType: String,
    val kind: SyncErrorKind = SyncErrorKind.Unknown,
) : RuntimeException(message)
