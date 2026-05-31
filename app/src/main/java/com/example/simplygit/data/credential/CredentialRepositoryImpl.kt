package com.example.simplygit.data.credential

import com.example.simplygit.domain.repository.CredentialIdentity
import com.example.simplygit.domain.repository.CredentialPublicView
import com.example.simplygit.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin adapter that forwards to [CredentialDataSource] and fills in the default
 * `@users.noreply.github.com` email when omitted (SPEC §6.2).
 */
@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val dataSource: CredentialDataSource,
) : CredentialRepository {

    override fun observe(): Flow<CredentialPublicView?> = dataSource.observe()

    /**
     * SPEC Iteration 2 (fix I-2): one-shot identity read.
     *
     * BUG-008 fix (bug_report_20260503_snao): delegate to
     * [CredentialDataSource.snapshotIdentity], which reads
     * `SharedPreferences` directly. The previous implementation went through
     * `observe().firstOrNull()`, which registered and immediately
     * unregistered a `SharedPreferences.OnSharedPreferenceChangeListener`
     * every time — unnecessary churn on the 15-minute sync loop.
     */
    override suspend fun snapshotIdentity(): CredentialIdentity? =
        dataSource.snapshotIdentity()?.let { CredentialIdentity(it.username, it.email) }

    override suspend fun save(username: String, email: String, pat: CharArray) {
        val effectiveEmail = email.ifBlank { "$username@users.noreply.github.com" }
        dataSource.save(username, effectiveEmail, pat)
    }

    override suspend fun saveIdentity(username: String, email: String) {
        val effectiveEmail = email.ifBlank { "$username@users.noreply.github.com" }
        dataSource.saveIdentity(username, effectiveEmail)
    }

    override suspend fun loadPatOnce(): CharArray? = dataSource.loadPatOnce()

    override suspend fun clear() = dataSource.clear()
}
