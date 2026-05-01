package com.example.simplygit.data.diagnostics

import android.content.Context
import com.example.simplygit.data.git.SanitizedGitException
import com.example.simplygit.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only, process-local diagnostics appender (SPEC §4.4 / A10).
 *
 * Writes one line per record into `<filesDir>/logs/diagnostics.log`, storing only the
 * *sanitized* message together with the original exception type name so an engineer can
 * grep the file by [SanitizedGitException.originalType] without ever seeing a PAT or
 * URL-embedded credential.
 *
 * Contract:
 *  - Never writes `Throwable.stackTrace` or raw `cause.message`.
 *  - Only accepts already-sanitized exceptions or plain short tags.
 *  - Size-capped at [MAX_BYTES] via a one-shot truncation when the threshold is exceeded
 *    (we deliberately keep it dead simple for Phase 1 — no rolling logger dependency).
 */
@Singleton
class DiagnosticsLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun logGitOpFailure(op: String, cause: Throwable) {
        val sanitized = cause as? SanitizedGitException
        val originalType = sanitized?.originalType ?: cause.javaClass.simpleName
        val message = sanitized?.message ?: cause.javaClass.simpleName
        append("GIT_OP_FAILURE op=$op type=$originalType msg=$message")
    }

    suspend fun logInfo(tag: String, message: String) {
        append("INFO tag=$tag msg=$message")
    }

    private suspend fun append(line: String) = withContext(io) {
        runCatching {
            val dir = File(context.filesDir, LOG_DIR).apply { if (!exists()) mkdirs() }
            val file = File(dir, LOG_FILE)
            if (file.length() > MAX_BYTES) file.writeText("")
            FileWriter(file, /* append = */ true).use { w ->
                w.append(TS_FMT.format(Date()))
                w.append(' ')
                w.append(line)
                w.append('\n')
            }
        }
        // Swallow IO errors intentionally: the appender must never influence the user flow.
    }

    private companion object {
        const val LOG_DIR = "logs"
        const val LOG_FILE = "diagnostics.log"
        const val MAX_BYTES: Long = 256L * 1024L
        val TS_FMT: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
