package com.example.simplygit.data.saf

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.example.simplygit.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Three-state outcome of resolving a SAF tree Uri to a JGit-compatible path (SPEC §4.3). */
sealed interface ResolveResult {
    data class Ok(val absPath: String) : ResolveResult

    /** docId prefix is not `primary:` (e.g. SD card, cloud provider). */
    data object NotPrimary : ResolveResult

    /** Path resolved but `canRead()` / `canWrite()` failed. */
    data object NotReadable : ResolveResult
}

/**
 * Resolves `content://com.android.externalstorage.documents/tree/primary:Documents/Xxx`
 * into an absolute path under `/storage/emulated/0/...` and probes readability
 * (SPEC §4.3 / total-plan §4.1).
 *
 * Intentionally narrow: Iteration 1 validates the cheapest path (direct absolute path).
 * Any non-primary / non-readable result is reported back to UI verbatim so Phase 2 can
 * decide whether to invest in a custom JGit FS adapter (N7, retro D1).
 */
@Singleton
class SafPathResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun tryResolveAbsolutePath(treeUri: Uri): ResolveResult = withContext(io) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val split = docId.split(":", limit = 2)
        if (split.size != 2 || split[0] != "primary") return@withContext ResolveResult.NotPrimary

        val relative = split[1]
        val base = Environment.getExternalStorageDirectory()
        val absPath = if (relative.isEmpty()) base.absolutePath else File(base, relative).absolutePath
        val dir = File(absPath)
        if (!dir.exists() || !dir.canRead() || !dir.canWrite()) {
            return@withContext ResolveResult.NotReadable
        }
        ResolveResult.Ok(absPath)
    }

    /**
     * Returns true iff the app still holds a persisted RW permission for [treeUri]
     * (SPEC §4.3 alignment with total-plan §4.1: detect revoked permissions).
     */
    fun hasPersistedPermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == treeUri && perm.isReadPermission && perm.isWritePermission
        }
}
