# SimplyGit BUG 审查报告

| 字段 | 值 |
|------|-----|
| **审查日期** | 2026-05-03 |
| **审查模式** | 每日增量 + 用户发起的二次实证复核 + 原地修复 |
| **审查范围** | 基于 `git log --since="yesterday"` 命中的 `814df63 fix(app): 修复同步链路问题并优化首页交互` 的变更文件做完整五层扫描；对 `data/git`、`data/ssh`、`data/saf`、`data/sync`、`data/diagnostics`、`domain/usecase`、`runtime`、`di`、`notification`、`ui/home`、`ui/ssh`、`ui/browser`、`ui/conflict`、`ui/diff`、`ui/policy` 未变更文件做 Layer 1-2 抽样。复核时基线推进到当时 HEAD `2c88ad0 fix(sync): 修复后台同步与 SSH 认证链路`。 |
| **分支** | main |
| **修复状态** | BUG-001 / 002 / 003 / 005 / 006 已原地修复并编译通过；BUG-004 复核确认为误报（`2c88ad0` 已在 `y7b0` BUG-007 路径上修掉）；OBS-001 / OBS-002 保留观察。 |

---

## 执行摘要

本日内三份扫描报告的关系：

| 报告 | 模式 | BUG 数 | 当前状态 |
|------|------|--------|---------|
| `bug_report_20260503_snao.md` | 全仓扫描 | 9（P0×1 / P1×2 / P2×3 / P3×3） | `814df63` 修复 8 条（BUG-005 未修） |
| `bug_report_20260503_y7b0.md` | 全仓扫描（并行合并） | 8（P1×2 / P2×4 / P3×2） | `2c88ad0` 全部修复 |
| `bug_report_20260503_p16x.md`（本报告） | 每日增量 + 二次复核 + 修复 | 6 初报 → 1 误报 + 5 真 BUG | 5 条在本轮已当场修复 |

本次扫描识别并**当场修复**了 5 条确认 BUG（BUG-001 / 002 / 003 / 005 / 006），主要集中在：

- `HomeViewModel.runOp` 异常兜底缺失（手动 Git 操作失败路径）
- `FileTreeRepositoryImpl.statusByPath` 写入顺序与优先级表不一致（`DELETED` 枚举副作用）
- `ResolveConflictUseCase` 的 SSH push 首连 TOFU 透传
- `HomeScreen.AuthModeSection` 空密钥 SSH 切换盲区
- `ExportLogsUseCase` 不清理历史导出 zip

仍有一条平行报告遗留未修：`snao` BUG-005（`TofuServerKeyDatabase.lookup` 空列表）— P2/Medium，本次未触碰。

---

## 复核与修复逐条明细

### [BUG-001] `HomeViewModel.runOp` 在 `block()` 抛异常时 UI 永久卡死 ✅ 已修复

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | Medium |
| **影响范围** | 手动触发 Clone / Pull / Commit / Push。`block()` 若抛 Throwable（例如 `runWithAuth.credRepo.loadPatOnce()` 底层的 `EncryptedSharedPreferences.getString` 在 AndroidKeyStore 损坏时抛 `GeneralSecurityException`），异常直接逃出 `viewModelScope.launch`，`_uiState` 永远停在 `HomeUiState.Working` → 四个按钮全部灰掉，`sync_log` 行也永远 `endedAt=null`。用户必须杀进程才能恢复。 |
| **触发条件** | 任一 UseCase 在返回 `GitOpResult` 之前抛出 Throwable。实证失败链：AOSP `issuetracker.google.com/issues/164901018` 的 `AEADBadTagException` → ESP `getString(KEY_PAT, null)` 抛 → `runWithAuth` 无保护 → `CloneRepoUseCase.invoke` 上抛 → `block()` 抛到 `runOp` 的 launch 块 → 沉默到 `Thread.uncaughtExceptionHandler`。 |

**证据（复核后最终确认）**

