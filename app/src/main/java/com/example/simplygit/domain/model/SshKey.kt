package com.example.simplygit.domain.model

import java.util.Arrays

/**
 * Short-lived in-memory SSH key pair (SPEC §4.4.1 Iteration 3 / R3).
 *
 * Intentionally NOT a `data class` — the auto-generated `toString()` would
 * print the private key. Public and fingerprint are safe to log; the
 * private key only escapes via [privateKeyCopy] and MUST be wiped by the
 * caller.
 */
class SshKeyPair(
    val keyId: String,
    val publicKeyOpenssh: String,
    val fingerprintSha256: String,
    private val privateKeyRef: CharArray,
) {
    fun privateKeyCopy(): CharArray = privateKeyRef.copyOf()

    fun wipe() {
        Arrays.fill(privateKeyRef, '\u0000')
    }

    override fun toString(): String =
        "SshKeyPair(keyId=$keyId, fingerprint=$fingerprintSha256, private=***)"
}

/** Safe-to-display index row (SPEC §4.4.1). */
data class SshKeyIndexEntry(
    val keyId: String,
    val fingerprintSha256: String,
    val createdAt: Long,
)

sealed interface DeleteSshKeyOutcome {
    data object Deleted : DeleteSshKeyOutcome
    data class InUse(val byRepoIds: List<Long>) : DeleteSshKeyOutcome
}
