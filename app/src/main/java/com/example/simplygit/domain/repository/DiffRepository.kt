package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.DiffOutcome
import com.example.simplygit.domain.model.DiffSource

/** Diff surface exposed to the Domain layer (SPEC §4.2.1 Iteration 3). */
interface DiffRepository {
    /**
     * Diff a single path according to [source]. Implementation lives in
     * the Data layer and keeps the JGit `DiffFormatter` inside a
     * `Git.open(dir).use{}` scope — callers never hold a JGit reference
     * (SPEC P6).
     */
    suspend fun diff(repoId: Long, path: String, source: DiffSource): DiffOutcome
}
