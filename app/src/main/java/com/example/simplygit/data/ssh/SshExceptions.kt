package com.example.simplygit.data.ssh

/**
 * Non-OpenSSH private key format (SPEC §4.4.1, R8 white-list).
 */
class SshKeyFormatException(cause: Throwable? = null) :
    RuntimeException("unsupported SSH private key format (expected OpenSSH)", cause)

/**
 * First-time connection to an unknown host — UI prompts the user to confirm
 * the fingerprint (SPEC §4.4.2 TOFU).
 */
class SshHostKeyFirstConnectException(val host: String, val fingerprint: String) :
    RuntimeException("TOFU first connect to $host fingerprint=$fingerprint")

/**
 * Known-hosts fingerprint mismatch (SPEC §4.4.2). Sanitised into
 * [com.example.simplygit.data.git.SyncErrorKind.Auth] by
 * [com.example.simplygit.data.git.JGitExceptionSanitizer].
 */
class SshHostKeyChangedException(
    val host: String,
    val expectedFingerprint: String,
    val actualFingerprint: String,
) : RuntimeException(
    "host key changed for $host expected=$expectedFingerprint actual=$actualFingerprint",
)
