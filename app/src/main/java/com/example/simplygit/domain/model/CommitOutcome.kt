package com.example.simplygit.domain.model

/**
 * Result of [com.example.simplygit.domain.repository.GitRepository.commitAllIfDirty]
 * (SPEC §6.1 Iteration 2).
 *
 * `null` return → clean working tree, no commit was created.
 */
data class CommitOutcome(
    val objectId: String,
    val filesChanged: Int,
)
