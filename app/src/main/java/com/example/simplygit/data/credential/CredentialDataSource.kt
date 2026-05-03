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
    suspend fun saveIdentity(username: String, email: String)
    fun observe(): Flow<CredentialPublicView?>

    /**
     * One-shot identity read. Used by the background sync / manual UseCases
     * that need `username` / `email` without subscribing to change events.
     * Distinct from [observe] so callers do not pay the cost of registering
     * and immediately tearing down a `SharedPreferences` change listener
     * (BUG-008 — bug_report_20260503_snao).
     */
    suspend fun snapshotIdentity(): CredentialPublicView?
    suspend fun loadPatOnce(): CharArray?
    suspend fun clear()
}
