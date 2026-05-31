@file:Suppress("DEPRECATION", "TooManyFunctions")

package com.example.simplygit.data.binding

import androidx.room.withTransaction
import com.example.simplygit.data.diagnostics.DiagnosticsLogger
import com.example.simplygit.data.saf.SafUriStore
import com.example.simplygit.data.sync.RepositoryDao
import com.example.simplygit.data.sync.RepositoryEntity
import com.example.simplygit.data.sync.SimplygitDatabase
import com.example.simplygit.data.sync.SyncPolicyDao
import com.example.simplygit.data.sync.toEntity
import com.example.simplygit.domain.model.RepoBinding
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.model.SyncState
import com.example.simplygit.domain.repository.RepoBindingPartial
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [RepoBindingRepository] (SPEC §4.6 Iteration 2 / fix I-2 / I-8 /
 * CR P2-01 / P2-02).
 *
 * Iteration 1 stored the binding in DataStore Preferences. Iteration 2 moves
 * it to Room so the binding can participate in transactions with
 * [com.example.simplygit.data.sync.SyncPolicyEntity] /
 * [com.example.simplygit.data.sync.SyncLogEntity]. The legacy DataStore entry
 * remains as a migration source only (see [migrateFromDataStoreIfNeeded]).
 *
 * Mapping rules (SPEC §4.6):
 *  - `RepoBinding.id ↔ RepositoryEntity.id`
 *  - `RepoBinding.treeUri ↔ RepositoryEntity.localTreeUri`
 *  - `RepoBinding.localAbsPath (non-null) ↔ RepositoryEntity.localAbsPath (nullable)`
 *    Rows with null `localAbsPath` are treated as "SAF path still unresolved"
 *    and surfaced as `null` bindings so `RunSyncUseCase` short-circuits to
 *    `RunSyncOutcome.NoBinding`.
 *
 * Migration lifecycle (CR P2-01 / P2-02):
 *  1. `SimplyGitApp.onCreate` launches [migrateFromDataStoreIfNeeded] in the
 *     application scope so the very first UI frame does not wait on it.
 *  2. A successful migration flips `migration_v1_done = true` in the legacy
 *     store (it **keeps** the business keys around until the next cold start,
 *     SPEC R-7 "migrate-then-clear").
 *  3. On every subsequent cold start, if `migration_v1_done == true` and the
 *     legacy business keys are still present, we delete them. Crashing in
 *     between boot N and boot N+1 only risks re-delivering an already-idempotent
 *     no-op, never data loss.
 *  4. Failures increment `migration_v1_retry_count`; reaching
 *     [RepoBindingRepository.MAX_MIGRATION_RETRIES] flips the repository into
 *     a *disabled* state exposed via [isMigrationDisabled].
 */
