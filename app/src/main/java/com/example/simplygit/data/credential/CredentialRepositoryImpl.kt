package com.example.simplygit.data.credential

import com.example.simplygit.domain.repository.CredentialIdentity
import com.example.simplygit.domain.repository.CredentialPublicView
import com.example.simplygit.domain.repository.CredentialRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
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
     * SPEC Iteration 2 (fix I-2): one-shot identity read. Built on top of the
     * existing Flow so we don't duplicate ESP read logic here.
     */
    override suspend fun snapshotIdentity(): CredentialIdentity? =
        dataSource.observe().firstOrNull()?.let { CredentialIdentity(it.username, it.email) }

    override suspend fun save(username: String, email: String, pat: CharArray) {
        val effectiveEmail = email.ifBlank { "$username@users.noreply.github.com" }
        dataSource.save(username, effectiveEmail, pat)
    }

    override suspend fun loadPatOnce(): CharArray? = dataSource.loadPatOnce()

    override suspend fun clear() = dataSource.clear()
}
