package com.example.simplygit.data.ssh

import com.example.simplygit.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory SSH passphrase cache with a TTL-based wipe (SPEC §4.4.1 / R7).
 *
 * Wire:
 *  - Keyed by `keyId` (not by fingerprint so the caller can rotate keys
 *    without cache inversions).
 *  - Clean-up coroutine runs on the injected [ApplicationScope] — NEVER
 *    on a UI `rememberCoroutineScope` / `viewModelScope`; the cleanup
 *    semantic is "a user-space duty that must complete even if the UI
 *    disappears". Process termination zeroes the cache naturally.
 */
@Singleton
class SshPassphraseCache @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val cached = ConcurrentHashMap<String, CharArray>()
    private val cleanupJobs = ConcurrentHashMap<String, Job>()

    fun put(keyId: String, passphrase: CharArray) {
        cleanupJobs.remove(keyId)?.cancel()
        cached[keyId]?.let { Arrays.fill(it, '\u0000') }
        cached[keyId] = passphrase.copyOf()
        cleanupJobs[keyId] = appScope.launch {
            delay(TTL.toMillis())
            remove(keyId)
        }
    }

    fun get(keyId: String): CharArray? = cached[keyId]?.copyOf()

    fun remove(keyId: String) {
        cleanupJobs.remove(keyId)?.cancel()
        cached.remove(keyId)?.let { Arrays.fill(it, '\u0000') }
    }

    fun clear() {
        cleanupJobs.values.forEach { it.cancel() }
        cleanupJobs.clear()
        cached.values.forEach { Arrays.fill(it, '\u0000') }
        cached.clear()
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
