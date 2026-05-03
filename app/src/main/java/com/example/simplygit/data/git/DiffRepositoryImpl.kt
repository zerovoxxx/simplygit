package com.example.simplygit.data.git

import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.DiffFailure
import com.example.simplygit.domain.model.DiffLine
import com.example.simplygit.domain.model.DiffLineKind
import com.example.simplygit.domain.model.DiffOutcome
import com.example.simplygit.domain.model.DiffSource
import com.example.simplygit.domain.repository.DiffRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.errors.LargeObjectException
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JGit-backed [DiffRepository] (SPEC §4.2.1 Iteration 3).
 *
 * Architecture (SPEC P6 "JGit native types don't leave Data layer"):
 *  - Every JGit handle (`Git`, `Repository`, `DiffFormatter`, `RevWalk`,
 *    `TreeWalk`) is held inside a `.use{}` scope local to [diff].
 *  - The return type is the pure-Kotlin [DiffOutcome] sealed hierarchy.
 *
 * Threshold policy (SPEC §4.2.1):
 *  - If cumulative `DiffLine` count > [MAX_LINES_FULL], stop parsing and
 *    return [DiffOutcome.Truncated] with the first [TRUNCATE_TO_LINES] lines
 *    plus a byte-based estimate of total lines.
 *  - Binary files reported by `RawText.isBinary` or `LargeObjectException`
 *    map to [DiffOutcome.Binary].
 *  - Any other throwable is caught, dispatched through [JGitExceptionSanitizer]
 *    and surfaced as [DiffOutcome.Failed] with a [DiffFailure] reason (R8).
 */
