package com.example.simplygit.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simplygit.data.sync.FileTreeCacheDao
import com.example.simplygit.domain.repository.FileTreeRepository
import com.example.simplygit.domain.repository.RepoBindingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [RepoBrowserScreen] (SPEC §4.1.2 Iteration 3).
 *
 * Loads the current directory listing from [FileTreeRepository]; triggers a
 * rescan on first open if the cache is empty or when the user pulls to
 * refresh.
 */
@HiltViewModel
class RepoBrowserViewModel @Inject constructor(
    private val fileTreeRepo: FileTreeRepository,
    private val bindingRepo: RepoBindingRepository,
    private val fileTreeCacheDao: FileTreeCacheDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RepoBrowserUiState())
    val uiState: StateFlow<RepoBrowserUiState> = _uiState.asStateFlow()

    fun initialize(repoId: Long) {
        viewModelScope.launch {
            val binding = bindingRepo.currentOrNull()
            if (binding == null || binding.id != repoId) {
                _uiState.update { it.copy(noBinding = true) }
                return@launch
            }
            _uiState.update { it.copy(repoId = repoId) }
            // SPEC §4.1.1: first open → trigger rescan when cache empty.
            val cached = fileTreeCacheDao.countForRepo(repoId)
            if (cached == 0) {
                triggerRescan()
            }
            loadPath("")
        }
    }

    fun loadPath(path: String) {
        viewModelScope.launch {
            val repoId = _uiState.value.repoId
            if (repoId == 0L) return@launch
            val children = fileTreeRepo.listChildren(repoId, path)
            _uiState.update { state ->
                state.copy(
                    currentPath = path,
                    currentEntries = children,
                    breadcrumb = buildBreadcrumb(path),
                )
            }
        }
    }

    fun triggerRescan() {
        val repoId = _uiState.value.repoId
        if (repoId == 0L) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRescanning = true, repoTooLarge = false) }
            val outcome = runCatching { fileTreeRepo.rescan(repoId) }.getOrNull()
            val currentPath = _uiState.value.currentPath
            val children = fileTreeRepo.listChildren(repoId, currentPath)
            _uiState.update { state ->
                state.copy(
                    currentEntries = children,
                    isRescanning = false,
                    lastRescanAt = outcome?.let { System.currentTimeMillis() },
                    repoTooLarge = outcome?.totalEntries == -1,
                )
            }
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        if (current.isEmpty()) return
        val idx = current.lastIndexOf('/')
        loadPath(if (idx < 0) "" else current.substring(0, idx))
    }

    private fun buildBreadcrumb(path: String): List<BreadcrumbSegment> {
        val segments = mutableListOf(BreadcrumbSegment(label = "Vault", path = ""))
        if (path.isEmpty()) return segments
        var acc = ""
        for (part in path.split('/')) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            segments.add(BreadcrumbSegment(label = part, path = acc))
        }
        return segments
    }
}
