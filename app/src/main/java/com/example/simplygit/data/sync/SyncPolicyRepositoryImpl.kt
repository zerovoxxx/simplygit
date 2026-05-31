package com.example.simplygit.data.sync

import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [SyncPolicyRepository] (SPEC §4.6 / §6.1 Iteration 2).
 *
 * Lazily seeds [SyncPolicyModel.DEFAULT] on first read so callers always get a
 * usable value. N4: only 1 row in this iteration.
 */
@Singleton
class SyncPolicyRepositoryImpl @Inject constructor(
    private val dao: SyncPolicyDao,
) : SyncPolicyRepository {

    override fun observe(): Flow<SyncPolicyModel> = dao.observeFirst().map { entity ->
        entity?.toModel() ?: SyncPolicyModel.DEFAULT
    }

    override suspend fun current(): SyncPolicyModel =
        dao.findFirst()?.toModel() ?: ensureSeeded().toModel()

    override suspend fun update(policy: SyncPolicyModel) {
        val existing = dao.findFirst()
        if (existing == null) {
            dao.insert(policy.toEntity(id = 0L))
        } else {
            dao.update(policy.toEntity(id = existing.id))
        }
    }

    /** Inserts the default row iff absent and returns the persisted entity. */
    private suspend fun ensureSeeded(): SyncPolicyEntity {
        val existing = dao.findFirst()
        if (existing != null) return existing
        val id = dao.insert(SyncPolicyModel.DEFAULT.toEntity(id = 0L))
        return dao.findFirst() ?: SyncPolicyModel.DEFAULT.toEntity(id = id)
    }
}

internal fun SyncPolicyEntity.toModel(): SyncPolicyModel = SyncPolicyModel(
    intervalMinutes = intervalMinutes,
    requireUnmetered = requireUnmetered,
    requireCharging = requireCharging,
    commitMessageTemplate = commitMessageTemplate,
)

internal fun SyncPolicyModel.toEntity(id: Long): SyncPolicyEntity = SyncPolicyEntity(
    id = id,
    intervalMinutes = intervalMinutes,
    requireUnmetered = requireUnmetered,
    requireCharging = requireCharging,
    commitMessageTemplate = commitMessageTemplate,
)
