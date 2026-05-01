package com.example.simplygit.domain.model

/**
 * Result of a Git operation (SPEC §6.1).
 *
 * Failure.cause is always a sanitized exception (SPEC §4.4 JGitExceptionSanitizer).
 */
sealed interface GitOpResult {
    data object Success : GitOpResult
    data class SuccessWithPayload(val op: GitOp, val payload: Any) : GitOpResult
    data class Failure(val op: GitOp, val cause: Throwable) : GitOpResult
}
