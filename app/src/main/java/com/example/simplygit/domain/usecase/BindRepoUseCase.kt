package com.example.simplygit.domain.usecase

import android.net.Uri
import com.example.simplygit.data.saf.ResolveResult
import com.example.simplygit.data.saf.SafPathResolver
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SyncPolicyRepository
import com.example.simplygit.domain.service.SyncScheduler
import javax.inject.Inject

/**
 * Ensures the WorkManager periodic sync request exists for the current complete
 * binding. This makes the default 15-minute policy effective even when the user
 * never opens the policy screen and presses Save.
 */
class EnsureAutoSyncScheduledUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val policyRepo: SyncPolicyRepository,
    private val scheduler: SyncScheduler,
) {
    suspend operator fun invoke() {
        if (runCatching { bindingRepo.currentOrNull() }.getOrNull() == null) return
        val policy = runCatching { policyRepo.current() }.getOrNull() ?: return
        runCatching { scheduler.schedulePeriodic(policy) }
    }
}

/**
 * Outcome exposed to the ViewModel after a SAF picker callback (SPEC §4.3).
 * Keeps path-related state machine decisions inside the Domain layer.
 */
sealed interface BindVaultOutcome {
    data class Bound(val absPath: String) : BindVaultOutcome
    data object NotPrimary : BindVaultOutcome
    data object NotReadable : BindVaultOutcome
}

/**
 * Resolves a newly granted SAF tree URI and persists the binding iff the path is
 * JGit-compatible. UI layer is expected to have called
 * [android.content.ContentResolver.takePersistableUriPermission] before invoking this.
 */
class BindVaultUseCase @Inject constructor(
    private val resolver: SafPathResolver,
    private val bindingRepo: RepoBindingRepository,
    private val ensureAutoSyncScheduled: EnsureAutoSyncScheduledUseCase,
) {
    suspend operator fun invoke(treeUri: Uri): BindVaultOutcome =
        when (val r = resolver.tryResolveAbsolutePath(treeUri)) {
            ResolveResult.NotPrimary -> BindVaultOutcome.NotPrimary
            ResolveResult.NotReadable -> BindVaultOutcome.NotReadable
            is ResolveResult.Ok -> {
                bindingRepo.saveVault(treeUri.toString(), r.absPath)
                ensureAutoSyncScheduled()
                BindVaultOutcome.Bound(r.absPath)
            }
        }
}

/** Persists the HTTPS remote URL into the binding store (SPEC §5.2). */
class BindRemoteUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val ensureAutoSyncScheduled: EnsureAutoSyncScheduledUseCase,
) {
    suspend operator fun invoke(url: String) {
        require(url.isNotBlank()) { "remote url must not be blank" }
        bindingRepo.saveRemote(url.trim())
        ensureAutoSyncScheduled()
    }
}
