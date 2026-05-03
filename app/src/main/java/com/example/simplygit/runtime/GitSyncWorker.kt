package com.example.simplygit.runtime

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.simplygit.domain.model.RunSyncOutcome
import com.example.simplygit.domain.model.SyncTrigger
import com.example.simplygit.domain.repository.FileTreeRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.usecase.RunSyncUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Silent-sync worker (SPEC §4.4 Iteration 2, §4.1.1 Iteration 3).
 *
 * Delegates to [RunSyncUseCase] and maps its outcome onto WorkManager's
 * result domain:
 *  - `Ok` / `SkippedDebounce` / `SkippedPaused` / `NoBinding` → `success()`
 *  - `NetworkErr` → `retry()` — lets WM's exponential backoff take over.
 *  - Everything else (`PausedFs` / `PausedAuth` / `PausedConflict` /
 *    `MissingCredential` / `UnknownErr`) → `success()` because the use
 *    case has already persisted a `SyncLog` row and a state transition;
 *    we do not want WM to keep retrying a run that is waiting for user
 *    action.
 *
 * `UnknownErr` deliberately skips WM backoff (SPEC §4.5 / fix CR P2-06):
 * the failure is already counted toward the 3-strike `BROKEN` budget inside
 * `RunSyncUseCase.finishTransient`, and WM retry of an OOM / cancellation
 * would just keep hitting the same condition. Exposing user-facing failure
 * comes via the `BROKEN` banner + one-shot notification rather than hidden
 * backoff churn.
 *
 * Iteration 3 (§4.1.1): after a successful run we refresh the file-tree
 * cache so `RepoBrowserScreen`'s next open shows the latest paths /
 * Git status. Wrapped in `runCatching` so any rescan failure (e.g. SAF
 * permission race) is logged as Info without flipping WM's result.
 */
@HiltWorker
class GitSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runSync: RunSyncUseCase,
    private val bindingRepo: RepoBindingRepository,
    private val fileTreeRepo: FileTreeRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trigger = runCatching {
            SyncTrigger.valueOf(
                inputData.getString(WorkTags.KEY_TRIGGER) ?: SyncTrigger.PERIODIC.name,
            )
        }.getOrDefault(SyncTrigger.PERIODIC)

        when (val outcome = runSync(trigger)) {
            RunSyncOutcome.Ok -> {
                // SPEC §4.1.1 Iteration 3: refresh file-tree cache after a
                // successful sync. Must not influence Worker Result.
                runCatching {
                    bindingRepo.currentOrNull()?.let { fileTreeRepo.rescan(it.id) }
                }
                Result.success()
            }

            RunSyncOutcome.SkippedDebounce,
            is RunSyncOutcome.SkippedPaused,
            RunSyncOutcome.SkippedRunning,
            RunSyncOutcome.NoBinding,
            -> Result.success()

            RunSyncOutcome.NetworkErr -> Result.retry()

            RunSyncOutcome.PausedFs,
            RunSyncOutcome.PausedAuth,
            is RunSyncOutcome.PausedConflict,
            RunSyncOutcome.MissingCredential,
            RunSyncOutcome.UnknownErr,
            -> {
                // Silence the unused-warning — `outcome` is retained for readability.
                @Suppress("UnusedPrivateProperty") val unused = outcome
                Result.success()
            }
        }
    }
}
