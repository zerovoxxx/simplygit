package com.example.simplygit.data.credential

import android.content.SharedPreferences
import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.repository.CredentialPublicView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EncryptedSharedPreferences-backed implementation of [CredentialDataSource] (SPEC §4.2).
 *
 * Storage keys:
 *  - `github_pat` — ciphertext (ESP handles AES-256-GCM internally)
 *  - `github_username` — plaintext
 *  - `github_email` — plaintext
 *
 * The PAT is never exposed through [observe]; it may only be retrieved through
 * [loadPatOnce], and the caller is responsible for wiping the returned buffer.
 */
@Singleton
class EncryptedCredentialDataSource @Inject constructor(
    private val prefs: SharedPreferences,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CredentialDataSource {

    override suspend fun save(username: String, email: String, pat: CharArray) {
        withContext(io) {
            // ESP stores only String; we convert CharArray -> String at the last possible
            // moment. We cannot zero out a String, but we keep its scope minimal.
            val patString = String(pat)
            try {
                prefs.edit()
                    .putString(KEY_USERNAME, username)
                    .putString(KEY_EMAIL, email)
                    .putString(KEY_PAT, patString)
                    .apply()
            } finally {
                Arrays.fill(pat, '\u0000')
            }
        }
    }

    override fun observe(): Flow<CredentialPublicView?> = callbackFlow {
        fun snapshot(): CredentialPublicView? {
            val u = prefs.getString(KEY_USERNAME, null) ?: return null
            val e = prefs.getString(KEY_EMAIL, null) ?: return null
            return CredentialPublicView(u, e)
        }
        trySend(snapshot())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_USERNAME || key == KEY_EMAIL || key == KEY_PAT) {
                trySend(snapshot())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(io)

    override suspend fun loadPatOnce(): CharArray? = withContext(io) {
        val s = prefs.getString(KEY_PAT, null) ?: return@withContext null
        val copy = CharArray(s.length)
        s.toCharArray(copy)
        copy
    }

    override suspend fun clear() {
        withContext(io) {
            prefs.edit()
                .remove(KEY_PAT)
                .remove(KEY_USERNAME)
                .remove(KEY_EMAIL)
                .apply()
        }
    }

    private companion object {
        const val KEY_PAT = "github_pat"
        const val KEY_USERNAME = "github_username"
        const val KEY_EMAIL = "github_email"
    }
}