- `ui/home/HomeViewModel.kt:334`（修复前）：`val result = block()` 无包裹。
- `domain/usecase/GitUseCases.kt:110-130` `runWithAuth`：`credRepo.loadPatOnce()` 裸调用，无 try/catch。
- `viewModelScope.launch` 默认无 `CoroutineExceptionHandler`，异常被 `SupervisorJob` 静默丢。

**根因**

R8 "UI/日志前的异常必须走显式分派 + sanitizer 兜底" 在 ViewModel 入口处漏兜底。`runOp` 假定 `block()` 永远返回 `GitOpResult`—— 这是 Domain 层的内部契约，Data 层底层（ESP / Room / Hilt）随时可能抛。

**修复（已提交）**

`ui/home/HomeViewModel.kt` `runOp`：用 try/catch 包裹 `block()`，保留 `CancellationException`，其它异常转成 `GitOpResult.Failure(op, t)` 走既有 sanitizer 管道：

```kotlin
val result = try {
    block()
} catch (ce: kotlinx.coroutines.CancellationException) {
    throw ce
} catch (t: Throwable) {
    GitOpResult.Failure(op, t)
}
```

下游 `finishManualLogFailure` 的 `else` 分支会把非白名单 `cause` 交给 `JGitExceptionSanitizer` 脱敏（上次 `snao` BUG-003 的成果），PAT 泄漏风险自动闭合。

**建议验证方式**

单元测试：mock `cloneRepo()` 抛 `FooException("ghp_leaked")`，断言 (1) `uiState` 最终转为 `HomeUiState.Error` 不再永远 Working；(2) `sync_log.errorMsg` 不含 `"ghp_"` 字符串。

**关联**：R8 / P11 / `snao` BUG-003 同源。

---

### [BUG-002] `FileTreeRepositoryImpl.statusByPath` 写入顺序降级状态 ✅ 已修复

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | High（复核升级，原 Medium） |
| **影响范围** | `RepoBrowserScreen` 状态圆点。常见混合状态下（"修改 + `git add`"、"删除 + `git rm`"）DB 里 `git_status` 被写成 STAGED，丢失 MODIFIED / DELETED 语义。 |
| **触发条件** | 同一路径同时出现在 JGit Status 两组独立比较层的集合里：`added/changed/removed`（HEAD↔index）与 `modified/missing`（index↔worktree）。**JGit 官方文档明确这两组集合不互斥**，Obsidian vault 常见的"先 add 再继续编辑"工作流必然触发。 |

**证据（复核后最终确认）**

`data/filetree/FileTreeRepositoryImpl.kt:79-94`（修复前）的写入顺序是 UNTRACKED(1) → MODIFIED(3) → DELETED(4) → STAGED(2) → STAGED(2) → STAGED(2) → CONFLICT(5)，与 `priority()` 表 `CONFLICT(5) > DELETED(4) > MODIFIED(3) > STAGED(2) > UNTRACKED(1) > CLEAN(0)` **不一致**：`added/changed/removed` 那三组 STAGED(2) 在最后写入时会覆盖前面的 MODIFIED(3) / DELETED(4)。

典型失败场景：
- `added + modified`：新文件 `git add` 后编辑 → 应为 MODIFIED，实际被覆盖为 STAGED
- `added + missing`：`git add` 后删除 → 应为 DELETED，实际被覆盖为 STAGED
- `changed + modified`：已跟踪文件 `git add` 后再改 → 应为 MODIFIED，实际覆盖为 STAGED

**根因**

R12 "新类型与分派表必须同 commit 闭环"：引入 `GitFileStatus.DELETED` 时同步了 `priority()` 表，但**没同步** `buildMap` 的覆盖顺序。`buildMap` 把 `put` 当"无条件写"使用，隐含的契约"后写 ≥ 先写优先级"从未被机械校验过。

**修复（已提交）**

不再依赖"写入顺序 = 优先级顺序"—— 改用显式 `higherPriority` 比较函数：