@Singleton
class RepoBindingRepositoryImpl @Inject constructor(
    private val db: SimplygitDatabase,
    private val repoDao: RepositoryDao,
    private val policyDao: SyncPolicyDao,
    private val legacyStore: SafUriStore,
    private val diagnostics: DiagnosticsLogger,
    private val clock: Clock,
) : RepoBindingRepository {

    override fun observe(): Flow<RepoBinding?> =
        repoDao.observeFirst().map { it.toBindingOrNull() }

    override fun observePartial(): Flow<RepoBindingPartial?> =
        repoDao.observeFirst().map { it?.toPartial() }

    override suspend fun requireCurrent(): RepoBinding =
        currentOrNull() ?: error("RepoBinding not configured")

    override suspend fun currentOrNull(): RepoBinding? {
        // Defensive: if `SimplyGitApp.onCreate`'s async migration has not run
        // yet (e.g. a direct-boot-aware worker), fall through to a synchronous
        // attempt here. Idempotent — `migrationAlreadyCompleted()` short-circuits.
        migrateFromDataStoreIfNeeded()
        return repoDao.findFirst().toBindingOrNull()
    }

    override suspend fun saveVault(treeUri: String, absPath: String) {
        db.withTransaction {
            val policyId = ensurePolicyRow()
            val existing = repoDao.findFirst()
            if (existing == null) {
                repoDao.insert(
                    newRepositoryEntity(
                        treeUri = treeUri,
                        absPath = absPath,
                        remoteUrl = "",
                        policyId = policyId,
                    ),
                )
            } else {
                repoDao.updateVaultPath(existing.id, treeUri, absPath)
            }
        }
    }

    override suspend fun saveRemote(url: String) {
        require(url.isNotBlank()) { "remote url must not be blank" }
        db.withTransaction {
            val policyId = ensurePolicyRow()
            val existing = repoDao.findFirst()
            if (existing == null) {
                repoDao.insert(
                    newRepositoryEntity(
                        treeUri = "",
                        absPath = null,
                        remoteUrl = url,
                        policyId = policyId,
                    ),
                )
            } else {
                repoDao.updateRemoteUrl(existing.id, url)
            }
        }
    }

    /**
     * SPEC §4.4.3 / §6.1 Iteration 3: persist the auth tuple. We reject
     * unknown [authType] values and mismatched [authRef] prefixes here
     * rather than at the SQL layer so the contract violation never reaches
     * the database.
     */
    override suspend fun saveAuth(authType: String, authRef: String) {
        require(authType == "PAT" || authType == "SSH") { "authType must be PAT or SSH" }
        if (authType == "PAT") {
            require(authRef == "github_pat") { "PAT authRef must be 'github_pat'" }
        } else {
            require(authRef.startsWith("ssh_")) { "SSH authRef must start with 'ssh_'" }
        }
        db.withTransaction {
            val existing = repoDao.findFirst()
                ?: error("cannot saveAuth before binding exists")
            repoDao.updateAuth(existing.id, authType, authRef)
        }
    }

    override suspend fun clear() {
        db.withTransaction {
            repoDao.clear()
            legacyStore.clear()
        }
    }

    /**
     * SPEC §4.6 Iteration 2 / fix CR P2-01. See the class-level KDoc for the
     * lifecycle. Returns early in the hot path (`migration_v1_done == true`)
     * so calling this from both `SimplyGitApp.onCreate` and `currentOrNull()`
     * costs a single DataStore read.
     */
    override suspend fun migrateFromDataStoreIfNeeded() {
        if (legacyStore.isMigrationDone()) {
            // SPEC §4.6 step (3): the cold start **after** a successful
            // migration clears the legacy business keys (R-7 "migrate-then-clear").
            purgeLegacyBindingKeysIfPresent()
            return
        }
        if (repoDao.count() > 0) {
            // Room already has a row (e.g. fresh install): no migration needed;
            // still mark done so future boots skip this path.
            legacyStore.markMigrationDone()
            legacyStore.resetMigrationRetry()
            return
        }

        val tree = legacyStore.treeUri.first()
        val abs = legacyStore.localAbsPath.first()
        val remote = legacyStore.remoteUrl.first()
        if (tree.isNullOrBlank() || abs.isNullOrBlank() || remote.isNullOrBlank()) {
            // User never finished Iteration-1 binding: there is nothing to
            // migrate. We leave `migration_v1_done` as-is so a later bind in
            // Iteration-2 does not need to re-check a migration that isn't
            // applicable.
            return
        }

        val result = runCatching {
            db.withTransaction {
                val policyId = ensurePolicyRow()
                repoDao.insert(
                    newRepositoryEntity(
                        treeUri = tree,
                        absPath = abs,
                        remoteUrl = remote,
                        policyId = policyId,
                    ),
                )
            }
        }
        result.fold(
            onSuccess = {
                legacyStore.markMigrationDone()
                legacyStore.resetMigrationRetry()
                // Legacy keys are NOT cleared right now — SPEC R-7 asks us to
                // keep them until the *next* cold start so a crash between
                // "insert committed" and "return from onCreate" still leaves a
                // recoverable source. [purgeLegacyBindingKeysIfPresent] runs
                // on the subsequent boot.
            },
            onFailure = { t ->
                val retries = legacyStore.incrementMigrationRetry()
                diagnostics.logInfo(
                    "migration_failed",
                    "attempt=$retries type=${t.javaClass.simpleName}",
                )
            },
        )
    }

    override suspend fun isMigrationDisabled(): Boolean {
        if (legacyStore.isMigrationDone()) return false
        if (repoDao.count() > 0) return false
        return legacyStore.migrationRetryCount() >= RepoBindingRepository.MAX_MIGRATION_RETRIES
    }

    /**
     * Deletes the three legacy business keys once `migration_v1_done` is
     * true. Called lazily from [migrateFromDataStoreIfNeeded] on the cold
     * start after a successful migration (SPEC §4.6 step 3).
     */
    private suspend fun purgeLegacyBindingKeysIfPresent() {
        val tree = legacyStore.treeUri.first()
        val abs = legacyStore.localAbsPath.first()
        val remote = legacyStore.remoteUrl.first()
        if (tree == null && abs == null && remote == null) return
        legacyStore.clearLegacyBindingKeys()
        diagnostics.logInfo("migration_legacy_keys_cleared", "tree+abs+remote")
    }

    private suspend fun ensurePolicyRow(): Long {
        val existing = policyDao.findFirst()
        if (existing != null) return existing.id
        return policyDao.insert(SyncPolicyModel.DEFAULT.toEntity(id = 0L))
    }

    private fun newRepositoryEntity(
        treeUri: String,
        absPath: String?,
        remoteUrl: String,
        policyId: Long,
    ): RepositoryEntity = RepositoryEntity(
        id = 0L,
        displayName = "default",
        remoteUrl = remoteUrl,
        authType = "PAT",
        authRef = "github_pat",
        localTreeUri = treeUri,
        localAbsPath = absPath,
        defaultBranch = "main",
        syncPolicyId = policyId,
        syncState = SyncState.IDLE.name,
        lastSyncAt = null,
        lastSyncResult = null,
        createdAt = clock.millis(),
    )
}

// SPEC §6.1 Iteration 3 (P0-3 / P0-4): authType / authRef now flow
// through RepoBinding so JGitDataSource.applyAuth() can dispatch on them.
private fun RepositoryEntity?.toBindingOrNull(): RepoBinding? {
    if (this == null) return null
    if (localAbsPath.isNullOrBlank() || localTreeUri.isBlank() || remoteUrl.isBlank()) return null
    return RepoBinding(
        treeUri = localTreeUri,
        localAbsPath = localAbsPath,
        remoteUrl = remoteUrl,
        id = id,
        authType = authType,
        authRef = authRef,
    )
}

/**
 * UI-only 投影：允许 Vault / Remote 暂时缺失 —— 这样用户完成某一步绑定后
 * 首页能立刻展示"已绑定目录"/"远程：xxx"，而不是继续显示"未绑定"。
 */
private fun RepositoryEntity.toPartial(): RepoBindingPartial = RepoBindingPartial(
    id = id,
    treeUri = localTreeUri.ifBlank { null },
    localAbsPath = localAbsPath?.ifBlank { null },
    remoteUrl = remoteUrl.ifBlank { null },
    authType = authType,
    authRef = authRef,
)
