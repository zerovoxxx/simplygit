package com.example.simplygit.data.ssh

import android.content.Context
import com.example.simplygit.data.sync.RepositoryDao
import com.example.simplygit.di.IoDispatcher
import com.example.simplygit.domain.model.DeleteSshKeyOutcome
import com.example.simplygit.domain.model.SshKeyIndexEntry
import com.example.simplygit.domain.model.SshKeyPair
import com.example.simplygit.domain.repository.SshKeyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.time.Clock
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data-layer [SshKeyRepository] (SPEC §4.4.1 Iteration 3).
 *
 * Uses Apache MINA SSHD 2.13 for **every** key-format concern so both the
 * generated public key and the fingerprint are byte-identical to what
 * `ssh-keygen -y` / `ssh-keygen -l -E sha256` produces — that's the
 * invariant Spec §7.1 F4 / F5 depend on when the user pastes the key into
 * GitHub Deploy Keys.
 *
 * Key format flow (generate):
 *  1. Generate Ed25519 via `SecurityUtils.getKeyPairGenerator("EdDSA")`.
 *     `SecurityUtils` registers MINA's EdDSA provider lazily on first
 *     access, so this works even when the host JDK has no native EdDSA.
 *  2. Encode the **public** side through `OpenSSHKeyPairResourceWriter.writePublicKey`
 *     → canonical OpenSSH wire format (`ssh-ed25519 AAAAC3N... comment`).
 *  3. Encode the **private** side through `OpenSSHKeyPairResourceWriter.writePrivateKey`
 *     → `-----BEGIN OPENSSH PRIVATE KEY-----` block in the actual OpenSSH
 *     container (not PKCS#8). Optional passphrase routed through
 *     [OpenSSHKeyEncryptionContext] + bcrypt KDF.
 *  4. Compute fingerprint via `KeyUtils.getFingerPrint(SHA-256, pubKey)` —
 *     identical prefix ("SHA256:") to `ssh-keygen -l -E sha256`.
 *
 * Key format flow (import):
 *  - Parse with `SecurityUtils.loadKeyPairIdentities`, which delegates to
 *    `OpenSSHKeyPairResourceParser` internally. Malformed / non-OpenSSH
 *    content (e.g. PuTTY `.ppk`) raises [SshKeyFormatException];
 *  - Re-derive the matching public key from the parsed [KeyPair] so we
 *    **always** have a displayable public key for the user to paste into
 *    the Git host's Deploy Keys UI.
 *
 * If the MINA EdDSA provider fails to initialise (e.g. classpath reduced
 * via R8 in a future release), generation throws
 * [IllegalStateException] — we deliberately do NOT silently fall back to
 * RSA because that would confuse the SPEC §3.1.1 selection (Ed25519).
 */
@Singleton
internal class SshKeyRepositoryImpl @Inject constructor(
    private val dataSource: SshKeyDataSource,
    private val repositoryDao: RepositoryDao,
    private val passphraseCache: SshPassphraseCache,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
    @ApplicationContext private val context: Context,
) : SshKeyRepository {

    override fun observeIndex(): Flow<List<SshKeyIndexEntry>> = dataSource.observeIndex()

    override suspend fun generate(passphrase: CharArray?): SshKeyPair = withContext(io) {
        val kp = runCatching {
            SecurityUtils.getKeyPairGenerator(SSH_EDDSA_ALGO).generateKeyPair()
        }.getOrElse { t ->
            throw IllegalStateException(
                "Ed25519 KeyPairGenerator unavailable; SSH is unusable on this device",
                t,
            )
        }
        persistKeyPair(kp, passphrase)
    }

    override suspend fun import(
        privateKeyOpenssh: CharArray,
        passphrase: CharArray?,
    ): SshKeyPair = withContext(io) {
        val raw = String(privateKeyOpenssh)
        val rawBytes = raw.toByteArray(Charsets.UTF_8)

        val parsed = runCatching {
            val provider: FilePasswordProvider? = passphrase?.let { pw ->
                FilePasswordProvider.of(String(pw))
            }
            SecurityUtils.loadKeyPairIdentities(
                /* session = */ null,
                /* resourceKey = */ NamedResource.ofName("imported"),
                /* inputStream = */ rawBytes.inputStream(),
                /* provider = */ provider,
            )
        }.getOrElse { t -> throw SshKeyFormatException(t) }

        val kp = parsed?.firstOrNull() ?: throw SshKeyFormatException(
            IllegalArgumentException("no key pair parsed"),
        )

        val publicOpenssh = publicKeyToOpenssh(kp)
        val fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, kp.public)
        val keyId = "ssh_${UUID.randomUUID()}"

        // Persist the user's original private-key text verbatim so any
        // passphrase / comment the user already set is preserved. The
        // derived public key lives alongside it (see SshKeyDataSource
        // storage layout).
        dataSource.persist(
            keyId = keyId,
            privateKeyOpenssh = raw,
            publicKeyOpenssh = publicOpenssh,
            fingerprintSha256 = fingerprint,
            createdAt = clock.millis(),
        )

        // BUG-001 fix (bug_report_20260503_snao): if the imported key is
        // passphrase-protected, cache it under the freshly minted `keyId` so
        // the very next Git op can unlock it. Must happen BEFORE the wipe.
        passphrase?.takeIf { it.isNotEmpty() }?.let { passphraseCache.put(keyId, it) }

        Arrays.fill(privateKeyOpenssh, '\u0000')
        Arrays.fill(rawBytes, 0)
        passphrase?.let { Arrays.fill(it, '\u0000') }

        SshKeyPair(
            keyId = keyId,
            publicKeyOpenssh = publicOpenssh,
            fingerprintSha256 = fingerprint,
            privateKeyRef = raw.toCharArray(),
        )
    }

    override suspend fun exportPublic(keyId: String): String =
        dataSource.readPublic(keyId).orEmpty()

    override suspend fun delete(keyId: String): DeleteSshKeyOutcome {
        val repo = repositoryDao.findFirst()
        val references = if (repo?.authType == "SSH" && repo.authRef == keyId) {
            listOf(repo.id)
        } else emptyList()
        if (references.isNotEmpty()) return DeleteSshKeyOutcome.InUse(references)
        dataSource.delete(keyId)
        passphraseCache.remove(keyId)
        return DeleteSshKeyOutcome.Deleted
    }

    override suspend fun acceptHostKey(host: String, fingerprint: String) = withContext(io) {
        knownHostsDb().accept(host, fingerprint)
    }

    override suspend fun resetKnownHosts() = withContext(io) {
        knownHostsDb().reset()
    }

    /** Build a fresh TOFU DB view against the shared `filesDir/ssh/known_hosts`. */
    private fun knownHostsDb(): TofuServerKeyDatabase {
        val sshHome = File(context.filesDir, "ssh").apply { if (!exists()) mkdirs() }
        return TofuServerKeyDatabase(File(sshHome, "known_hosts"))
    }

    /**
     * Shared finalisation path: serialise [kp] in OpenSSH private + public
     * format, compute SHA-256 fingerprint, persist through
     * [SshKeyDataSource] and return the short-lived [SshKeyPair] handle.
     *
     * BUG-001 fix (bug_report_20260503_snao): if [passphrase] is non-empty we
     * stash a copy in [SshPassphraseCache] BEFORE wiping the caller's buffer
     * so the next `Git.open().pull()` can actually unlock the encrypted
     * private key. Without this the cache is always empty and MINA SSHD
     * reports "no more authentication methods available".
     */
    private suspend fun persistKeyPair(kp: KeyPair, passphrase: CharArray?): SshKeyPair {
        val keyId = "ssh_${UUID.randomUUID()}"
        val publicOpenssh = publicKeyToOpenssh(kp)
        val privateOpenssh = privateKeyToOpenssh(kp, passphrase)
        val fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, kp.public)

        dataSource.persist(
            keyId = keyId,
            privateKeyOpenssh = privateOpenssh,
            publicKeyOpenssh = publicOpenssh,
            fingerprintSha256 = fingerprint,
            createdAt = clock.millis(),
        )
        passphrase?.takeIf { it.isNotEmpty() }?.let { passphraseCache.put(keyId, it) }
        passphrase?.let { Arrays.fill(it, '\u0000') }
        return SshKeyPair(
            keyId = keyId,
            publicKeyOpenssh = publicOpenssh,
            fingerprintSha256 = fingerprint,
            privateKeyRef = privateOpenssh.toCharArray(),
        )
    }

    /**
     * Canonical OpenSSH public-key line: `ssh-ed25519 AAAA... simplygit-generated`.
     *
     * `writePublicKey` reads the algorithm name from the key (we never
     * hard-code `"ssh-ed25519"` / `"ssh-rsa"` strings) so future
     * algorithms keep working.
     */
    private fun publicKeyToOpenssh(kp: KeyPair): String {
        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(
            kp.public,
            /* comment = */ PUBLIC_KEY_COMMENT,
            /* stream = */ out,
        )
        // writePublicKey omits the trailing newline — that matches the
        // single-line format GitHub Deploy Keys expects.
        return out.toString(Charsets.UTF_8.name())
    }

    private fun privateKeyToOpenssh(kp: KeyPair, passphrase: CharArray?): String {
        val out = ByteArrayOutputStream()
        val encCtx = passphrase
            ?.takeIf { it.isNotEmpty() }
            ?.let { pw ->
                OpenSSHKeyEncryptionContext().apply {
                    password = String(pw)
                }
            }
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
            kp,
            /* comment = */ PUBLIC_KEY_COMMENT,
            /* options = */ encCtx,
            /* stream = */ out,
        )
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        /**
         * MINA SSHD's canonical name for Ed25519 inside its crypto provider
         * (see `SecurityUtils.EDDSA`). Matches OpenSSH's `ssh-ed25519`
         * wire type when encoded through `OpenSSHKeyPairResourceWriter`.
         */
        const val SSH_EDDSA_ALGO = "EdDSA"
        const val PUBLIC_KEY_COMMENT = "simplygit-generated"
    }
}