```kotlin
buildMap {
    fun upsert(path: String, candidate: GitFileStatus) {
        this[path] = higherPriority(this[path], candidate)
    }
    status.untracked.forEach { upsert(it, GitFileStatus.UNTRACKED) }
    status.added.forEach { upsert(it, GitFileStatus.STAGED) }
    status.changed.forEach { upsert(it, GitFileStatus.STAGED) }
    status.removed.forEach { upsert(it, GitFileStatus.STAGED) }
    status.modified.forEach { upsert(it, GitFileStatus.MODIFIED) }
    status.missing.forEach { upsert(it, GitFileStatus.DELETED) }
    status.conflicting.forEach { upsert(it, GitFileStatus.CONFLICT) }
}
```

未来再新增 `GitFileStatus` 值时只需更新 `priority()`，写入顺序不再相关 —— 机械化闭环。

**建议验证方式**

单元测试：mock `git.status()` 让 `foo.md` 同时出现在 `modified + changed`、`bar.md` 同时出现在 `missing + removed`，断言 rescan 后 `foo.md.git_status == "MODIFIED"` 且 `bar.md.git_status == "DELETED"`。

**关联**：R12 / P10 反模式变体。

---

### [BUG-003] `ResolveConflictUseCase` 的 push 吞 `SshHostKeyFirstConnectException` ✅ 已修复

| 字段 | 值 |
|------|-----|
| **等级** | P3（复核从 P2 降级 —— TOFU 异常最终可在下次 push 弹出，非永久卡死） |
| **置信度** | High |
| **影响范围** | 用户解决冲突并"提交 + 推送"；仓库**首次使用 SSH push** 时（clone 用 HTTPS、后切 SSH；或 SSH pull 通过但 push 需要重新握手），SSH 握手抛 TOFU → 被 catch-all 吞 → `pushOk=false` → UI 显示 "Push 失败" 但**没有任何入口可以确认 host key**。 |
| **触发条件** | SSH-bound 仓库 + 首次连接目标主机（或 `resetKnownHosts` 后）+ 从 `ConflictResolveScreen` 触发 resolve-and-push。 |

**证据（复核后最终确认）**

- `JGitExceptionSanitizer.mapException`（`data/git/JGitExceptionSanitizer.kt:138-149`）对 TOFU 做 bypass：`Result.failure(tofu)` 保留原异常。
- `GitRepositoryImpl.push.fold { onFailure = GitOpResult.Failure(GitOp.PUSH, it) }` 把 TOFU 包进 `Failure.cause`，**不抛异常**。
- 修复前的 `ResolveConflictUseCase.kt:142-154`：
  ```kotlin
  val pushOk: Boolean = try {
      val result = gitRepository.push(binding, identity.username, pat)
      result is GitOpResult.Success     // ← TOFU Failure 直接转 false，cause 被丢弃
  } catch (e: Throwable) { /* never reached for TOFU */ false }
  ```
- 主路径 `HomeViewModel.runOp:344-373` 里做得对（先检查 `result.cause as? SshHostKeyFirstConnectException`）；`ResolveConflictUseCase` 从未重现。

**根因**

SPEC §6.2 定义 TOFU 是**单一白名单** bypass 异常，所有涉及 push 的链路都应尊重它。`ResolveConflictUseCase` 的 push 失败处理沿用早期"Throwable → 布尔"的简化契约，未纳入 Iteration 3 新增的 TOFU 白名单 —— P10 反模式复发。

**修复（已提交）**

