package com.example.simplygit.domain.service

import com.example.simplygit.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quiet-window debounce guard (SPEC §4.2 / §4.3 Iteration 2).
 *
 * Walks the Vault tree (skipping `.git/`) and returns `true` when the most
 * recent file modification falls inside the quiet window, i.e. "changes are
 * still happening — defer the sync by one cycle".
 *
 * Implementation notes:
 *  - Iteration 2 has a single Vault (N4). Walking is done synchronously on
 *    the IO dispatcher; Obsidian vaults rarely exceed a few thousand files
 *    so a full `File.walkTopDown()` stays cheap.
 *  - `.git/` is skipped explicitly because JGit's own commit / fetch /
 *    push operations touch `index` / `packed-refs` and would otherwise
 *    keep the window open forever.
 */
@Singleton
class DebounceGuard @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {

    /**
     * @return `true` when the most recent mtime under [vaultAbsPath] is within
     * the last [window] (exclusive of `.git/`).
     */
    suspend fun withinQuietWindow(vaultAbsPath: String, window: Duration): Boolean =
        withContext(io) {
            val root = File(vaultAbsPath)
            if (!root.exists() || !root.isDirectory) return@withContext false
            val cutoff = clock.millis() - window.toMillis()
            var latest = 0L
            root.walkTopDown()
                .onEnter { dir -> dir.name != ".git" }
                .forEach { f ->
                    if (f.isFile) {
                        val m = f.lastModified()
                        if (m > latest) latest = m
                    }
                }
            latest >= cutoff
        }
}
