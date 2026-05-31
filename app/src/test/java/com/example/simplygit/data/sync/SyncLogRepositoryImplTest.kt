package com.example.simplygit.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncResult
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.model.SyncTrigger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SyncLogRepositoryImplTest {

    private lateinit var db: SimplygitDatabase
    private lateinit var repoDao: RepositoryDao
    private lateinit var logDao: SyncLogDao
    private lateinit var syncLogRepository: SyncLogRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SimplygitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repoDao = db.repositoryDao()
        logDao = db.syncLogDao()
        syncLogRepository = SyncLogRepositoryImpl(db, logDao, repoDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recoverStaleRunningAbortsOldWorkerLogAndReopensRepository() = runTest {
        val repoId = insertRepository(syncState = SyncState.RUNNING)
        val staleStartedAt = Instant.parse("2026-05-31T11:20:00Z")
        val now = Instant.parse("2026-05-31T12:00:00Z")
        val logId = logDao.insert(
            SyncLogEntity(
                repoId = repoId,
                startedAt = staleStartedAt.toEpochMilli(),
                endedAt = null,
                trigger = SyncTrigger.CATCHUP.name,
                result = null,
            ),
        )

        val recovered = syncLogRepository.recoverStaleRunning(
            repoId = repoId,
            staleBefore = Instant.parse("2026-05-31T11:30:00Z"),
            endedAt = now,
        )

        assertTrue(recovered)
        assertEquals(SyncState.IDLE.name, repoDao.findById(repoId)?.syncState)
        val row = logDao.findById(logId)
        assertEquals(now.toEpochMilli(), row?.endedAt)
        assertEquals(SyncResult.ABORTED.name, row?.result)
        assertEquals("StaleRunningRun", row?.errorType)
    }

    @Test
    fun recoverStaleRunningLeavesFreshWorkerRunAlone() = runTest {
        val repoId = insertRepository(syncState = SyncState.RUNNING)
        val freshStartedAt = Instant.parse("2026-05-31T11:59:00Z")
        val logId = logDao.insert(
            SyncLogEntity(
                repoId = repoId,
                startedAt = freshStartedAt.toEpochMilli(),
                endedAt = null,
                trigger = SyncTrigger.PERIODIC.name,
                result = null,
            ),
        )

        val recovered = syncLogRepository.recoverStaleRunning(
            repoId = repoId,
            staleBefore = Instant.parse("2026-05-31T11:30:00Z"),
            endedAt = Instant.parse("2026-05-31T12:00:00Z"),
        )

        assertFalse(recovered)
        assertEquals(SyncState.RUNNING.name, repoDao.findById(repoId)?.syncState)
        val row = logDao.findById(logId)
        assertEquals(null, row?.endedAt)
        assertEquals(null, row?.result)
    }

    @Test
    fun recoverStaleRunningReopensRepositoryWhenRunningStateHasNoOpenWorkerLog() = runTest {
        val repoId = insertRepository(syncState = SyncState.RUNNING)
        logDao.insert(
            SyncLogEntity(
                repoId = repoId,
                startedAt = Instant.parse("2026-05-31T11:20:00Z").toEpochMilli(),
                endedAt = Instant.parse("2026-05-31T11:21:00Z").toEpochMilli(),
                trigger = SyncTrigger.CATCHUP.name,
                result = SyncResult.OK.name,
            ),
        )

        val recovered = syncLogRepository.recoverStaleRunning(
            repoId = repoId,
            staleBefore = Instant.parse("2026-05-31T11:30:00Z"),
            endedAt = Instant.parse("2026-05-31T12:00:00Z"),
        )

        assertTrue(recovered)
        assertEquals(SyncState.IDLE.name, repoDao.findById(repoId)?.syncState)
    }

    @Test
    fun recoverStaleRunningClosesOrphanLogWithoutOverwritingIdleLastSync() = runTest {
        val repoId = insertRepository(syncState = SyncState.IDLE)
        val previousSync = Instant.parse("2026-05-31T11:50:00Z")
        repoDao.updateLastSync(repoId, previousSync.toEpochMilli(), SyncResult.OK.name)
        val logId = logDao.insert(
            SyncLogEntity(
                repoId = repoId,
                startedAt = Instant.parse("2026-05-31T11:00:00Z").toEpochMilli(),
                endedAt = null,
                trigger = SyncTrigger.PERIODIC.name,
                result = null,
            ),
        )

        val recovered = syncLogRepository.recoverStaleRunning(
            repoId = repoId,
            staleBefore = Instant.parse("2026-05-31T11:30:00Z"),
            endedAt = Instant.parse("2026-05-31T12:00:00Z"),
        )

        assertTrue(recovered)
        val repo = repoDao.findById(repoId)
        assertEquals(SyncState.IDLE.name, repo?.syncState)
        assertEquals(previousSync.toEpochMilli(), repo?.lastSyncAt)
        assertEquals(SyncResult.OK.name, repo?.lastSyncResult)
        assertEquals(SyncResult.ABORTED.name, logDao.findById(logId)?.result)
    }

    @Test
    fun abortRunFinishesCurrentLogAndReopensRepository() = runTest {
        val repoId = insertRepository(syncState = SyncState.RUNNING)
        val now = Instant.parse("2026-05-31T12:00:00Z")
        val logId = logDao.insert(
            SyncLogEntity(
                repoId = repoId,
                startedAt = Instant.parse("2026-05-31T11:59:00Z").toEpochMilli(),
                endedAt = null,
                trigger = SyncTrigger.PERIODIC.name,
                result = null,
            ),
        )

        syncLogRepository.abortRun(
            repoId = repoId,
            logId = logId,
            endedAt = now,
            errorMsg = "worker cancelled",
            errorType = "CancellationException",
        )

        assertEquals(SyncState.IDLE.name, repoDao.findById(repoId)?.syncState)
        val row = logDao.findById(logId)
        assertEquals(now.toEpochMilli(), row?.endedAt)
        assertEquals(SyncResult.ABORTED.name, row?.result)
        assertEquals("worker cancelled", row?.errorMsg)
        assertEquals("CancellationException", row?.errorType)
    }

    private suspend fun insertRepository(syncState: SyncState): Long {
        val policyId = db.syncPolicyDao().insert(SyncPolicyModel.DEFAULT.toEntity(id = 0L))
        return repoDao.insert(
            RepositoryEntity(
                displayName = "default",
                remoteUrl = "https://github.com/example/vault.git",
                authRef = "github_pat",
                localTreeUri = "content://tree",
                localAbsPath = "/storage/emulated/0/Documents/Vault",
                defaultBranch = "main",
                syncPolicyId = policyId,
                syncState = syncState.name,
                lastSyncAt = null,
                lastSyncResult = null,
                createdAt = Instant.parse("2026-05-31T10:00:00Z").toEpochMilli(),
            ),
        )
    }
}