- `domain/model/ConflictFile.kt`：`ResolveResult` 新增 `NeedsHostKeyConfirmation(host, fingerprint, committedFiles, remainingSkipped)`。
- `domain/usecase/ResolveConflictUseCase.kt`：push 后不再折叠成布尔，而是先从 `(result as? Failure).cause` 提 TOFU；命中就写 `SyncResult.NETWORK_ERR` 审计行（`errorMsg="tofu:<host>"`）、返回 `NeedsHostKeyConfirmation`；否则继续原 truth table。
- `ui/conflict/ConflictResolveViewModel.kt`：注入 `SshKeyRepository`；新增 `confirmHostKey(host, fingerprint)` 调 `acceptHostKey` 后重跑 `submit()`。
- `ui/conflict/ConflictResolveUiState.kt`：`SubmissionResult` 新增 `HostKeyNeeded` 变体。
- `ui/conflict/ConflictResolveScreen.kt`：截获 `HostKeyNeeded` 并渲染专门的 TOFU `AlertDialog`（复用 `tofu_confirm_*` 字符串资源），确认走 `confirmHostKey`、拒绝走 `dismissResult`；`ResultDialog` 的 `when` 保留 `HostKeyNeeded` 穷尽分支以满足编译器。

**建议验证方式**

集成测试：mock SSHD 在首次 SSH push 时抛 TOFU；用户在 `ConflictResolveScreen` 点 "提交并推送"。期望：UiState 显示 `HostKeyNeeded`；UI 渲染 TOFU 对话框；确认后重跑 push 成功；`sync_log` 里有两条行（`NETWORK_ERR + tofu:<host>` 和 `CONFLICT_RESOLVED`）。

**关联**：R12 / P10 / P11。

---

### [BUG-004] `SshPassphraseCache.put` TTL 重入 ❌ 误报，已在 `2c88ad0` 修复

| 字段 | 值 |
|------|-----|
| **状态** | 复核后确认：本报告提交时代码已修复，初版基于**陈旧文件快照**（commit `814df63` 以前的内容）判错。HEAD `2c88ad0` 上 `SshPassphraseCache.kt` 已维护 `cleanupJobs: ConcurrentHashMap<String, Job>`；`put` 开头 `cleanupJobs.remove(keyId)?.cancel()` 取消旧 TTL；`remove(keyId)` / `clear()` 同样 cancel 对应 Job。这是平行报告 `y7b0` BUG-007 的修复成果。 |

本条保留在报告里以便历史对照，不计入 "本轮确认 BUG" 数。

---

### [BUG-005] `HomeScreen.AuthModeSection` 空密钥 SSH 切换盲区 ✅ 已修复

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | High |
| **影响范围** | 用户从未生成过 SSH 密钥，`authType == "PAT"`。点击 "SSH" radio → `onSubmitAuth` 被 `if (firstKey != null)` 守卫住不调用 → radio 状态不变；`bound.authType` 仍是 "PAT" → 下方 `if (bound.authType == "SSH")` 分支不进入 → "去创建 SSH 密钥" 按钮不显示。用户卡在"点了没反应"状态，需自行发现顶栏齿轮 → 设置 → SSH 密钥 的三级路径。 |
| **触发条件** | SSH 密钥列表为空 + 用户尝试切到 SSH。Phase 1-2 MVP 用户第一次尝试 SSH 几乎必然命中。 |

**证据（复核后最终确认）**

`ui/home/HomeScreen.kt:585-625`（修复前）：
```kotlin
AuthModeRadio(
    onClick = {
        val firstKey = sshKeys.firstOrNull()
        if (firstKey != null) { onSubmitAuth("SSH", firstKey.keyId) }
        // ← sshKeys 为空时：onClick 直接 return，不给用户任何反馈
    },
)
if (bound.authType == "SSH") {          // ← 空密钥时 authType 仍是 PAT，此 block 不进入
    if (sshKeys.isEmpty()) {
        OutlinedButton(onClick = onOpenSshKeys) { Text(...ssh_key_select_none) }
    }
    ...
}
```

即使用户能绕过 UI 发 intent，`HomeViewModel.submitAuthType:270` 的 `require(!id.isNullOrBlank())` 会抛 `IllegalArgumentException` 被 `runCatching` 吞掉，仍然 no-op。

**根因**

UI 的"空状态引导"和"触发切换的主动作"被耦合成"authType==SSH 时才显示引导"，导致空密钥场景下引导路径被自己遮盖。

