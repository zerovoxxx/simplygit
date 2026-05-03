package com.example.simplygit.data.git

import com.example.simplygit.data.ssh.SshHostKeyChangedException
import com.example.simplygit.data.ssh.SshHostKeyFirstConnectException
import com.example.simplygit.data.ssh.SshKeyFormatException
import org.eclipse.jgit.errors.TransportException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strips credentials-like fragments from Throwable messages before they reach UI or
 * logs (SPEC §4.4 / §5.1 alignment):
 *  - basic-auth in URLs (`https://user:token@host/...`)
 *  - `token=...` query parameters
 *  - `Authorization: ...` headers
 *  - GitHub PAT prefixes (`ghp_`, `ghs_`, `gho_`, `ghu_`, `github_pat_...`)
 *
 * The sanitizer flattens the whole cause chain: every layer's message is cleaned and
 * concatenated so downstream consumers never receive the original Throwable (whose
 * `cause` could still carry sensitive text).
 *
 * SPEC Iteration 2 (fix I-1): every sanitized exception also carries a
 * [SyncErrorKind] tag so `RunSyncUseCase` can dispatch on auth / network / unknown
 * branches without introducing dedicated exception subclasses.
 */
@Singleton
class JGitExceptionSanitizer @Inject constructor() {

    fun sanitize(t: Throwable): SanitizedGitException {
        val chain = buildString {
            var current: Throwable? = t
            var depth = 0
            val seen = HashSet<Throwable>()
            while (current != null && depth < MAX_DEPTH && seen.add(current)) {
                val piece = cleanMessage(current.message) ?: current.javaClass.simpleName
                if (depth > 0) append(" | caused-by: ")
                append(piece)
                current = current.cause
                depth++
            }
        }
        return SanitizedGitException(chain, t.javaClass.simpleName, classifyKind(t))
    }

    /**
     * Walks the cause chain and returns the first matching classification
     * (SPEC §4.2 / §4.5 Iteration 2 / fix I-1, §6.2 Iteration 3 white-list).
     *
     *  - [SyncErrorKind.Auth] — JGit [TransportException] whose message contains
     *    "401" / "403" / "not authorized" (case-insensitive). JGit 6.10 surfaces
     *    GitHub's rejection with exactly one of these markers. Also classifies
     *    [SshHostKeyChangedException] as Auth so the SSH host-key mismatch
     *    drops the repo into `PAUSED_AUTH` via `RunSyncUseCase` (SPEC §4.4.2).
     *  - [SyncErrorKind.Network] — standard JDK network exceptions
     *    (UnknownHost / NoRouteToHost / Connect / Socket / SocketTimeout).
     *  - [SyncErrorKind.Unknown] — Iteration 3 white-listed failures that do
     *    not map to any finer-grained category:
     *    [ConflictResolutionFailedException], [SshKeyFormatException]. Also
     *    used for anything else (JGit's own IOExceptions, OOM, cancelled
     *    coroutines).
     *
     * Note: [SshHostKeyFirstConnectException] is **not** classified here —
     * callers (e.g. `JGitDataSource`) must catch it before the sanitizer
     * runs so the UI can surface a TOFU confirmation dialog (SPEC §6.2).
     */
    internal fun classifyKind(t: Throwable): SyncErrorKind {
        var current: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_DEPTH && seen.add(current)) {
            when (current) {
                is SshHostKeyChangedException -> return SyncErrorKind.Auth
                is ConflictResolutionFailedException,
                is SshKeyFormatException,
                -> return SyncErrorKind.Unknown
                is TransportException -> {
                    val msg = current.message.orEmpty()
                    if (msg.contains("not authorized", ignoreCase = true) ||
                        msg.contains("401") ||
                        msg.contains("403")
                    ) {
                        return SyncErrorKind.Auth
                    }
                }
                is UnknownHostException,
                is NoRouteToHostException,
                is ConnectException,
                is SocketException,
                is SocketTimeoutException -> return SyncErrorKind.Network
            }
            current = current.cause
            depth++
        }
        return SyncErrorKind.Unknown
    }

    /** Public helper so non-throwable text (e.g. raw messages) can go through the same filter. */
    fun cleanMessage(raw: String?): String? = raw
        ?.let { REGEX_BASIC_AUTH.replace(it, "https://[redacted]@") }
        ?.let { REGEX_TOKEN_QP.replace(it, "token=[redacted]") }
        ?.let { REGEX_AUTH_HEADER.replace(it, "Authorization: [redacted]") }
        ?.let { REGEX_CLASSIC_PAT.replace(it, "[redacted-pat]") }
        ?.let { REGEX_FINE_PAT.replace(it, "[redacted-pat]") }

    private companion object {
        const val MAX_DEPTH = 8
        val REGEX_BASIC_AUTH = Regex("""https?://[^\s@]+@""")
        val REGEX_TOKEN_QP = Regex("""token\s*=\s*[A-Za-z0-9_\-]+""")
        val REGEX_AUTH_HEADER = Regex("""Authorization:\s*\S+""")
        val REGEX_CLASSIC_PAT = Regex("""gh[pous]_[A-Za-z0-9]{20,}""")
        val REGEX_FINE_PAT = Regex("""github_pat_[A-Za-z0-9_]{20,}""")
    }
}

/**
 * Maps any failure inside [Result] through [JGitExceptionSanitizer].
 *
 * Exception: [SshHostKeyFirstConnectException] (or a cause chain containing
 * one) bypasses sanitization so the UI can catch the exact type and open a
 * TOFU confirmation dialog (SPEC §6.2 — the single white-listed bypass).
 */
internal fun <T> Result<T>.mapException(sanitizer: JGitExceptionSanitizer): Result<T> =
    fold(
        onSuccess = { Result.success(it) },
        onFailure = { t ->
            val tofu = findCause(t, SshHostKeyFirstConnectException::class.java)
            if (tofu != null) {
                Result.failure(tofu)
            } else {
                Result.failure(sanitizer.sanitize(t))
            }
        },
    )

/**
 * Walks the cause chain up to [JGitExceptionSanitizer.MAX_DEPTH] entries
 * looking for a throwable assignable to [type].
 */
private fun <T : Throwable> findCause(t: Throwable, type: Class<T>): T? {
    var current: Throwable? = t
    var depth = 0
    val seen = HashSet<Throwable>()
    while (current != null && depth < FIND_CAUSE_MAX_DEPTH && seen.add(current)) {
        if (type.isInstance(current)) {
            @Suppress("UNCHECKED_CAST")
            return current as T
        }
        current = current.cause
        depth++
    }
    return null
}

private const val FIND_CAUSE_MAX_DEPTH = 8
