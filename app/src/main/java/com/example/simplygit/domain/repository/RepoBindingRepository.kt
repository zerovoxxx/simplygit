package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.RepoBinding
import kotlinx.coroutines.flow.Flow

/** SPEC §6.2. */
interface RepoBindingRepository {
    fun observe(): Flow<RepoBinding?>

    /** @throws IllegalStateException when no binding has been established yet. */
    suspend fun requireCurrent(): RepoBinding

    suspend fun saveVault(treeUri: String, absPath: String)
    suspend fun saveRemote(url: String)
    suspend fun clear()
}
