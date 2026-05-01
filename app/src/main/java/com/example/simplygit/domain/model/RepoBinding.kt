package com.example.simplygit.domain.model

/**
 * Binding between a SAF tree URI, the resolved absolute path (fed into JGit's
 * `java.io.File`-based APIs, SPEC §3.2 path A) and the remote HTTPS URL.
 */
data class RepoBinding(
    val treeUri: String,
    val localAbsPath: String,
    val remoteUrl: String,
)