**修复（已提交）**

`ui/home/HomeScreen.kt` `AuthModeSection`：
- SSH radio 的 `onClick`：空密钥时直接 `onOpenSshKeys()` 把用户带到 SSH 管理页。
- "去创建 SSH 密钥" 按钮从 `if (authType == "SSH")` 解耦：只要 `sshKeys.isEmpty()` 就显示，PAT 状态下也可见。
- 已选密钥列表仍然只在 `authType == "SSH" && sshKeys.isNotEmpty()` 时渲染。

**建议验证方式**

Compose UI test：`sshKeys = emptyList()`、`bound.authType = "PAT"`；模拟点击 "SSH" radio。断言：`onOpenSshKeys` 被调用，同时 "去创建 SSH 密钥" 按钮在 Composable tree 可见。

**关联**：P7（SPEC 声明挂载点与代码飘移）的 UI 侧变体。

---

### [BUG-006] `ExportLogsUseCase` 不清理历史导出 zip ✅ 已修复

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | High |
| **影响范围** | 每次从 `SyncAuditScreen` 导出都生成新 zip 到 `<filesDir>/exports/`，旧文件永不删除。一次/天 估算一年 365 个 zip × 数十 KB = 几十 MB 磁盘慢泄漏。zip 含已 sanitize 的 sync_log.json + 近 7 天 diagnostics-*.log —— **虽已去敏但仍是业务审计数据**，堆在 `filesDir/exports` 永不清理，对设备被取证或误 backup 场景扩大攻击面。 |
| **触发条件** | 用户手动触发导出日志 ≥ 2 次。 |

**证据（复核后最终确认）**

`domain/usecase/ExportLogsUseCase.kt:44-69`（修复前）：每次 `invoke()` 都新建 `simplygit-<ts>.zip`，没有清理任何历史文件。`DiagnosticsLogger` 自己有 7 天保留策略（`data/diagnostics/DiagnosticsLogger.kt:123-128`），但 export zip 没有对应策略。

**根因**

Iteration 2 G9 SPEC 描述了 "生成 zip 并通过 FileProvider 分享"，没有同时定义"保留多少份 zip"。属于"自研组件的自清理策略缺失"。

**修复（已提交）**

`domain/usecase/ExportLogsUseCase.kt` 在写完新 zip 后调用 `pruneOldExports(dir, keepFile = zipFile)`：遍历 `exports/` 下所有 `simplygit-*.zip`，删除 `lastModified < now - 7 days` 且不是刚写入的那个文件（防止时钟偏移时误删刚生成的 artefact）。阈值常量 `EXPORT_RETENTION_DAYS = 7` 与 `DiagnosticsLogger.RETENTION_DAYS` 对齐。

**建议验证方式**

单元测试：mock `Clock`，连续 10 次在递增时间点调 `invoke()`，断言 `exports/` 目录里的 zip 数量始终 ≤ retention window 覆盖的 zip 数。

**关联**：建议新增反模式"自研临时文件生产者必须配套消费者侧的 retention 清理"。

---

## 观察项（置信度 Low / 证据链未完全闭合）

### [OBS-001] `DiffRepositoryImpl.mapFailure` 用类名字符串分派

| 字段 | 值 |
|------|-----|
| **疑似等级** | P3 |
| **置信度** | Low |
| **位置** | `data/git/DiffRepositoryImpl.kt:320-329` |

`cls.contains("FileNotFound", ignoreCase = true)` 等。当前 `isMinifyEnabled = false`（`app/build.gradle.kts:26`），R8 未启用，类名保留，功能正常。保持观察：release 开启 minify 时需改为 `when (t) { is FileNotFoundException -> ..., ... }` 实例判型。

### [OBS-002] `SyncLogRepositoryImpl.observeRepoState(repoId)` 的 `repoId` 参数被忽略

