package com.example.simplygit.domain.usecase

import com.example.simplygit.domain.repository.RepoBindingRepository
import javax.inject.Inject

/**
 * Flip the current binding between PAT and SSH (SPEC §4.4.3 Iteration 3).
 *
 * Thin wrapper around [RepoBindingRepository.saveAuth] so the Home
 * ViewModel does not couple to the storage contract directly. Validation is
 * delegated to the repository (`authType` must be `PAT`/`SSH`; `authRef`
 * prefix must match).
 */
class SaveAuthTypeUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
) {
    /**
     * @param authType `"PAT"` or `"SSH"`.
     * @param authRef  `"github_pat"` for PAT, `"ssh_<keyId>"` for SSH.
     */
    suspend operator fun invoke(authType: String, authRef: String) {
        bindingRepo.saveAuth(authType, authRef)
    }
}
