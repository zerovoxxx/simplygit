package com.example.simplygit.data.git

import com.example.simplygit.domain.model.GitOp
import com.example.simplygit.domain.model.GitOpResult
import com.example.simplygit.domain.model.RepoBinding
import com.example.simplygit.domain.repository.GitRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitRepositoryImpl @Inject constructor(
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
}
