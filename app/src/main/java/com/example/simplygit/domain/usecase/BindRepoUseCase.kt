package com.example.simplygit.domain.usecase

import android.net.Uri
import com.example.simplygit.data.saf.ResolveResult
import com.example.simplygit.data.saf.SafPathResolver
import com.example.simplygit.domain.repository.RepoBindingRepository
import javax.inject.Inject

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
) {
    suspend operator fun invoke(treeUri: Uri): BindVaultOutcome =
        when (val r = resolver.tryResolveAbsolutePath(treeUri)) {
            ResolveResult.NotPrimary -> BindVaultOutcome.NotPrimary
            ResolveResult.NotReadable -> BindVaultOutcome.NotReadable
            is ResolveResult.Ok -> {
                bindingRepo.saveVault(treeUri.toString(), r.absPath)
                BindVaultOutcome.Bound(r.absPath)
            }
        }
}

/** Persists the HTTPS remote URL into the binding store (SPEC §5.2). */
class BindRemoteUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
) {
    suspend operator fun invoke(url: String) {
        require(url.isNotBlank()) { "remote url must not be blank" }
        bindingRepo.saveRemote(url.trim())
    }
}
