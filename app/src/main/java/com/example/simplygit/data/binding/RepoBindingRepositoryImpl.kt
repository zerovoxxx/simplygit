package com.example.simplygit.data.binding

import com.example.simplygit.data.saf.SafUriStore
import com.example.simplygit.domain.model.RepoBinding
import com.example.simplygit.domain.repository.RepoBindingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoBindingRepositoryImpl @Inject constructor(
    private val store: SafUriStore,
) : RepoBindingRepository {

    override fun observe(): Flow<RepoBinding?> =
        combine(store.treeUri, store.localAbsPath, store.remoteUrl) { uri, abs, url ->
            if (uri.isNullOrBlank() || abs.isNullOrBlank() || url.isNullOrBlank()) null
            else RepoBinding(uri, abs, url)
        }

    override suspend fun requireCurrent(): RepoBinding =
        observe().first() ?: error("RepoBinding not configured")

    override suspend fun saveVault(treeUri: String, absPath: String) =
        store.saveVault(treeUri, absPath)

    override suspend fun saveRemote(url: String) = store.saveRemote(url)

    override suspend fun clear() = store.clear()
}
