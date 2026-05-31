package com.example.simplygit.ui.ssh

import com.example.simplygit.domain.model.SshKeyIndexEntry

data class SshKeyUiState(
    val loading: Boolean = false,
    val keys: List<SshKeyIndexEntry> = emptyList(),
    val justGenerated: GeneratedKeyPreview? = null,
    val importDialog: Boolean = false,
    val errorMessageKey: String? = null,
)

data class GeneratedKeyPreview(
    val keyId: String,
    val publicKeyOpenssh: String,
    val fingerprintSha256: String,
)
