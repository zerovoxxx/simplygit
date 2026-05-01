package com.example.simplygit.di

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKeys
import com.example.simplygit.data.binding.RepoBindingRepositoryImpl
import com.example.simplygit.data.credential.CredentialDataSource
import com.example.simplygit.data.credential.CredentialRepositoryImpl
import com.example.simplygit.data.credential.EncryptedCredentialDataSource
import com.example.simplygit.data.git.GitRepositoryImpl
import com.example.simplygit.domain.repository.CredentialRepository
import com.example.simplygit.domain.repository.GitRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Top-level DataStore for non-sensitive binding data (SPEC §4.7 / §6.1 → `repo.preferences_pb`). */
private val Context.repoDataStore: DataStore<Preferences> by preferencesDataStore(name = "repo")

/**
 * Wires Data-layer components (SPEC §4.7).
 *
 * Sensitive credentials → [EncryptedSharedPreferences] (ESP).
 * Non-sensitive binding state → Preferences DataStore.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideEncryptedPrefs(@ApplicationContext ctx: Context): SharedPreferences {
        // SPEC R-2: `MasterKey.Builder` is the Jetpack-recommended path on API 23+,
        // but some OEM ROMs reject it. Fall back to the legacy `MasterKeys.getOrCreate`
        // helper before surfacing a storage failure to the UI.
        return runCatching { createWithMasterKey(ctx) }
            .recoverCatching { createWithLegacyMasterKeys(ctx) }
            .getOrThrow()
    }

    private fun createWithMasterKey(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            ENCRYPTED_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Suppress("DEPRECATION")
    private fun createWithLegacyMasterKeys(ctx: Context): SharedPreferences {
        // Recreate the AES-256-GCM key spec MasterKeys.getOrCreate expects.
        val spec = KeyGenParameterSpec.Builder(
            MasterKeys.AES256_GCM_SPEC.keystoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        val alias = MasterKeys.getOrCreate(spec)
        return EncryptedSharedPreferences.create(
            ENCRYPTED_PREFS,
            alias,
            ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Provides
    @Singleton
    fun provideRepoDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.repoDataStore

    private const val ENCRYPTED_PREFS = "encrypted_prefs"
    private const val KEY_SIZE_BITS = 256
}

/** Repository / DataSource implementation bindings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindsModule {

    @Binds
    @Singleton
    abstract fun bindCredentialDataSource(
        impl: EncryptedCredentialDataSource,
    ): CredentialDataSource

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        impl: CredentialRepositoryImpl,
    ): CredentialRepository

    @Binds
    @Singleton
    abstract fun bindRepoBindingRepository(
        impl: RepoBindingRepositoryImpl,
    ): RepoBindingRepository

    @Binds
    @Singleton
    abstract fun bindGitRepository(
        impl: GitRepositoryImpl,
    ): GitRepository
}
