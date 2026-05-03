package com.example.simplygit.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simplygit.data.ssh.SshKeyFormatException
import com.example.simplygit.domain.model.DeleteSshKeyOutcome
import com.example.simplygit.domain.repository.SshKeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Arrays
import javax.inject.Inject

@HiltViewModel
class SshKeyViewModel @Inject constructor(
    private val sshKeyRepository: SshKeyRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SshKeyUiState())
    val uiState: StateFlow<SshKeyUiState> = _uiState.asStateFlow()

    init {
        sshKeyRepository.observeIndex()
            .onEach { keys -> _uiState.update { it.copy(keys = keys) } }
            .launchIn(viewModelScope)
    }

    fun generate() {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true, errorMessageKey = null) }
        viewModelScope.launch {
            val pair = runCatching { sshKeyRepository.generate(null) }.getOrNull()
            _uiState.update { state ->
                state.copy(
                    loading = false,
                    justGenerated = pair?.let {
                        GeneratedKeyPreview(
                            keyId = it.keyId,
                            publicKeyOpenssh = it.publicKeyOpenssh,
                            fingerprintSha256 = it.fingerprintSha256,
                        )
                    },
                    errorMessageKey = if (pair == null) "ssh_generate_failed" else null,
                )
            }
            pair?.wipe()
        }
    }

    fun importPasted(text: String) {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(loading = true, errorMessageKey = null) }
        viewModelScope.launch {
            val buf = text.toCharArray()
            val result = runCatching { sshKeyRepository.import(buf, null) }
            _uiState.update { state ->
                state.copy(
                    loading = false,
                    justGenerated = result.getOrNull()?.let {
                        GeneratedKeyPreview(
                            keyId = it.keyId,
                            publicKeyOpenssh = it.publicKeyOpenssh,
                            fingerprintSha256 = it.fingerprintSha256,
                        )
                    },
                    errorMessageKey = when {
                        result.isSuccess -> null
                        result.exceptionOrNull() is SshKeyFormatException -> "ssh_import_invalid_format"
                        else -> "ssh_import_failed"
                    },
                )
            }
            result.getOrNull()?.wipe()
            Arrays.fill(buf, '\u0000')
        }
    }

    fun delete(keyId: String) {
        viewModelScope.launch {
            val outcome = sshKeyRepository.delete(keyId)
            if (outcome is DeleteSshKeyOutcome.InUse) {
                _uiState.update { it.copy(errorMessageKey = "ssh_delete_in_use") }
            }
        }
    }

    fun dismissGenerated() {
        _uiState.update { it.copy(justGenerated = null) }
    }

    fun showImportDialog(show: Boolean) {
        _uiState.update { it.copy(importDialog = show) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessageKey = null) }
    }
}
