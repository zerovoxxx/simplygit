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
import com.example.simplygit.data.binding.RepoBindingRepositoryImpl
import com.example.simplygit.data.credential.CredentialDataSource
import com.example.simplygit.data.credential.CredentialRepositoryImpl
import com.example.simplygit.data.credential.EncryptedCredentialDataSource
import com.example.simplygit.data.filetree.FileTreeRepositoryImpl
import com.example.simplygit.data.git.ConflictRepositoryImpl
import com.example.simplygit.data.git.DiffRepositoryImpl
import com.example.simplygit.data.git.GitRepositoryImpl
import com.example.simplygit.data.ssh.SshKeyRepositoryImpl
import com.example.simplygit.data.sync.SyncLogRepositoryImpl
import com.example.simplygit.data.sync.SyncPolicyRepositoryImpl
import com.example.simplygit.domain.repository.ConflictRepository
import com.example.simplygit.domain.repository.CredentialRepository
import com.example.simplygit.domain.repository.DiffRepository
import com.example.simplygit.domain.repository.FileTreeRepository
import com.example.simplygit.domain.repository.GitRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import com.example.simplygit.domain.repository.SshKeyRepository
import com.example.simplygit.domain.repository.SyncLogRepository
import com.example.simplygit.domain.repository.SyncPolicyRepository
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
            androidx.security.crypto.MasterKeys.AES256_GCM_SPEC.keystoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        val alias = androidx.security.crypto.MasterKeys.getOrCreate(spec)
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
    internal abstract fun bindGitRepository(
        impl: GitRepositoryImpl,
    ): GitRepository

    // SPEC §4.8 Iteration 2: Room-backed repositories for sync policy / audit log.
    @Binds
    @Singleton
    abstract fun bindSyncPolicyRepository(
        impl: SyncPolicyRepositoryImpl,
    ): SyncPolicyRepository

    @Binds
    @Singleton
    abstract fun bindSyncLogRepository(
        impl: SyncLogRepositoryImpl,
    ): SyncLogRepository

    // SPEC §4.1.1 Iteration 3: file-tree cache powering `RepoBrowserScreen`.
    @Binds
    @Singleton
    internal abstract fun bindFileTreeRepository(
        impl: FileTreeRepositoryImpl,
    ): FileTreeRepository

    // SPEC §4.2.1 Iteration 3: unified-diff view powered by JGit DiffFormatter.
    @Binds
    @Singleton
    internal abstract fun bindDiffRepository(
        impl: DiffRepositoryImpl,
    ): DiffRepository

    // SPEC §4.3 Iteration 3: checkout --ours/--theirs + commit glue.
    @Binds
    @Singleton
    internal abstract fun bindConflictRepository(
        impl: ConflictRepositoryImpl,
    ): ConflictRepository

    // SPEC §4.4.1 Iteration 3: SSH key vault (independent of CredentialRepository).
    @Binds
    @Singleton
    internal abstract fun bindSshKeyRepository(
        impl: SshKeyRepositoryImpl,
    ): SshKeyRepository
}
