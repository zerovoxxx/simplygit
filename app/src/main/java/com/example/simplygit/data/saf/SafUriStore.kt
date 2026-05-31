package com.example.simplygit.data.saf

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy non-sensitive binding state stored in a Preferences DataStore
 * (SPEC §4.3 Iteration 1).
 *
 * SPEC Iteration 2 (fix I-8 + CR P2-01/P2-02): deprecated — the binding has
 * been migrated to Room (`repository` table). This class is retained ONLY as:
 *   - a **migration source** for
 *     [com.example.simplygit.data.binding.RepoBindingRepositoryImpl.migrateFromDataStoreIfNeeded],
 *   - a **migration bookkeeping store** for `migration_v1_done` and
 *     `migration_v1_retry_count`.
 *
 * All new business writes go to Room. The source `vault_tree_uri` /
 * `local_abs_path` / `remote_url` keys are cleared by
 * [clearLegacyBindingKeys] on the cold start **after** a successful migration
 * so data never lingers beyond one boot (SPEC R-7 "migrate-then-clear").
 */
@Deprecated(
    "SPEC §4.6 Iteration 2: migrated to Room. Used as a migration source only.",
    level = DeprecationLevel.WARNING,
)
@Singleton
class SafUriStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val treeUri: Flow<String?> = dataStore.data.map { it[KEY_VAULT_TREE_URI] }
    val localAbsPath: Flow<String?> = dataStore.data.map { it[KEY_LOCAL_ABS_PATH] }
    val remoteUrl: Flow<String?> = dataStore.data.map { it[KEY_REMOTE_URL] }

    /** One-shot read of the migration-done flag (default false). */
    suspend fun isMigrationDone(): Boolean =
        dataStore.data.first()[KEY_MIGRATION_V1_DONE] == true

    /** One-shot read of the consecutive migration-failure counter (default 0). */
    suspend fun migrationRetryCount(): Int =
        dataStore.data.first()[KEY_MIGRATION_V1_RETRIES] ?: 0

    suspend fun markMigrationDone() {
        dataStore.edit { it[KEY_MIGRATION_V1_DONE] = true }
    }

    suspend fun incrementMigrationRetry(): Int {
        var next = 0
        dataStore.edit { prefs ->
            next = (prefs[KEY_MIGRATION_V1_RETRIES] ?: 0) + 1
            prefs[KEY_MIGRATION_V1_RETRIES] = next
        }
        return next
    }

    suspend fun resetMigrationRetry() {
        dataStore.edit { it.remove(KEY_MIGRATION_V1_RETRIES) }
    }

    /**
     * Clears ONLY the legacy business keys (tree / abs / remote), preserving
     * `migration_v1_done` so we never re-migrate. Called from
     * `RepoBindingRepositoryImpl` on the cold start after a successful
     * migration (SPEC R-7, "migrate-then-clear").
     */
    suspend fun clearLegacyBindingKeys() {
        dataStore.edit {
            it.remove(KEY_VAULT_TREE_URI)
            it.remove(KEY_LOCAL_ABS_PATH)
            it.remove(KEY_REMOTE_URL)
        }
    }

    suspend fun saveVault(treeUri: String, absPath: String) {
        dataStore.edit { prefs ->
            prefs[KEY_VAULT_TREE_URI] = treeUri
            prefs[KEY_LOCAL_ABS_PATH] = absPath
        }
    }

    suspend fun saveRemote(url: String) {
        dataStore.edit { it[KEY_REMOTE_URL] = url }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_VAULT_TREE_URI)
            it.remove(KEY_LOCAL_ABS_PATH)
            it.remove(KEY_REMOTE_URL)
            it.remove(KEY_MIGRATION_V1_DONE)
            it.remove(KEY_MIGRATION_V1_RETRIES)
        }
    }

    private companion object {
        val KEY_VAULT_TREE_URI = stringPreferencesKey("vault_tree_uri")
        val KEY_LOCAL_ABS_PATH = stringPreferencesKey("local_abs_path")
        val KEY_REMOTE_URL = stringPreferencesKey("remote_url")
        val KEY_MIGRATION_V1_DONE = booleanPreferencesKey("migration_v1_done")
        val KEY_MIGRATION_V1_RETRIES = intPreferencesKey("migration_v1_retry_count")
    }
}
