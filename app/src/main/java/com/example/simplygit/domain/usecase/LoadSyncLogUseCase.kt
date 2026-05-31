package com.example.simplygit.domain.usecase

import com.example.simplygit.domain.model.SyncLogModel
import com.example.simplygit.domain.repository.SyncLogRepository
import javax.inject.Inject

/** SPEC §4.7 Iteration 2: Audit detail loader. */
class LoadSyncLogUseCase @Inject constructor(
    private val repo: SyncLogRepository,
) {
    suspend operator fun invoke(id: Long): SyncLogModel? = repo.loadById(id)
}
