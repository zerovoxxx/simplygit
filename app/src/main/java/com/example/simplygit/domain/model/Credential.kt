package com.example.simplygit.domain.model

import java.util.Arrays

/**
 * Credential object (SPEC §6.1).
 *
 * Deliberately **not** a `data class`: the default `toString` / `equals` / `hashCode`
 * would include the PAT field and leak secrets through logs or crash reports.
 *
 * PAT is held as a [CharArray] so callers can [wipe] it after use. This class does not
 * take a defensive copy of [patRef]; the caller is expected to pass ownership and call
 * [wipe] when the credential leaves scope.
 */
class Credential(
    val username: String,
    val email: String,
    private val patRef: CharArray,
) {

    /** Returns a fresh copy of the PAT. The caller **must** wipe it in a `finally` block. */
    fun patCopy(): CharArray = patRef.copyOf()

    /** Zeroes out the internally held PAT buffer. Idempotent. */
    fun wipe() {
        Arrays.fill(patRef, '\u0000')
    }

    override fun toString(): String = "Credential(username=$username, email=$email, pat=***)"

    override fun equals(other: Any?): Boolean =
        other is Credential && other.username == username && other.email == email

    override fun hashCode(): Int = username.hashCode() * 31 + email.hashCode()
}
