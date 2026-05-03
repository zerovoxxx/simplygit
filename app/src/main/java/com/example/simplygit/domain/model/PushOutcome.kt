package com.example.simplygit.domain.model

/**
 * Result of [com.example.simplygit.domain.repository.GitRepository.push]
 * (SPEC §6.1 Iteration 2 — fix for bug_report_20260503 "审计统计恒为 0").
 *
 * `commitsPushed` counts remote refs that were actually advanced by this
 * push (i.e. `RemoteRefUpdate.Status == OK`). Up-to-date refs do NOT
 * contribute, so a no-op push surfaces `commitsPushed = 0` in the audit
 * row — matching user intuition ("成功 / pushed 0" means "已是最新").
 *
 * Pure DTO: no JGit native references so it can safely cross the
 * Data → Domain → UI boundary.
 */
data class PushOutcome(
    val commitsPushed: Int,
)
