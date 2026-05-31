package com.example.simplygit.domain.repository

import kotlinx.coroutines.flow.Flow

/** Projection of [com.example.simplygit.domain.model.Credential] that is safe to
 *  surface to UI (no PAT). SPEC §6.2.
 */
data class CredentialPublicView(val username: String, val email: String)

/**
 * Identity snapshot used by background runs (SPEC §6.2 Iteration 2 / fix I-2).
 *
 * Same shape as [CredentialPublicView], but returned through a one-shot
 * suspend call so workers do not need a Flow subscription.
 */
data class CredentialIdentity(val username: String, val email: String)

/**
 * Single entry point for credential access (SPEC §4.2).
 *
 * Contracts:
 * - [observe] never emits the PAT.
 * - [loadPatOnce] returns a fresh [CharArray] copy; callers **must** wipe it in a
 *   `try/finally` block.
 * - [save] takes ownership of the incoming `pat` buffer and wipes it before returning.
 */
interface CredentialRepository {
    fun observe(): Flow<CredentialPublicView?>

    /**
     * One-shot identity read (SPEC §6.2 Iteration 2 / fix I-2): returns `null`
     * when credentials have not been saved yet. Does **not** expose the PAT;
     * PAT retrieval still goes through [loadPatOnce].
     */
    suspend fun snapshotIdentity(): CredentialIdentity?

    suspend fun save(username: String, email: String, pat: CharArray)
    suspend fun saveIdentity(username: String, email: String)
    suspend fun loadPatOnce(): CharArray?
    suspend fun clear()
}
