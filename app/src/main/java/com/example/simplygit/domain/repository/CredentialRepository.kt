package com.example.simplygit.domain.repository

import kotlinx.coroutines.flow.Flow

/** Projection of [com.example.simplygit.domain.model.Credential] that is safe to
 *  surface to UI (no PAT). SPEC §6.2.
 */
data class CredentialPublicView(val username: String, val email: String)

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
    suspend fun save(username: String, email: String, pat: CharArray)
    suspend fun loadPatOnce(): CharArray?
    suspend fun clear()
}
