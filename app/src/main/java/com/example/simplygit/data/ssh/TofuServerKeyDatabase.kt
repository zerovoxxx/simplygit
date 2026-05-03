package com.example.simplygit.data.ssh

import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import java.io.File
import java.net.InetSocketAddress
import java.security.PublicKey

/**
 * TOFU (trust-on-first-use) host-key manager (SPEC §4.4.2 Iteration 3).
 *
 * Backs the JGit `SshdSessionFactory.setServerKeyDatabase(...)` callback so
 * every outbound SSH connection goes through `accept(...)`. The behaviour:
 *
 *  - **Known + matching fingerprint** → accept silently.
 *  - **Known + mismatched fingerprint** → throw [SshHostKeyChangedException]
 *    so `JGitDataSource` can route the sync into `PAUSED_AUTH`
 *    (`SyncErrorKind.Auth` classification).
 *  - **Unknown host** → throw [SshHostKeyFirstConnectException] so the UI
 *    layer catches it, shows a TOFU confirmation dialog, and — after the
 *    user confirms — calls [accept] to persist the fingerprint before
 *    retrying.
 *
 * Storage format (one record per line, not OpenSSH canonical — keeps the
 * parser trivial):
 *   `<host> <algoTag> <base64PublicKey> <sha256Fingerprint>`
 *
 * The file lives at `filesDir/ssh/known_hosts` (app-private), so it is not
 * readable from other apps.
 */
internal class TofuServerKeyDatabase(private val knownHosts: File) : ServerKeyDatabase {

    init {
        knownHosts.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (!knownHosts.exists()) knownHosts.createNewFile()
    }

    /**
     * Returns every host key we already trust for [connectAddress]. JGit
     * uses this for informational logging and to detect "key was removed
     * from known_hosts but is still being offered by server" edge cases.
     * A conservative empty list is fine; the real gate is [accept].
     */
    override fun lookup(
        connectAddress: String,
        remoteAddress: InetSocketAddress,
        config: ServerKeyDatabase.Configuration,
    ): MutableList<PublicKey> = mutableListOf()

    override fun accept(
        connectAddress: String,
        remoteAddress: InetSocketAddress,
        serverKey: PublicKey,
        config: ServerKeyDatabase.Configuration,
        provider: CredentialsProvider?,
    ): Boolean {
        val host = connectAddress
        val fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
        val stored = readStoredFingerprint(host)
        return when {
            stored == null -> throw SshHostKeyFirstConnectException(host, fingerprint)
            stored == fingerprint -> true
            else -> throw SshHostKeyChangedException(host, stored, fingerprint)
        }
    }

    /**
     * Persist a fingerprint after the user has confirmed the first connect.
     * UI layer drives this when handling [SshHostKeyFirstConnectException].
     */
    fun accept(host: String, fingerprint: String) {
        // Keep the legacy "algoTag + base64 pub" columns empty — the
        // fingerprint column is the one we read back. Additional columns
        // keep the line format stable for forward compatibility.
        val line = "$host unknown - $fingerprint"
        knownHosts.appendText("$line\n")
    }

    /** Drop every record — exposed as "reset known hosts" in the UI. */
    fun reset() {
        knownHosts.writeText("")
    }

    private fun readStoredFingerprint(host: String): String? {
        if (!knownHosts.exists()) return null
        val line = knownHosts.readLines().firstOrNull { it.startsWith("$host ") }
            ?: return null
        val parts = line.split(' ')
        return parts.getOrNull(FP_FIELD_INDEX)
    }

    private companion object {
        const val FP_FIELD_INDEX = 3
    }
}
