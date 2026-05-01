package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.RepoBinding

/**
 * Git operations exposed to the Domain layer (SPEC §6.2).
 *
 * PAT is always passed as [CharArray] at method boundaries rather than wrapped in a
 * Credential object — this keeps its lifecycle visible on the call chain so callers
 * never forget the `try/finally { Arrays.fill(pat, '\u0000') }` pattern.
 */
interface GitRepository {
    suspend fun clone(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun pull(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun commitAll(
        binding: RepoBinding,
        message: String,
        authorName: String,
        authorEmail: String,
    ): GitOpResult
    suspend fun push(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
}