| 字段 | 值 |
|------|-----|
| **疑似等级** | P4 |
| **置信度** | Low |
| **位置** | `data/sync/SyncLogRepositoryImpl.kt:40-41` |

`repoDao.observeFirst().map { it.toStateSnapshot(repoId) }` 只取第一行 repository（N4 单仓库假设），不按 `repoId` 过滤。N4 假设下无感；Phase 3+ 支持多仓库时必须重构。建议在 SPEC BOUNDARIES 显式记录"直到 Phase 3 才移除 N4"。

---

## 持续存在的上次报告问题

| 编号 | 标题 | 上次等级 | 当前状态 |
|------|------|----------|---------|
| `snao` BUG-005 | `TofuServerKeyDatabase.lookup()` 永远返回空列表 | P2 / Medium | 未修 —— 两次 commit + 本轮修复都未触碰 `TofuServerKeyDatabase.kt` |
| `snao` OBS-002 | `SyncPolicy.intervalMinutes` 数据层未做下限校验 | P3 / Low | 已缓解 —— `UpdateSyncPolicyUseCase` 有 `require(... in VALID_INTERVALS)` 保护，仅绕过 UseCase 才触发 |
| `snao` OBS-003 | `TofuServerKeyDatabase` 同 host 重复 accept 只读第一条 | P3 / Low | 未修 |

`snao` OBS-001（`SshKeyDataSource.readPublic` 对非标准换行定位失败）—— 复核后实际不存在：`persist()` 无条件追加 `"\n# PUBLIC-KEY #\n"` 分隔符，无论私钥是否以 `\n` 结尾，`indexOf("\n# PUBLIC-KEY #\n")` 都能匹配（分隔符本身以 `\n` 开头）。

---

## 编译验证

```
$ ./gradlew --offline --no-daemon --rerun-tasks compileDebugKotlin
...
> Task :app:compileDebugKotlin
w: .../ui/policy/SyncPolicyScreen.kt:89:26 'val LocalLifecycleOwner ...' is deprecated.
w: .../ui/ssh/SshKeyScreen.kt:90:13 'fun Divider ...' is deprecated.

BUILD SUCCESSFUL in 12s
15 actionable tasks: 15 executed
```

两条 warning 都是既有代码的 deprecation，不是本次修复引入。

---

## 趋势对比

| 指标 | `snao` v1 | `y7b0` 平行 | `p16x` v1 | `p16x` v2（修复后） |
|------|-----------|-------------|-----------|---------------------|
| BUG 总数 | 9 | 8 | 6 初报 | 5 真 BUG + 1 误报 |
| P0+P1 数 | 3 | 2 | 0 | 0 |
| P2 数 | 3 | 4 | 2 | 1 遗留（`snao` BUG-005） |
| P3 数 | 3 | 2 | 4 | 4（**全部已修**） |
| 观察项 | 3 | 2 | 2 | 2 |
| 高置信 | 6 | 5 | 3 | 4 |

**历史趋势总结**：两轮修复 commit（`814df63` + `2c88ad0`）合计清理 17 条 BUG；本轮再修 5 条。当前代码高危 BUG 清零，仅剩 TOFU 算法协商 / 同 host 重复 accept 两条 SSH 边角 P2/P3 有待未来 SSH 深度测试时处理。

---

## 本轮提炼的机械化规则候选

- **R13 候选黄金法则**："ViewModel 的 `viewModelScope.launch` 块必须把 `block()` 的异常以 `try/catch` 转为 UiState（错误态），不得让异常逃出 `SupervisorJob`。" —— 已在 `HomeViewModel.runOp` 修复点写入注释，建议沉淀到 `docs/retro/golden-rules.md`。
- **P12 候选反模式**："跨 `put` 调用用隐式写入顺序表达优先级 —— 新枚举值引入时必然会忘记同步更新写入顺序。" —— BUG-002 的根因教训；建议沉淀到 `docs/retro/patterns.md`。
- **P13 候选反模式**："白名单异常未沿调用链透传 —— sanitizer 在最上游做一次 bypass 不够，下游每一个'`Throwable → Boolean` / `Failure → UiError` 折叠'节点都必须同样 bypass。" —— BUG-003 的根因教训；建议沉淀。

