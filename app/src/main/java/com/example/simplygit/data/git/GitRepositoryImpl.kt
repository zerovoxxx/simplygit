package com.example.simplygit.data.git

import com.example.simplygit.domain.model.CommitOutcome
import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.PullOutcomeClassified
import com.example.simplygit.domain.model.RepoBinding
import com.example.simplygit.domain.repository.GitRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GitRepositoryImpl @Inject constructor(
    private val jgit: JGitDataSource,
) : GitRepository {

    override suspend fun clone(binding: RepoBinding, username: String, pat: CharArray): GitOpResult =
        jgit.clone(binding.remoteUrl, File(binding.localAbsPath), username, pat)
            .fold(
                onSuccess = { GitOpResult.Success },
                onFailure = { GitOpResult.Failure(GitOp.CLONE, it) },
            )

    override suspend fun pull(binding: RepoBinding, username: String, pat: CharArray): GitOpResult =
        jgit.pull(File(binding.localAbsPath), username, pat)
            .fold(
                onSuccess = { GitOpResult.SuccessWithPayload(GitOp.PULL, it) },
                onFailure = { GitOpResult.Failure(GitOp.PULL, it) },
            )

    override suspend fun commitAll(
        binding: RepoBinding,
        message: String,
        authorName: String,
        authorEmail: String,
    ): GitOpResult =
        jgit.commitAll(File(binding.localAbsPath), message, authorName, authorEmail)
            .fold(
                onSuccess = { GitOpResult.Success },
                onFailure = { GitOpResult.Failure(GitOp.COMMIT, it) },
            )

    override suspend fun push(binding: RepoBinding, username: String, pat: CharArray): GitOpResult =
        jgit.push(File(binding.localAbsPath), username, pat)
            .fold(
                onSuccess = { GitOpResult.Success },
                onFailure = { GitOpResult.Failure(GitOp.PUSH, it) },
            )

    /**
     * SPEC §6.2.1 Iteration 2 (fix I-2 / I-3): throws [SanitizedGitException]
     * (with `kind` set by [JGitExceptionSanitizer]) on failure. The classifier
     * runs inside the JGit `Git.open(dir).use{}` scope held by
     * [JGitDataSource.pullAndClassify]; callers only see the plain DTO.
     */
    override suspend fun pullAndClassify(
        binding: RepoBinding,
        username: String,
        pat: CharArray,
    ): PullOutcomeClassified =
        jgit.pullAndClassify(File(binding.localAbsPath), username, pat)
            .getOrThrow()

    override suspend fun commitAllIfDirty(
        binding: RepoBinding,
        message: String,
        authorName: String,
        authorEmail: String,
    ): CommitOutcome? =
        jgit.commitAllIfDirty(File(binding.localAbsPath), message, authorName, authorEmail)
            .getOrThrow()
}
