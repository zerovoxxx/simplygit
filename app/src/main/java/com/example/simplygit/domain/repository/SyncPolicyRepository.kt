package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.SyncPolicyModel
import kotlinx.coroutines.flow.Flow

/** SPEC §6.2 Iteration 2. */
interface SyncPolicyRepository {
    fun observe(): Flow<SyncPolicyModel>
    suspend fun current(): SyncPolicyModel
    suspend fun update(policy: SyncPolicyModel)
}
