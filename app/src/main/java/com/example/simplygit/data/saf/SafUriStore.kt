package com.example.simplygit.data.saf

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-sensitive binding state stored in a Preferences DataStore (SPEC §4.3 / §6.1).
 */
@Singleton
class SafUriStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val treeUri: Flow<String?> = dataStore.data.map { it[KEY_VAULT_TREE_URI] }
    val localAbsPath: Flow<String?> = dataStore.data.map { it[KEY_LOCAL_ABS_PATH] }
    val remoteUrl: Flow<String?> = dataStore.data.map { it[KEY_REMOTE_URL] }

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
        }
    }

    private companion object {
        val KEY_VAULT_TREE_URI = stringPreferencesKey("vault_tree_uri")
        val KEY_LOCAL_ABS_PATH = stringPreferencesKey("local_abs_path")
        val KEY_REMOTE_URL = stringPreferencesKey("remote_url")
    }
}
