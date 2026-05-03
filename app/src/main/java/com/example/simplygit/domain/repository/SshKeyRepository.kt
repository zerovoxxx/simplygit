package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.DeleteSshKeyOutcome
import com.example.simplygit.domain.model.SshKeyIndexEntry
import com.example.simplygit.domain.model.SshKeyPair
import kotlinx.coroutines.flow.Flow

/**
 * SSH key vault (SPEC §4.4.1 Iteration 3 / P0-4).
 *
 * Independent of [CredentialRepository] — PAT and SSH keys are stored in
 * disjoint encrypted stores so a leak on one side can never expose the
 * other.
 */
interface SshKeyRepository {

    /** Generate a new ed25519 key pair. [passphrase] is optional; caller MUST wipe. */
    suspend fun generate(passphrase: CharArray?): SshKeyPair

    /** Import an OpenSSH-format private key the user pasted or picked from SAF. */
    suspend fun import(privateKeyOpenssh: CharArray, passphrase: CharArray?): SshKeyPair

    /** Returns the public key text so the user can paste it into GitHub / GitLab. */
    suspend fun exportPublic(keyId: String): String

    /** Observe the (non-sensitive) index for UI display. */
    fun observeIndex(): Flow<List<SshKeyIndexEntry>>

    /** Delete a key unless a repository binding still points at it. */
    suspend fun delete(keyId: String): DeleteSshKeyOutcome

    /**
     * Persist a host fingerprint into the TOFU `known_hosts` file (SPEC §4.4.2).
     * Called by the UI after the user confirms the first-connect dialog; the
     * next clone/pull/push will see the host as already trusted.
     */
    suspend fun acceptHostKey(host: String, fingerprint: String)

    /** Wipe every TOFU record. Exposed as "reset known hosts" in the UI. */
    suspend fun resetKnownHosts()
}
