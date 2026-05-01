package com.example.simplygit.data.credential

import com.example.simplygit.domain.repository.CredentialPublicView
import kotlinx.coroutines.flow.Flow

/**
 * Internal DataSource seam for credential storage (SPEC §4.2).
 *
 * The only permitted implementation is [EncryptedCredentialDataSource] (backed by
 * `EncryptedSharedPreferences`). No other module may read `github_pat` directly.
 */
interface CredentialDataSource {
    suspend fun save(username: String, email: String, pat: CharArray)
    fun observe(): Flow<CredentialPublicView?>
    suspend fun loadPatOnce(): CharArray?
    suspend fun clear()
}
