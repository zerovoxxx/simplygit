package com.example.simplygit.domain.repository

import com.example.simplygit.domain.model.RepoBinding
import kotlinx.coroutines.flow.Flow

/**
 * UI-facing "部分绑定" 快照：每一步绑定（Vault / Remote / Auth）写进 Room 后
 * 都应当立刻反映到首页，即使其他字段还是空的。
 *
 * [observe] 返回的 [RepoBinding] 仅在 Vault + Remote 都已填写时才会非空（因为
 * 下游业务把它当作"可以发起 Git 操作"的前置条件）；但 Home UI 要在更早阶段
 * 就展示"已绑定目录：xxx"、"远程：xxx"，所以这里提供一个**不做完整性过滤**
 * 的快照。
 */
data class RepoBindingPartial(
    val id: Long,
    val treeUri: String?,
    val localAbsPath: String?,
    val remoteUrl: String?,
    val authType: String,
    val authRef: String,
)

/** SPEC §6.2. */
interface RepoBindingRepository {
    fun observe(): Flow<RepoBinding?>

    /**
     * UI-only 观测入口：Vault / Remote 任一已填就会立刻发出一个
     * [RepoBindingPartial]，不受 "必须三个字段都非空" 的完整性闸门影响。
     *
     * 背景（fix）：Iteration 2 的 `toBindingOrNull()` 把 `remoteUrl.isBlank()`
     * 的行整体映射成 null，导致用户**先选 Vault 再填远程**的正常顺序下，
     * 选完 Vault 后首页仍然显示 "未绑定 Vault 目录"。
     */
    fun observePartial(): Flow<RepoBindingPartial?>

    /** @throws IllegalStateException when no binding has been established yet. */
    suspend fun requireCurrent(): RepoBinding

    /**
     * Returns the current binding, or `null` when no binding exists OR when
     * `localAbsPath` is still unresolved (SPEC §4.6 Iteration 2 mapping rule).
     * Preferred entry point for background workers: no Flow subscription.
     *
     * SPEC Iteration 2 (fix I-2): used by
     * [com.example.simplygit.domain.usecase.RunSyncUseCase] to short-circuit to
     * `RunSyncOutcome.NoBinding`.
     */
    suspend fun currentOrNull(): RepoBinding?

    /**
     * One-shot DataStore → Room migration entry (SPEC §4.6 Iteration 2 /
     * fix CR P2-01). Called from `SimplyGitApp.onCreate` in an async
     * `GlobalScope.launch` so the very first frame is not blocked; callers
     * that directly read Room (e.g. [observe]) see the migrated row as soon
     * as this completes.
     *
     * Idempotent: becomes a no-op once `migration_v1_done = true` or the
     * `repository` table already has a row. Failures bump a retry counter —
     * three consecutive failures flip the migration into a *disabled* state
     * exposed via [isMigrationDisabled], and Iteration-2 auto-sync features
     * must fall back to "guide the user to re-bind".
     */
    suspend fun migrateFromDataStoreIfNeeded()

    /**
     * Returns true when the DataStore → Room migration has failed
     * [MAX_MIGRATION_RETRIES] times and should be surfaced to the user as a
     * re-bind prompt (SPEC §4.6 Iteration 2 / fix CR P2-01).
     */
    suspend fun isMigrationDisabled(): Boolean

    suspend fun saveVault(treeUri: String, absPath: String)
    suspend fun saveRemote(url: String)

    /**
     * SPEC §4.4.3 / §6.1 Iteration 3: persist the auth dispatch tuple so the
     * next sync dispatches through [com.example.simplygit.data.git.JGitDataSource]'s
     * `applyAuth` to either PAT or SSH.
     *
     * Invariants enforced by the implementation:
     *  - [authType] must be `"PAT"` or `"SSH"`.
     *  - [authRef] must be `"github_pat"` when [authType] == `"PAT"` (N4
     *    single-PAT slot) and `"ssh_<keyId>"` when [authType] == `"SSH"`.
     */
    suspend fun saveAuth(authType: String, authRef: String)

    suspend fun clear()

    companion object {
        /**
         * Maximum consecutive DataStore → Room migration attempts before the
         * migration is considered broken and the user must re-bind
         * (SPEC §4.6 Iteration 2 / fix CR P2-01).
         */
        const val MAX_MIGRATION_RETRIES: Int = 3
    }
}
