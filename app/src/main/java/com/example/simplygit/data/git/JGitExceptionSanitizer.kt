package com.example.simplygit.data.git

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
     * (SPEC §4.2 / §4.5 Iteration 2 / fix I-1).
     *
     *  - [SyncErrorKind.Auth] — JGit [TransportException] whose message contains
     *    "401" / "403" / "not authorized" (case-insensitive). JGit 6.10 surfaces
     *    GitHub's rejection with exactly one of these markers.
     *  - [SyncErrorKind.Network] — standard JDK network exceptions
     *    (UnknownHost / NoRouteToHost / Connect / Socket / SocketTimeout).
     *  - [SyncErrorKind.Unknown] — anything else, including JGit's own
     *    IOExceptions, OOM, cancelled coroutines.
     */
    internal fun classifyKind(t: Throwable): SyncErrorKind {
        var current: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_DEPTH && seen.add(current)) {
            when (current) {
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

/** Maps any failure inside [Result] through [JGitExceptionSanitizer]. */
internal fun <T> Result<T>.mapException(sanitizer: JGitExceptionSanitizer): Result<T> =
    fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(sanitizer.sanitize(it)) },
    )
