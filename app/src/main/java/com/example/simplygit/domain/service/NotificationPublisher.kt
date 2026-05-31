package com.example.simplygit.domain.service

import com.example.simplygit.domain.model.ConflictClass

/**
 * Notification abstraction (SPEC §6.2 / §4.5 Iteration 2).
 *
 * Concrete implementation lives in the `notification` package. Callers pass
 * the repoId so deep-link intents can route back to the audit page for that
 * specific repo (N4: only 1 repo in Iteration 2).
 */
interface NotificationPublisher {
    fun publishConflict(repoId: Long, kind: ConflictClass)
    fun publishAuthFailed(repoId: Long)
    fun publishFsPermissionLost(repoId: Long)
    fun publishNetworkBroken(repoId: Long)
    fun publishLowPriority(msg: String)
}
