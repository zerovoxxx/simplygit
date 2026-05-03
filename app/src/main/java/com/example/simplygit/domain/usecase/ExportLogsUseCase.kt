package com.example.simplygit.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.simplygit.data.diagnostics.DiagnosticsLogger
import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.SyncLogModel
import com.example.simplygit.domain.repository.SyncLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Packages the audit log (most recent 500 rows) together with the rolling
 * diagnostics files and exposes the bundle through the app's `FileProvider`
 * (SPEC §4.7 / §4.9.1 / G9 Iteration 2).
 *
 * The file is written to `<filesDir>/exports/simplygit-<ts>.zip` so the
 * FileProvider xml (`@xml/file_paths`) can grant READ permission via
 * `ACTION_SEND`. SPEC §5.2: data never leaves the device automatically;
 * sharing is explicitly initiated by the user.
 */
class ExportLogsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val syncLogRepo: SyncLogRepository,
    private val diagnostics: DiagnosticsLogger,
    private val clock: Clock,
) {
    data class ExportArtifact(val uri: Uri, val displayPath: String)

    suspend operator fun invoke(): ExportArtifact = withContext(io) {
        val logs = syncLogRepo.loadRecentForExport(MAX_EXPORT_ROWS)
        val diagFiles = diagnostics.snapshotRecentLogFiles()

        val dir = File(context.filesDir, EXPORTS_DIR).apply { if (!exists()) mkdirs() }
        val ts = TS_FMT.format(Date(clock.millis()))
        val zipFile = File(dir, "simplygit-$ts.zip")

        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("sync_log.json"))
            zip.write(logs.toJsonBytes())
            zip.closeEntry()

            diagFiles.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        // BUG-006 fix (bug_report_20260503_p16x): prune historical exports so
        // the directory does not accumulate forever. Each zip still contains
        // already-sanitized content but is business audit data nonetheless —
        // keeping it around widens the forensic / mis-backup attack surface
        // without benefit. Kept in lock-step with DiagnosticsLogger's 7-day
        // retention so a full export bundle always fits the same window.
        pruneOldExports(dir, keepFile = zipFile)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile,
        )
        ExportArtifact(uri = uri, displayPath = zipFile.absolutePath)
    }

    /**
     * Removes `simplygit-*.zip` files older than [EXPORT_RETENTION_DAYS]
     * (measured by `File.lastModified`). The freshly written [keepFile] is
     * explicitly excluded so a badly-skewed clock cannot delete the artefact
     * we are about to return.
     */
    private fun pruneOldExports(dir: File, keepFile: File) {
        val cutoff = clock.millis() - Duration.ofDays(EXPORT_RETENTION_DAYS.toLong()).toMillis()
        dir.listFiles { f ->
            f.name.startsWith("simplygit-") && f.name.endsWith(".zip")
        }
            ?.filter { it.absolutePath != keepFile.absolutePath && it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun List<SyncLogModel>.toJsonBytes(): ByteArray {
        val arr = JSONArray()
        for (row in this) {
            val obj = JSONObject()
            obj.put("id", row.id)
            obj.put("repoId", row.repoId)
            obj.put("startedAt", row.startedAt.toString())
            obj.put("endedAt", row.endedAt?.toString())
            obj.put("trigger", row.trigger.name)
            obj.put("result", row.result?.name)
            obj.put("commitsPulled", row.commitsPulled)
            obj.put("commitsPushed", row.commitsPushed)
            obj.put("filesChanged", row.filesChanged)
            obj.put("conflictClass", row.conflictClass?.name)
            obj.put("errorMsg", row.errorMsg)
            obj.put("errorType", row.errorType)
            arr.put(obj)
        }
        return arr.toString(JSON_INDENT).toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val EXPORTS_DIR = "exports"
        const val MAX_EXPORT_ROWS = 500
        const val EXPORT_RETENTION_DAYS = 7
        const val JSON_INDENT = 2
        val TS_FMT: SimpleDateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
