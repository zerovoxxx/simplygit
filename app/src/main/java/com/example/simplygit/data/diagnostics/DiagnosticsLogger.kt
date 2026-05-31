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
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only, process-local diagnostics appender (SPEC §4.4 / §4.9.1 Iteration 2).
 *
 * Writes one line per record into `<filesDir>/logs/diagnostics-YYYY-MM-DD.log`,
 * storing only the *sanitized* message together with the original exception type
 * name so an engineer can grep the file by [SanitizedGitException.originalType]
 * without ever seeing a PAT or URL-embedded credential.
 *
 * SPEC Iteration 2 (fix I-4): rolled from a single-file 256 KB design to
 * per-day rolling files (`diagnostics-YYYY-MM-DD.log`) so `ExportLogsUseCase`
 * can ship the last 7 days of logs. Each day's file is capped at
 * [DAY_MAX_BYTES]; files older than [RETENTION_DAYS] are deleted on every
 * append (cheap directory listing).
 *
 * Contract:
 *  - Never writes `Throwable.stackTrace` or raw `cause.message`.
 *  - Only accepts already-sanitized exceptions or plain short tags.
 *  - Size-capped per-day; no rolling-logger dependency.
 *  - IO errors are swallowed — the appender must never influence the user flow.
 */
@Singleton
class DiagnosticsLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
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

    /**
     * Returns the set of `diagnostics-YYYY-MM-DD.log` files touched in the last
     * [RETENTION_DAYS] days, sorted ascending by filename (i.e. oldest first).
     * Safe to call from any thread — IO is dispatched to [IoDispatcher].
     */
    suspend fun snapshotRecentLogFiles(): List<File> = withContext(io) {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) return@withContext emptyList()
        val cutoff = clock.millis() - Duration.ofDays(RETENTION_DAYS.toLong()).toMillis()
        dir.listFiles { f -> f.name.startsWith(LOG_PREFIX) && f.name.endsWith(LOG_SUFFIX) }
            ?.filter { it.lastModified() >= cutoff }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private suspend fun append(line: String) = withContext(io) {
        runCatching {
            val dir = File(context.filesDir, LOG_DIR).apply { if (!exists()) mkdirs() }
            migrateLegacyLogIfNeeded(dir)
            val today = DATE_FMT.format(Date(clock.millis()))
            val file = File(dir, "$LOG_PREFIX$today$LOG_SUFFIX")
            if (file.length() > DAY_MAX_BYTES) {
                // BUG-009 fix (bug_report_20260503_snao): keep the most recent
                // half instead of truncating the whole day. The previous
                // `file.writeText("")` wiped every pre-crash log line right
                // when an engineer needed them most.
                val tailBytes = DAY_MAX_BYTES.toInt() / 2
                val raw = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
                val keep = if (raw.size > tailBytes) {
                    raw.copyOfRange(raw.size - tailBytes, raw.size)
                } else raw
                file.writeBytes(keep)
                file.appendText("\n--- LOG ROTATED (tail-kept) ---\n")
            }
            FileWriter(file, /* append = */ true).use { w ->
                w.append(TS_FMT.format(Date(clock.millis())))
                w.append(' ')
                w.append(line)
                w.append('\n')
            }
            pruneOldLogs(dir)
        }
    }

    /**
     * One-shot migration: if a legacy `diagnostics.log` exists (Iteration 1),
     * rename it to `diagnostics-<today>.log` so it participates in the rolling
     * / retention logic. Safe to call repeatedly; becomes a no-op once the
     * legacy file is gone.
     */
    private fun migrateLegacyLogIfNeeded(dir: File) {
        val legacy = File(dir, LEGACY_LOG_FILE)
        if (!legacy.exists()) return
        val today = DATE_FMT.format(Date(clock.millis()))
        val target = File(dir, "$LOG_PREFIX$today$LOG_SUFFIX")
        if (!target.exists()) {
            runCatching { legacy.renameTo(target) }
        } else {
            // Same-day collision: append legacy content, then delete.
            runCatching {
                target.appendText(legacy.readText())
                legacy.delete()
            }
        }
    }

    private fun pruneOldLogs(dir: File) {
        val cutoff = clock.millis() - Duration.ofDays(RETENTION_DAYS.toLong()).toMillis()
        dir.listFiles { f -> f.name.startsWith(LOG_PREFIX) && f.name.endsWith(LOG_SUFFIX) }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val LOG_DIR = "logs"
        const val LEGACY_LOG_FILE = "diagnostics.log"
        const val LOG_PREFIX = "diagnostics-"
        const val LOG_SUFFIX = ".log"
        const val DAY_MAX_BYTES: Long = 64L * 1024L
        const val RETENTION_DAYS: Int = 7
        val DATE_FMT: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val TS_FMT: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