---

## 审查摘要

| 维度 | 数据 |
|------|------|
| 扫描模式 | 每日增量 + 二次实证复核 + 原地修复 |
| 扫描范围 | 22 个变更文件完整五层扫描 + ~96 个未变更 Kt 文件 Layer 1-2 抽样 |
| 确认 BUG（修复） | P0: 0 / P1: 0 / P2: 0 / P3: 4（全部 ✅）/ P4: 0 — **BUG-001/002/003/005/006** 均已原地修复，`BUG-001` 原定 P2 下游涉及 P2 行为已在修复中一并解决 |
| 确认 BUG（遗留） | P2: 1（`snao` BUG-005 TofuServerKeyDatabase.lookup 空列表）/ P3: 1（`snao` OBS-003 同 host 多行 accept） |
| 误报 | BUG-004 SshPassphraseCache TTL —— 初版基于陈旧快照判错，HEAD 已由 `2c88ad0` 修好 |
| 观察项 | 2 条（OBS-001 / OBS-002，均为 Low 置信度） |
| 置信度分布 | 本轮修复的 5 条：High 4 / Medium 1 |
| 编译验证 | `./gradlew --offline --rerun-tasks compileDebugKotlin` 成功；仅有 2 条既有 deprecation warning |

### 已完成修复清单（按文件）

| 文件 | 改动要点 | 关联 BUG |
|------|---------|---------|
| `app/src/main/java/com/example/simplygit/ui/home/HomeViewModel.kt` | `runOp` 加 try/catch 兜底 | BUG-001 |
| `app/src/main/java/com/example/simplygit/data/filetree/FileTreeRepositoryImpl.kt` | `statusByPath` 用 `higherPriority` 机械化写入 | BUG-002 |
| `app/src/main/java/com/example/simplygit/domain/model/ConflictFile.kt` | `ResolveResult` 新增 `NeedsHostKeyConfirmation` | BUG-003 |
| `app/src/main/java/com/example/simplygit/domain/usecase/ResolveConflictUseCase.kt` | push 失败路径 peek TOFU 并透传 | BUG-003 |
| `app/src/main/java/com/example/simplygit/ui/conflict/ConflictResolveViewModel.kt` | 注入 `SshKeyRepository`；新增 `confirmHostKey` | BUG-003 |
| `app/src/main/java/com/example/simplygit/ui/conflict/ConflictResolveUiState.kt` | `SubmissionResult` 新增 `HostKeyNeeded` | BUG-003 |
| `app/src/main/java/com/example/simplygit/ui/conflict/ConflictResolveScreen.kt` | 截获 `HostKeyNeeded` 并渲染 TOFU AlertDialog | BUG-003 |
| `app/src/main/java/com/example/simplygit/ui/home/HomeScreen.kt` | `AuthModeSection` 空密钥 onClick 跳转 + 引导按钮解耦 | BUG-005 |
| `app/src/main/java/com/example/simplygit/domain/usecase/ExportLogsUseCase.kt` | 新增 `pruneOldExports` 7 天 retention | BUG-006 |

### 遗留观察名单

1. **`snao` BUG-005 / OBS-003** —— `TofuServerKeyDatabase.lookup` 空列表 + 同 host 多行 accept 只读第一条：需要存储 `base64(publicKey)` 才能实现完整 lookup + rotate 覆盖写。本次未修，因为非 P0/P1 且实际未有用户反馈触发。
2. **OBS-001** —— `DiffRepositoryImpl.mapFailure` 类名字符串分派：R8 未启用暂不触发，release 开启 minify 时必须改为实例判型。
3. **OBS-002** —— `observeRepoState(repoId)` 参数忽略：N4 单仓库假设下无感，Phase 3+ 必须重构。
