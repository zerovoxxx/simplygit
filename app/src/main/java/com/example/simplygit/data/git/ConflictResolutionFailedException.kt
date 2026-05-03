package com.example.simplygit.data.git

/**
 * Raised by [ConflictRepositoryImpl] when one or more paths cannot be
 * resolved (SPEC §6.2 Iteration 3, sanitizer whitelist).
 *
 * Classified as [SyncErrorKind.Unknown] by [JGitExceptionSanitizer] —
 * callers dispatch on the `paths` field for UI messaging.
 */
class ConflictResolutionFailedException(
    val paths: List<String>,
    cause: Throwable,
) : RuntimeException("conflict resolution failed for ${paths.size} path(s)", cause)
