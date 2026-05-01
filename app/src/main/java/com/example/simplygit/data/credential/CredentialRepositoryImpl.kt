package com.example.simplygit.data.credential

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

    override suspend fun save(username: String, email: String, pat: CharArray) {
        val effectiveEmail = email.ifBlank { "$username@users.noreply.github.com" }
        dataSource.save(username, effectiveEmail, pat)
    }

    override suspend fun loadPatOnce(): CharArray? = dataSource.loadPatOnce()

    override suspend fun clear() = dataSource.clear()
}
