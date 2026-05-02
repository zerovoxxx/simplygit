package com.example.simplygit.domain.model

/**
 * Binding between a SAF tree URI, the resolved absolute path (fed into JGit's
 * `java.io.File`-based APIs, SPEC §3.2 path A) and the remote HTTPS URL.
 *
 * SPEC §4.1 / §4.6 Iteration 2: [id] is the Room primary key; default `0` means
 * "not persisted yet" (e.g. DataStore-only legacy binding during migration).
 */
data class RepoBinding(
    val treeUri: String,
    val localAbsPath: String,
    val remoteUrl: String,
    val id: Long = 0L,
)
