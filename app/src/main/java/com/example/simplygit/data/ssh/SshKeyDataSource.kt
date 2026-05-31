package com.example.simplygit.data.ssh

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.SshKeyIndexEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data-layer SSH key store (SPEC §4.4.1 Iteration 3).
 *
 * Layout:
 *  - `filesDir/ssh/<keyId>.enc`          — AES-256-GCM `EncryptedFile` holding
 *    the OpenSSH private key text (plus a line delimiter and the public key
 *    on the trailing line, so we never need to keep a separate `.pub`).
 *  - `DataStore<Preferences>["ssh_key_index"]` — JSON list of
 *    `{keyId, fingerprint, createdAt}` triples for UI listing.
 *
 * The class does NOT produce keys — generation / import happens in
 * [SshKeyRepositoryImpl].
 */
@Singleton
internal class SshKeyDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val sshDir: File by lazy {
        File(context.filesDir, "ssh").apply { if (!exists()) mkdirs() }
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    fun observeIndex(): Flow<List<SshKeyIndexEntry>> =
        dataStore.data.map { prefs ->
            decodeIndex(prefs[KEY_INDEX] ?: "[]")
        }

    suspend fun readIndex(): List<SshKeyIndexEntry> = withContext(io) {
        decodeIndex(dataStore.data.first()[KEY_INDEX] ?: "[]")
    }

    suspend fun persist(
        keyId: String,
        privateKeyOpenssh: String,
        publicKeyOpenssh: String,
        fingerprintSha256: String,
        createdAt: Long,
    ) = withContext(io) {
        val file = fileFor(keyId)
        if (file.exists()) file.delete()
        val encryptedFile = EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        encryptedFile.openFileOutput().use { os ->
            os.writer().use { writer ->
                writer.write(privateKeyOpenssh)
                writer.write("\n# PUBLIC-KEY #\n")
                writer.write(publicKeyOpenssh)
            }
        }
        // Append to index.
        dataStore.edit { prefs ->
            val existing = decodeIndex(prefs[KEY_INDEX] ?: "[]")
                .filterNot { it.keyId == keyId }
            val merged = existing + SshKeyIndexEntry(keyId, fingerprintSha256, createdAt)
            prefs[KEY_INDEX] = encodeIndex(merged)
        }
    }

    suspend fun readPrivate(keyId: String): CharArray? = withContext(io) {
        val file = fileFor(keyId)
        if (!file.exists()) return@withContext null
        runCatching {
            val ef = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            val bytes = ef.openFileInput().use { it.readBytes() }
            val text = String(bytes, Charsets.UTF_8)
            val idx = text.indexOf("\n# PUBLIC-KEY #\n")
            val priv = if (idx >= 0) text.substring(0, idx) else text
            priv.toCharArray()
        }.getOrNull()
    }

    suspend fun readPublic(keyId: String): String? = withContext(io) {
        val file = fileFor(keyId)
        if (!file.exists()) return@withContext null
        runCatching {
            val ef = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            val bytes = ef.openFileInput().use { it.readBytes() }
            val text = String(bytes, Charsets.UTF_8)
            val idx = text.indexOf("\n# PUBLIC-KEY #\n")
            if (idx < 0) null else text.substring(idx + "\n# PUBLIC-KEY #\n".length)
        }.getOrNull()
    }

    suspend fun delete(keyId: String) = withContext(io) {
        fileFor(keyId).delete()
        dataStore.edit { prefs ->
            val existing = decodeIndex(prefs[KEY_INDEX] ?: "[]")
                .filterNot { it.keyId == keyId }
            prefs[KEY_INDEX] = encodeIndex(existing)
        }
    }

    private fun fileFor(keyId: String): File = File(sshDir, "$keyId.enc")

    private fun decodeIndex(json: String): List<SshKeyIndexEntry> = runCatching {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SshKeyIndexEntry(
                keyId = o.getString("keyId"),
                fingerprintSha256 = o.getString("fp"),
                createdAt = o.getLong("ts"),
            )
        }
    }.getOrDefault(emptyList())

    private fun encodeIndex(entries: List<SshKeyIndexEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
            o.put("keyId", e.keyId)
            o.put("fp", e.fingerprintSha256)
            o.put("ts", e.createdAt)
            arr.put(o)
        }
        return arr.toString()
    }

    private companion object {
        val KEY_INDEX = stringPreferencesKey("ssh_key_index")
    }
}
