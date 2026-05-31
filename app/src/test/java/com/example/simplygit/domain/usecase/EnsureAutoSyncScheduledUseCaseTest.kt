package com.example.simplygit.domain.usecase

import com.example.simplygit.domain.model.RepoBinding
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.repository.RepoBindingPartial
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SyncPolicyRepository
import com.example.simplygit.domain.service.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsureAutoSyncScheduledUseCaseTest {

    @Test
    fun bindRemoteEnsuresScheduleWhenRemoteCompletesBinding() = runTest {
        val policy = SyncPolicyModel.DEFAULT.copy(intervalMinutes = 15)
        val bindingRepo = FakeRepoBindingRepository(current = null).apply {
            currentAfterSaveRemote = RepoBinding(
                treeUri = "content://tree",
                localAbsPath = "/storage/emulated/0/Documents/Vault",
                remoteUrl = "https://github.com/example/vault.git",
                id = 7L,
            )
        }
        val scheduler = RecordingScheduler()
        val ensureScheduled = EnsureAutoSyncScheduledUseCase(
            bindingRepo = bindingRepo,
            policyRepo = FakeSyncPolicyRepository(policy),
            scheduler = scheduler,
        )
        val useCase = BindRemoteUseCase(bindingRepo, ensureScheduled)

        useCase(" https://github.com/example/vault.git ")

        assertEquals("https://github.com/example/vault.git", bindingRepo.savedRemoteUrl)
        assertEquals(listOf(policy), scheduler.scheduledPolicies)
    }

    @Test
    fun schedulesCurrentPolicyWhenBindingIsComplete() = runTest {
        val policy = SyncPolicyModel(
            intervalMinutes = 30,
            requireUnmetered = true,
            requireCharging = false,
            commitMessageTemplate = "sync %ISO%",
        )
        val scheduler = RecordingScheduler()
        val useCase = EnsureAutoSyncScheduledUseCase(
            bindingRepo = FakeRepoBindingRepository(
                current = RepoBinding(
                    treeUri = "content://tree",
                    localAbsPath = "/storage/emulated/0/Documents/Vault",
                    remoteUrl = "https://github.com/example/vault.git",
                    id = 42L,
                ),
            ),
            policyRepo = FakeSyncPolicyRepository(policy),
            scheduler = scheduler,
        )

        useCase()

        assertEquals(listOf(policy), scheduler.scheduledPolicies)
    }

    @Test
    fun doesNotScheduleWhenBindingIsIncomplete() = runTest {
        val scheduler = RecordingScheduler()
        val useCase = EnsureAutoSyncScheduledUseCase(
            bindingRepo = FakeRepoBindingRepository(current = null),
            policyRepo = FakeSyncPolicyRepository(SyncPolicyModel.DEFAULT),
            scheduler = scheduler,
        )

        useCase()

        assertTrue(scheduler.scheduledPolicies.isEmpty())
    }

    private class FakeRepoBindingRepository(
        private var current: RepoBinding?,
    ) : RepoBindingRepository {
        var currentAfterSaveRemote: RepoBinding? = null
        var savedRemoteUrl: String? = null

        override fun observe(): Flow<RepoBinding?> = flowOf(current)
        override fun observePartial(): Flow<RepoBindingPartial?> = flowOf(null)
        override suspend fun requireCurrent(): RepoBinding =
            current ?: error("RepoBinding not configured")
        override suspend fun currentOrNull(): RepoBinding? = current
        override suspend fun migrateFromDataStoreIfNeeded() = Unit
        override suspend fun isMigrationDisabled(): Boolean = false
        override suspend fun saveVault(treeUri: String, absPath: String) = Unit
        override suspend fun saveRemote(url: String) {
            savedRemoteUrl = url
            current = currentAfterSaveRemote
        }
        override suspend fun saveAuth(authType: String, authRef: String) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeSyncPolicyRepository(
        private val policy: SyncPolicyModel,
    ) : SyncPolicyRepository {
        override fun observe(): Flow<SyncPolicyModel> = flowOf(policy)
        override suspend fun current(): SyncPolicyModel = policy
        override suspend fun update(policy: SyncPolicyModel) = Unit
    }

    private class RecordingScheduler : SyncScheduler {
        val scheduledPolicies = mutableListOf<SyncPolicyModel>()

        override fun schedulePeriodic(policy: SyncPolicyModel) {
            scheduledPolicies += policy
        }

        override fun triggerCatchUpOnce() = Unit

        override fun cancelAll() = Unit
    }
}