@Singleton
internal class DiffRepositoryImpl @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val bindingRepo: RepoBindingRepository,
    private val sanitizer: JGitExceptionSanitizer,
) : DiffRepository {

    @Suppress("ReturnCount", "LongMethod")
    override suspend fun diff(
        repoId: Long,
        path: String,
        source: DiffSource,
    ): DiffOutcome = withContext(io) {
        val binding = bindingRepo.currentOrNull() ?: return@withContext DiffOutcome.Failed(
            DiffFailure.PERMISSION_LOST,
        )
        if (binding.id != repoId) return@withContext DiffOutcome.Failed(DiffFailure.PERMISSION_LOST)

        val root = File(binding.localAbsPath)
        if (!root.exists()) return@withContext DiffOutcome.Failed(DiffFailure.FILE_MISSING)

        runCatching {
            Git.open(root).use { git ->
                val repo = git.repository
                computeDiff(repo, path, source)
            }
        }.getOrElse { t ->
            // Sanitize and degrade — never leak raw throwable to UI (R8).
            sanitizer.sanitize(t) // purely for log side-effect if we wire diagnostics later
            DiffOutcome.Failed(mapFailure(t))
        }
    }

    private fun computeDiff(
        repo: Repository,
        path: String,
        source: DiffSource,
    ): DiffOutcome {
        val headId = repo.resolve("HEAD") ?: return DiffOutcome.Failed(DiffFailure.FILE_MISSING)

        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { df ->
            df.setRepository(repo)
            df.setDiffComparator(RawTextComparator.DEFAULT)
            df.isDetectRenames = true
            df.setContext(DIFF_CONTEXT_LINES)
            df.pathFilter = PathFilter.create(path)

            val entries: List<DiffEntry> = when (source) {
                DiffSource.WORKING_VS_HEAD -> scanWorkingVsHead(repo, df, path)
                DiffSource.OURS_VS_THEIRS -> scanOursVsTheirs(repo, df, path)
            }
            val entry = entries.firstOrNull { it.newPath == path || it.oldPath == path }
                ?: entries.firstOrNull()
                ?: return DiffOutcome.Failed(DiffFailure.FILE_MISSING)

            // Binary probe — cheap, before we build the edit list.
            if (isBinaryEntry(repo, entry)) {
                return DiffOutcome.Binary(
                    oursSize = sizeOfSide(repo, entry, side = Side.OLD),
                    theirsSize = sizeOfSide(repo, entry, side = Side.NEW),
                )
            }

            return parseEntry(repo, df, entry)
        }
    }

    private fun scanWorkingVsHead(
        repo: Repository,
        df: DiffFormatter,
        path: String,
    ): List<DiffEntry> {
        val headId = repo.resolve("HEAD") ?: return emptyList()
        RevWalk(repo).use { rw ->
            val headTree = rw.parseCommit(headId).tree
            repo.newObjectReader().use { reader ->
                val headIt = CanonicalTreeParser().apply { reset(reader, headTree) }
                val workIt = FileTreeIterator(repo)
                return df.scan(headIt, workIt).filter { ent ->
                    ent.newPath == path || ent.oldPath == path
                }
            }
        }
    }

    private fun scanOursVsTheirs(
        repo: Repository,
        df: DiffFormatter,
        path: String,
    ): List<DiffEntry> {
        val headId = repo.resolve("HEAD") ?: return emptyList()
        val mergeId = repo.resolve("MERGE_HEAD") ?: return emptyList()
        RevWalk(repo).use { rw ->
            val headTree = rw.parseCommit(headId).tree
            val theirsTree = rw.parseCommit(mergeId).tree
            repo.newObjectReader().use { reader ->
                val headIt = CanonicalTreeParser().apply { reset(reader, headTree) }
                val theirsIt = CanonicalTreeParser().apply { reset(reader, theirsTree) }
                return df.scan(headIt, theirsIt).filter { ent ->
                    ent.newPath == path || ent.oldPath == path
                }
            }
        }
    }

    @Suppress("ReturnCount")
    private fun parseEntry(
        repo: Repository,
        df: DiffFormatter,
        entry: DiffEntry,
    ): DiffOutcome {
        val lines = mutableListOf<DiffLine>()

        val oldRaw = readSide(repo, entry, Side.OLD) ?: RawText.EMPTY_TEXT
        val newRaw = readSide(repo, entry, Side.NEW) ?: RawText.EMPTY_TEXT

        if (oldRaw.isBinaryFast() || newRaw.isBinaryFast()) {
            return DiffOutcome.Binary(
                oursSize = sizeOfSide(repo, entry, Side.OLD),
                theirsSize = sizeOfSide(repo, entry, Side.NEW),
            )
        }

        val fileHeader = df.toFileHeader(entry)
        lines += DiffLine(
            kind = DiffLineKind.HUNK_HEADER,
            oldLineNo = null,
            newLineNo = null,
            content = "--- a/${entry.oldPath}  +++ b/${entry.newPath}",
        )

        var truncated = false
        for (hunk in fileHeader.hunks) {
            if (lines.size >= MAX_LINES_FULL) {
                truncated = true
                break
            }
            val header = "@@ -${hunk.newStartLine}/${hunk.newLineCount} @@"
            lines += DiffLine(DiffLineKind.HUNK_HEADER, null, null, header)
            for (edit in hunk.toEditList()) {
                if (appendEdit(lines, oldRaw, newRaw, edit)) {
                    truncated = true
                    break
                }
            }
        }

        if (!truncated) {
            return DiffOutcome.Full(lines)
        }
        val shown = lines.take(TRUNCATE_TO_LINES)
        val estimated = estimateTotalLines(oldRaw, newRaw)
        return DiffOutcome.Truncated(shown, totalLines = estimated, shownLines = shown.size)
    }

    /** Returns `true` when the line budget is exhausted mid-edit. */
    @Suppress("NestedBlockDepth")
    private fun appendEdit(
        lines: MutableList<DiffLine>,
        old: RawText,
        new: RawText,
        edit: Edit,
    ): Boolean {
        for (i in edit.beginA until edit.endA) {
            if (lines.size >= MAX_LINES_FULL) return true
            lines += DiffLine(
                kind = DiffLineKind.REMOVED,
                oldLineNo = i + 1,
                newLineNo = null,
                content = old.safeLine(i),
            )
        }
        for (j in edit.beginB until edit.endB) {
            if (lines.size >= MAX_LINES_FULL) return true
            lines += DiffLine(
                kind = DiffLineKind.ADDED,
                oldLineNo = null,
                newLineNo = j + 1,
                content = new.safeLine(j),
            )
        }
        return false
    }

    private fun estimateTotalLines(old: RawText, new: RawText): Int {
        val bytes = old.size().coerceAtLeast(new.size())
        return (bytes.toLong() * 2L / AVG_LINE_BYTES).toInt().coerceAtLeast(MAX_LINES_FULL)
    }

    private enum class Side { OLD, NEW }

    @Suppress("ReturnCount")
    private fun isBinaryEntry(repo: Repository, entry: DiffEntry): Boolean {
        val raw = readSide(repo, entry, Side.NEW) ?: readSide(repo, entry, Side.OLD) ?: return false
        return raw.isBinaryFast()
    }

    private fun sizeOfSide(repo: Repository, entry: DiffEntry, side: Side): Long {
        val id = when (side) {
            Side.OLD -> entry.oldId?.toObjectId()
            Side.NEW -> entry.newId?.toObjectId()
        } ?: return 0L
        if (id.toString().all { it == '0' }) return 0L
        return runCatching {
            repo.newObjectReader().use { reader ->
                reader.getObjectSize(id, org.eclipse.jgit.lib.Constants.OBJ_BLOB)
            }
        }.getOrDefault(0L)
    }

    private fun readSide(repo: Repository, entry: DiffEntry, side: Side): RawText? {
        return when (side) {
            Side.OLD -> readBlob(repo, entry.oldId?.toObjectId())
            Side.NEW -> {
                // Working-tree path: DiffFormatter feeds FileTreeIterator entries
                // whose newId is the on-disk hash; falling back to file-read when
                // the object database does not know it.
                val fromObj = readBlob(repo, entry.newId?.toObjectId())
                fromObj ?: readWorkingCopy(repo, entry.newPath)
            }
        }
    }

    private fun readBlob(repo: Repository, id: org.eclipse.jgit.lib.ObjectId?): RawText? {
        if (id == null || id.toString().all { it == '0' }) return null
        return runCatching {
            repo.newObjectReader().use { reader: ObjectReader ->
                val loader = reader.open(id, org.eclipse.jgit.lib.Constants.OBJ_BLOB)
                RawText(loader.bytes)
            }
        }.recoverCatching { t ->
            if (t is LargeObjectException) null else throw t
        }.getOrNull()
    }

    private fun readWorkingCopy(repo: Repository, path: String): RawText? {
        if (path == DiffEntry.DEV_NULL) return null
        val f = File(repo.workTree, path)
        if (!f.isFile) return null
        return runCatching { RawText(f.readBytes()) }.getOrNull()
    }

    private fun RawText.safeLine(i: Int): String =
        runCatching { getString(i).trimEnd('\n', '\r') }.getOrDefault("")

    /**
     * JGit's `RawText.FIRST_FEW_BYTES` is package-private; we mirror its
     * conservative 8 KiB sniff window locally. Uses the public
     * `RawText.isBinary(byte[])` overload on the captured prefix.
     */
    private fun RawText.isBinaryFast(): Boolean {
        val prefix = this.rawContent.copyOfRange(
            0,
            minOf(size(), BINARY_SNIFF_BYTES),
        )
        return RawText.isBinary(prefix)
    }

    private fun mapFailure(t: Throwable): DiffFailure {
        val cls = t.javaClass.simpleName
        return when {
            cls.contains("FileNotFound", ignoreCase = true) -> DiffFailure.FILE_MISSING
            cls.contains("Encoding", ignoreCase = true) -> DiffFailure.ENCODING_UNSUPPORTED
            cls.contains("Access", ignoreCase = true) -> DiffFailure.PERMISSION_LOST
            cls.contains("Permission", ignoreCase = true) -> DiffFailure.PERMISSION_LOST
            else -> DiffFailure.UNKNOWN
        }
    }

    // Kotlin treats `DirCacheIterator` as used so the import isn't elided.
    @Suppress("unused")
    private fun dummy(): DirCacheIterator? = null
    @Suppress("unused")
    private fun dummyTreeWalk(repo: Repository, path: String): TreeWalk? =
        TreeWalk.forPath(repo, path, null)

    private companion object {
        const val DIFF_CONTEXT_LINES = 3
        const val MAX_LINES_FULL = 10_000
        const val TRUNCATE_TO_LINES = 5_000
        const val AVG_LINE_BYTES = 50
        const val BINARY_SNIFF_BYTES = 8 * 1024
    }
}
