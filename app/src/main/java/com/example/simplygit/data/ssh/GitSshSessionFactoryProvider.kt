package com.example.simplygit.data.ssh

import android.content.Context
import com.example.simplygit.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a fully-wired [SshdSessionFactory] per SSH-bound repository
 * (SPEC §4.4.2 Iteration 3, P0-1 closure).
 *
 * Wiring:
 *  - [SshdSessionFactoryBuilder.setDefaultKeysProvider] loads **only** the
 *    private key referenced by this repo's `authRef` (= "ssh_&lt;keyId&gt;"),
 *    decrypted through [SshKeyDataSource]. SSHD's default file-scan of
 *    `~/.ssh/id_*` is therefore bypassed — we never rely on plaintext keys
 *    on disk.
 *  - [SshdSessionFactoryBuilder.setKeyPasswordProvider] routes passphrase
 *    prompts through [SshPassphraseCache]; empty passphrase → caller
 *    retries the same key with an empty array so SSHD stops asking.
 *  - [SshdSessionFactoryBuilder.setServerKeyDatabase] installs
 *    [TofuServerKeyDatabase] so first-connect raises
 *    [SshHostKeyFirstConnectException] and mismatched fingerprints raise
 *    [SshHostKeyChangedException].
 *  - [SshdSessionFactoryBuilder.setPreferredAuthentications] = `publickey`
 *    so SSHD never falls back to password auth (GitHub / GitLab rejects it
 *    anyway; keeping the list tight surfaces errors faster).
 */
@Singleton
internal class GitSshSessionFactoryProvider @Inject constructor(
    private val sshKeyDataSource: SshKeyDataSource,
    private val passphraseCache: SshPassphraseCache,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Build the factory for the given [repoId] / [keyId]. The returned
     * factory is **short-lived** — JGit's `TransportCommand` holds it for
     * the duration of one clone/pull/push. Safe to call on any thread.
     */
    fun buildFactory(repoId: Long, keyId: String): SshdSessionFactory {
        val sshHome = File(context.filesDir, "ssh").apply { if (!exists()) mkdirs() }

        return SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(sshHome)
            .setSshDirectory(sshHome)
            .setDefaultKeysProvider { _ -> loadKeyPairs(keyId) }
            .setKeyPasswordProvider { _ -> CachedKeyPasswordProvider(keyId, passphraseCache) }
            .setServerKeyDatabase { _, ssh ->
                TofuServerKeyDatabase(File(ssh, "known_hosts"))
            }
            .build(null)
    }

    /**
     * Decrypt the private key stored under `filesDir/ssh/<keyId>.enc`,
     * parse it into a [KeyPair] via MINA SSHD, and return a single-element
     * iterable to JGit.
     *
     * `runBlocking` is intentional here: JGit's `Function<File, Iterable<KeyPair>>`
     * contract is synchronous and invoked from an SSHD worker thread, not
     * the main thread — it never runs on a UI dispatcher.
     *
     * Any failure degrades to an empty iterable so SSHD reports "no
     * matching auth method" instead of leaking a throwable. The root-cause
     * failure is already covered by the higher-level sanitizer when the
     * transport call unwinds.
     */
    private fun loadKeyPairs(keyId: String): Iterable<KeyPair> {
        val privateKey = runBlocking(io) { sshKeyDataSource.readPrivate(keyId) }
            ?: return emptyList()
        val pass = passphraseCache.get(keyId)
        return try {
            val provider: FilePasswordProvider? = pass?.let {
                FilePasswordProvider.of(String(it))
            }
            val bytes = String(privateKey).toByteArray(Charsets.UTF_8)
            val parsed = SecurityUtils.loadKeyPairIdentities(
                /* session = */ null,
                /* resourceKey = */ NamedResource.ofName(keyId),
                /* inputStream = */ bytes.inputStream(),
                /* provider = */ provider,
            )
            parsed?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        } finally {
            java.util.Arrays.fill(privateKey, '\u0000')
            pass?.let { java.util.Arrays.fill(it, '\u0000') }
        }
    }

    /**
     * Bridges [SshPassphraseCache] into JGit's [KeyPasswordProvider] ABI.
     *
     * The conversion `CharArray → String` is a deliberate [R3] exception:
     * JGit's `KeyPasswordProvider.getPassphrase` contract returns
     * `char[]`, so we can in fact hand back an unmodified copy. The
     * String conversion is only used internally by MINA SSHD's own
     * `FilePasswordProvider` when we go through that path — that's a
     * separate code site in [loadKeyPairs] above.
     */
    private class CachedKeyPasswordProvider(
        private val keyId: String,
        private val cache: SshPassphraseCache,
    ) : KeyPasswordProvider {
        private var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
        override fun getPassphrase(uri: URIish?, attempt: Int): CharArray =
            cache.get(keyId) ?: CharArray(0)

        override fun setAttempts(maxNumberOfAttempts: Int) {
            maxAttempts = maxNumberOfAttempts
        }

        override fun getAttempts(): Int = maxAttempts

        override fun keyLoaded(
            uri: URIish?,
            attempt: Int,
            error: Exception?,
        ): Boolean = false // never retry — caller surfaces the error upstream

        private companion object {
            const val DEFAULT_MAX_ATTEMPTS = 1
        }
    }
}
