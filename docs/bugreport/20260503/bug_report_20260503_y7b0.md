# SimplyGit BUG 审查报告

| 字段 | 值 |
|------|-----|
| **审查日期** | 2026-05-03 |
| **审查模式** | 全仓扫描（并行合并） |
| **审查范围** | `app/src/main/java/com/example/simplygit/` 全部生产 Kotlin 代码（118 个文件），覆盖 `data/git`、`data/ssh`、`data/saf`、`data/binding`、`data/credential`、`data/sync`、`domain/usecase`、`domain/repository`、`domain/model`、`runtime`、`notification`、`ui`、`di`，并抽查 `AndroidManifest.xml` / Gradle 配置。 |
| **分支** | main |

---

## Tier 1 — 主链路 / Git 同步 / SSH

### [BUG-001] 后台同步忽略 `push()` 失败并记录 OK，可能造成“备份成功”假象

| 字段 | 值 |
|------|-----|
| **等级** | P1 |
| **置信度** | High |
| **影响范围** | 后台静默同步链路；远端推送失败、认证失败、远端拒绝时，审计日志和 Worker 结果仍可能显示成功。 |
| **触发条件** | `pullAndClassify` 与 `commitAllIfDirty` 成功，但 `gitRepo.push(...)` 返回 `GitOpResult.Failure`。 |

**证据**

`RunSyncUseCase` 第 160-175 行调用 `push` 后丢弃返回值，随后直接写 `SyncResult.OK` 并返回 `RunSyncOutcome.Ok`：

```160:175:app/src/main/java/com/example/simplygit/domain/usecase/RunSyncUseCase.kt
// (7) Push.
gitRepo.push(binding, identity.username, pat)

// (8) Persist + prune.
val endedAt = Instant.now(clock)
syncLogRepo.finishLog(
    logId = logId,
    result = SyncResult.OK,
    endedAt = endedAt,
    commitsPulled = pullResult.commitsPulled,
    commitsPushed = if (commit != null) 1 else 0,
    filesChanged = commit?.filesChanged ?: 0,
)
syncLogRepo.updateSyncState(repoId, SyncState.IDLE)
syncLogRepo.pruneExpired(endedAt)
RunSyncOutcome.Ok
```

而 `GitRepository.push` 的契约返回 `GitOpResult`，实现会把 JGit push 失败折叠成 `GitOpResult.Failure`，不会抛出：

```44:49:app/src/main/java/com/example/simplygit/data/git/GitRepositoryImpl.kt
override suspend fun push(binding: RepoBinding, username: String, pat: CharArray): GitOpResult =
    jgit.push(File(binding.localAbsPath), username, pat)
        .fold(
            onSuccess = { GitOpResult.Success },
            onFailure = { GitOpResult.Failure(GitOp.PUSH, it) },
        )
```

**根因**

`RunSyncUseCase` 混用了两套错误模型：`pullAndClassify` / `commitAllIfDirty` 失败通过 `getOrThrow()` 抛出 `SanitizedGitException`，但 `push` 仍是旧的 `GitOpResult` 返回值契约。调用方按“抛异常式”处理了“结果式”接口，形成返回值假成功。

**修复建议**

最小修复：在 `RunSyncUseCase.invoke` 第 160 行后显式检查 `pushResult`：

1. `GitOpResult.Success` 才进入 OK 分支；
2. `GitOpResult.Failure` 若 `cause is SanitizedGitException` 则复用 `handleSanitized(repoId, logId, cause)`；否则先走 `jgitExceptionSanitizer.sanitize(cause)`；
3. `SuccessWithPayload` 对 `PUSH` 视为契约异常，转 `Unknown`。

中期建议：为后台链路新增 `pushOrThrow(...)` 或把 `push` 统一迁移到 `Result<Unit>` / throw-style，避免同一 Repository 同时存在两套失败语义。

**建议验证方式**

单元测试 mock `gitRepo.push` 返回 `GitOpResult.Failure(GitOp.PUSH, SanitizedGitException(..., SyncErrorKind.Auth))`，断言：

- `syncLogRepo.finishLog` 不写 `SyncResult.OK`；
- 状态转为 `PAUSED_AUTH`；
- `RunSyncOutcome.PausedAuth`。

**关联**

- 反模式：返回值假成功。
- 黄金法则 R12：新/旧错误契约必须在同一调用链闭环。

---

### [BUG-002] SSH 仓库仍强制依赖 PAT，SSH-only 用户无法手动或后台同步

| 字段 | 值 |
|------|-----|
| **等级** | P1 |
| **置信度** | High |
| **影响范围** | 所有只配置 SSH key、未保存 GitHub PAT 的仓库；手动 Clone/Pull/Push 与后台同步都会提前失败。 |
| **触发条件** | `RepoBinding.authType == "SSH"` 且 `CredentialRepository.loadPatOnce()` 返回 `null`。 |

**证据**

模型已经支持 SSH 绑定：

```15:22:app/src/main/java/com/example/simplygit/domain/model/RepoBinding.kt
data class RepoBinding(
    val treeUri: String,
    val localAbsPath: String,
    val remoteUrl: String,
    val id: Long = 0L,
    val authType: String = "PAT",
    val authRef: String = "github_pat",
)
```

`JGitDataSource` 的注释与实现也明确 SSH 模式应传 `CharArray(0)` 占位，真正凭证由 `authRef` 对应的 SSH key 提供：

```197:219:app/src/main/java/com/example/simplygit/data/git/JGitDataSource.kt
/**
 * SPEC §4.4.2 (P0-1 closure): dispatch on `binding.authType`. Signature
 * stays PAT-shaped so callers don't need to branch — SSH-bound repos
 * pass `CharArray(0)` for [pat] and this method ignores it.
 */
private suspend fun applyAuth(
    cmd: TransportCommand<*, *>,
    username: String,
    pat: CharArray,
) {
    val binding = bindingRepository.currentOrNull()
    when (val authType = binding?.authType) {
        null, "PAT" -> cmd.setCredentialsProvider(
            UsernamePasswordCredentialsProvider(username, pat),
        )
        "SSH" -> cmd.setTransportConfigCallback { transport ->
            (transport as? SshTransport)?.sshSessionFactory =
                sshSessionFactoryProvider.buildFactory(binding.id, binding.authRef)
        }
```

但手动 Git UseCase 一律先调用 `runWithPat`，没 PAT 直接返回 `MissingCredentialException`，不会进入 SSH 分支：

```110:121:app/src/main/java/com/example/simplygit/domain/usecase/GitUseCases.kt
private suspend inline fun runWithPat(
    credRepo: CredentialRepository,
    op: GitOp,
    block: (pat: CharArray) -> GitOpResult,
): GitOpResult {
    val pat = credRepo.loadPatOnce()
        ?: return GitOpResult.Failure(op, MissingCredentialException())
    return try {
        block(pat)
    } finally {
        Arrays.fill(pat, '\u0000')
    }
}
```

后台同步也同样强制 `loadPatOnce()`：

```112:134:app/src/main/java/com/example/simplygit/domain/usecase/RunSyncUseCase.kt
// (4) Identity + PAT.
val identity = credRepo.snapshotIdentity() ?: run {
    ...
    return RunSyncOutcome.MissingCredential
}
pat = credRepo.loadPatOnce() ?: run {
    syncLogRepo.pauseAndFinish(
        repoId = repoId,
        logId = logId,
        state = SyncState.PAUSED_AUTH,
        result = SyncResult.AUTH_ERR,
        endedAt = Instant.now(clock),
    )
    notifier.publishAuthFailed(repoId)
    return RunSyncOutcome.MissingCredential
}
```

**根因**

Iteration 3 把 Data 层改成 `authType` 分发，但 Domain UseCase 仍保留 PAT-era 的 preflight 模板，导致 SSH 分支在到达 `JGitDataSource.applyAuth` 前就被拦截。

**修复建议**

1. 在 `GitOpPreflight.Ready` 中暴露 `binding.authType` / `authRef`；
2. 将 `runWithPat` 改为 `runWithAuth(binding, op)`：
   - `PAT`：保持现状，必须 `loadPatOnce()`；
   - `SSH`：使用 `CharArray(0)` 占位，仍需要 identity 的 `username/email` 作为 commit author；若当前 identity 只依赖 PAT 保存流程，则应新增独立的 Git author 配置；
3. `RunSyncUseCase` 同样按 `binding.authType` 分支，SSH 不再因缺 PAT 进入 `PAUSED_AUTH`。

**建议验证方式**

- 手动测试：仅生成/导入 SSH key + 绑定 SSH remote，不保存 PAT，执行 Clone/Pull/Push。
- 单元测试：`binding.authType = "SSH"`、`credRepo.loadPatOnce() = null` 时，断言 `gitRepo.push(..., CharArray(0))` 被调用。

**关联**

- 反模式 P12：跨层标识符/协议分支扩展后读取端未同步。
- 黄金法则 R12：新分支必须同 commit 接入所有分派表。

---

### [BUG-003] 后台同步缺少 `RUNNING` 并发闸门，Periodic 与 Catch-up 可能同时操作同一仓库

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | High |
| **影响范围** | WorkManager 周期任务与冷启动 catch-up 重叠、或未来新增手动后台触发时；可能出现 `index.lock`、重复提交、状态覆盖、审计行交错。 |
| **触发条件** | 一个 `GitSyncWorker` 已把仓库状态置为 `RUNNING`，另一个 worker 在第 74-79 行只检查 `PAUSED_STATES` 后继续启动。 |

**证据**

`RUNNING` 不是暂停态，`RunSyncUseCase` 只短路 `PAUSED_STATES`：

```74:83:app/src/main/java/com/example/simplygit/domain/usecase/RunSyncUseCase.kt
val snapshot = syncLogRepo.loadRepoState(repoId)

// (1) Pause gate.
if (snapshot.syncState in PAUSED_STATES) {
    return RunSyncOutcome.SkippedPaused(snapshot.syncState)
}

val now = Instant.now(clock)
val logId = syncLogRepo.startLog(repoId, trigger, now)
syncLogRepo.updateSyncState(repoId, SyncState.RUNNING)
```

状态机包含 `RUNNING`，但短路集合不包含它：

```19:34:app/src/main/java/com/example/simplygit/domain/model/SyncState.kt
enum class SyncState {
    IDLE,
    RUNNING,
    PAUSED_CONFLICT,
    PAUSED_AUTH,
    PAUSED_FS,
    BROKEN,
}

/** States that short-circuit the worker until the user explicitly resumes. */
val PAUSED_STATES: Set<SyncState> = setOf(
    SyncState.PAUSED_CONFLICT,
    SyncState.PAUSED_AUTH,
    SyncState.PAUSED_FS,
    SyncState.BROKEN,
)
```

调度器使用两个不同 unique work 名称，不能互斥：

```58:77:app/src/main/java/com/example/simplygit/runtime/SyncSchedulerImpl.kt
wm.enqueueUniquePeriodicWork(
    WorkTags.UNIQUE_PERIODIC,
    ExistingPeriodicWorkPolicy.UPDATE,
    request,
)
...
wm.enqueueUniqueWork(
    WorkTags.UNIQUE_CATCHUP,
    ExistingWorkPolicy.KEEP,
    request,
)
```

**根因**

`RUNNING` 被建模为状态机状态，但没有原子 compare-and-set 语义。`loadRepoState` 与 `updateSyncState(RUNNING)` 分离，两个 worker 可同时读到 `IDLE` 并同时进入 JGit 操作。

**修复建议**

最小修复：在 `RunSyncUseCase` 中对 `SyncState.RUNNING` 直接返回 `SkippedPaused` 或新增 `RunSyncOutcome.SkippedRunning`。

更稳妥方案：在 `SyncLogRepository` / DAO 增加原子 `tryMarkRunning(repoId): Boolean`：

```sql
UPDATE repository SET sync_state='RUNNING'
WHERE id=:repoId AND sync_state='IDLE'
```

返回 1 才允许启动；否则跳过。

**建议验证方式**

并发单元测试：并行启动两个 `runSync(SyncTrigger.CATCHUP/PERIODIC)`，mock `gitRepo.pullAndClassify` 加延迟，断言只有一次进入 Git 调用，另一条审计不应写 `RUNNING` 主链路。

**关联**

- BUG 模式：竞态条件 / 异步状态收敛。
- 架构规则：后台任务只走 WorkManager，但跨 unique work 仍需业务互斥。

---

## Tier 2 — UI / 权限 / 安全边界

### [BUG-004] Android 13+ 通知权限首次请求被误判为“永久拒绝”，首次保存策略可能不弹授权框

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | High |
| **影响范围** | Android 13+ 首次安装用户；后台冲突、认证失败、文件权限丢失等通知可能无法送达。 |
| **触发条件** | 用户从未请求过 `POST_NOTIFICATIONS`，进入同步策略页保存非手动策略。 |

**证据**

`isPermanentlyDenied` 仅根据 `!shouldShowRequestPermissionRationale` 判断永久拒绝：

```50:57:app/src/main/java/com/example/simplygit/notification/NotificationPermissionHelper.kt
fun isPermanentlyDenied(activity: Activity): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (isGranted(activity)) return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}
```

保存按钮遇到 `permanentlyDenied` 会直接打开系统设置，不调用 runtime permission launcher：

```216:227:app/src/main/java/com/example/simplygit/ui/policy/SyncPolicyScreen.kt
if (state.intervalMinutes != SyncPolicyModel.MANUAL_ONLY &&
    !NotificationPermissionHelper.isGranted(context)
) {
    val perm = NotificationPermissionHelper.permissionIfNeeded()
    val permanentlyDenied = activity?.let {
        NotificationPermissionHelper.isPermanentlyDenied(it)
    } ?: false
    when {
        perm != null && !permanentlyDenied -> permissionLauncher.launch(perm)
        permanentlyDenied -> context.openAppNotificationSettings()
        // else: pre-A13 — permission is implicit.
    }
}
```

**根因**

Android 权限 API 中，“从未请求过”和“永久拒绝/不再询问”都可能让 `shouldShowRequestPermissionRationale` 返回 `false`。当前代码没有记录“是否已经请求过”，导致首次请求被混淆为永久拒绝。

**修复建议**

增加 `notificationPermissionRequested` 持久状态（DataStore 或 policy UI state）：

- 未请求过：始终 `permissionLauncher.launch(POST_NOTIFICATIONS)`；
- 请求过且未授权且 `rationale=false`：才判定永久拒绝并跳系统设置；
- launcher 回调后写入“已请求”。

**建议验证方式**

Android 13+ 模拟器 fresh install，保存 15 分钟策略：期望先弹系统权限框，而不是直接跳系统设置。

**关联**

- BUG 模式：输入/状态边界未区分。

---

### [BUG-005] SAF 持久授权失败被吞掉，仍继续保存 Vault 绑定

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | Medium |
| **影响范围** | 文档树授权异常、ROM 行为差异、权限 flag 不完整时；用户看到 Vault 已绑定，但下次后台同步进入 `PAUSED_FS`。 |
| **触发条件** | `takePersistableUriPermission(uri, flags)` 抛 `SecurityException` 或其他异常。 |

**证据**

UI 侧吞掉持久授权异常后仍调用 `PickVault`：

```89:98:app/src/main/java/com/example/simplygit/ui/home/HomeScreen.kt
val pickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree()
) { uri: Uri? ->
    if (uri != null) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
        viewModel.onIntent(HomeIntent.PickVault(uri))
    }
}
```

而 UseCase 明确假设 UI 已经完成持久授权：

```19:23:app/src/main/java/com/example/simplygit/domain/usecase/BindRepoUseCase.kt
/**
 * Resolves a newly granted SAF tree URI and persists the binding iff the path is
 * JGit-compatible. UI layer is expected to have called
 * [android.content.ContentResolver.takePersistableUriPermission] before invoking this.
 */
```

后台同步后续会检查持久授权并暂停：

```87:99:app/src/main/java/com/example/simplygit/domain/usecase/RunSyncUseCase.kt
// (2) SAF permission self-check.
val treeUri = runCatching { Uri.parse(binding.treeUri) }.getOrNull()
if (treeUri == null || !safPathResolver.hasPersistedPermission(treeUri)) {
    syncLogRepo.pauseAndFinish(
        repoId = repoId,
        logId = logId,
        state = SyncState.PAUSED_FS,
        result = SyncResult.FS_ERR,
        endedAt = Instant.now(clock),
    )
    notifier.publishFsPermissionLost(repoId)
    return RunSyncOutcome.PausedFs
}
```

**根因**

授权持久化是绑定成功的前置条件，但 UI 用 `runCatching { ... }` 忽略了失败，Domain 层又没有二次校验“权限已持久化”。两层契约之间出现空洞。

**修复建议**

- UI：`takePersistableUriPermission` 失败时不要调用 `PickVault`，改为显示可恢复错误；
- Domain：`BindVaultUseCase` 可额外调用 `resolver.hasPersistedPermission(treeUri)`，不满足则返回新增 `BindVaultOutcome.PermissionNotPersisted`；
- 记录失败类型到 diagnostics，便于定位 ROM 差异。

**建议验证方式**

用 fake `ContentResolver` / 包装器让 `takePersistableUriPermission` 抛异常，断言不会调用 `bindingRepo.saveVault`。

**关联**

- 黄金法则：现代 Android 合规优先，文件访问只走 SAF 且必须保证持久授权。

---

### [BUG-006] SSH 认证失败未归类为 Auth，后台同步会走 Unknown/BROKEN 而非 PAUSED_AUTH

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | Medium |
| **影响范围** | SSH key 未加入 GitHub、key 被删除、passphrase 错误/过期、`permission denied (publickey)` 等常见 SSH 认证失败。 |
| **触发条件** | JGit/SSHD 抛 `TransportException`，message 包含 SSH 常见认证失败文本，但不包含 `401` / `403` / `not authorized`。 |

**证据**

Sanitizer 对 `TransportException` 的 Auth 分类只识别 HTTPS/PAT 常见文本：

```81:89:app/src/main/java/com/example/simplygit/data/git/JGitExceptionSanitizer.kt
is TransportException -> {
    val msg = current.message.orEmpty()
    if (msg.contains("not authorized", ignoreCase = true) ||
        msg.contains("401") ||
        msg.contains("403")
    ) {
        return SyncErrorKind.Auth
    }
}
```

SSH key 读取失败会降级为空 key 列表，最终上游通常只看到 SSHD 的认证失败：

```99:109:app/src/main/java/com/example/simplygit/data/ssh/GitSshSessionFactoryProvider.kt
} catch (t: Throwable) {
    android.util.Log.w(
        "SimplyGit.SSH",
        "loadKeyPairs failed keyId=$keyId passPresent=${pass != null} type=${t.javaClass.simpleName}",
    )
    emptyList()
}
```

`RunSyncUseCase` 只有 `SyncErrorKind.Auth` 才进入 `PAUSED_AUTH` 并通知用户；`Unknown` 会按 transient 失败计数，三次后进 `BROKEN`：

```194:229:app/src/main/java/com/example/simplygit/domain/usecase/RunSyncUseCase.kt
private suspend fun handleSanitized(
    repoId: Long,
    logId: Long,
    e: SanitizedGitException,
): RunSyncOutcome = when (e.kind) {
    SyncErrorKind.Auth -> {
        syncLogRepo.pauseAndFinish(
            repoId = repoId,
            logId = logId,
            state = SyncState.PAUSED_AUTH,
            result = SyncResult.AUTH_ERR,
...
    SyncErrorKind.InvalidState,
    SyncErrorKind.Unknown,
    -> finishTransient(
        repoId = repoId,
        logId = logId,
        result = SyncResult.ABORTED,
```

**根因**

SSH 接入新增了认证失败形态，但异常分类表仍主要面向 HTTPS/PAT。认证类错误被误分到 Unknown，导致用户无法得到“凭证/SSH key 需要处理”的直接提示。

**修复建议**

在 `classifyKind` 中补充 SSH auth 文本/异常类型白名单，例如：

- `permission denied`；
- `publickey`；
- `no more authentication methods`；
- `auth fail`；
- 私钥解密失败/ passphrase required 相关异常。

同时为 `JGitExceptionSanitizer.classifyKind` 增加参数化单测。

**建议验证方式**

构造 `TransportException("git@github.com: Permission denied (publickey).")`，断言 `sanitize(...).kind == SyncErrorKind.Auth`。

**关联**

- 反模式 P10：新增异常/新枚举定义后未闭环到分派表。
- 黄金法则 R12。

---

## Tier 3 — 生命周期 / 低频隐患

### [BUG-007] `SshPassphraseCache.put` 的旧 TTL 清理任务会误删新 passphrase

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | High |
| **影响范围** | 用户在 10 分钟 TTL 内重复导入/生成同一 keyId 或未来 UI 提供“重新输入 passphrase”能力时，新 passphrase 可能提前失效。 |
| **触发条件** | 同一 `keyId` 连续调用 `put`：第一次 `put` 的延迟清理协程尚未执行，第二次写入了新值。 |

**证据**

每次 `put` 都启动一个无版本检查的延迟删除任务：

```30:36:app/src/main/java/com/example/simplygit/data/ssh/SshPassphraseCache.kt
fun put(keyId: String, passphrase: CharArray) {
    cached[keyId]?.let { Arrays.fill(it, '\u0000') }
    cached[keyId] = passphrase.copyOf()
    appScope.launch {
        delay(TTL.toMillis())
        cached.remove(keyId)?.let { Arrays.fill(it, '\u0000') }
    }
}
```

删除 SSH key 时也只删持久 key，不清 passphrase cache：

```148:155:app/src/main/java/com/example/simplygit/data/ssh/SshKeyRepositoryImpl.kt
override suspend fun delete(keyId: String): DeleteSshKeyOutcome {
    val repo = repositoryDao.findFirst()
    val references = if (repo?.authType == "SSH" && repo.authRef == keyId) {
        listOf(repo.id)
    } else emptyList()
    if (references.isNotEmpty()) return DeleteSshKeyOutcome.InUse(references)
    dataSource.delete(keyId)
    return DeleteSshKeyOutcome.Deleted
}
```

**根因**

TTL 清理任务没有和具体缓存值绑定；旧任务只按 key 删除，无法判断当前 map 中的值是否由自己写入。删除 key 时也没有调用 cache 层清理。

**修复建议**

- `put` 时生成版本号 / token，延迟清理前比较 token 一致才 remove；
- 或保存 `Job`，新 `put` 先 cancel 旧 job；
- 增加 `SshPassphraseCache.remove(keyId)`，`SshKeyRepositoryImpl.delete` 成功后调用。

**建议验证方式**

单元测试使用 `TestScope`：`put(key, old)`，推进 9 分钟，`put(key, new)`，再推进 1 分钟，断言 `get(key)` 仍返回 new。

**关联**

- BUG 模式：异步清理竞态 / 生命周期。
- 黄金法则 R7：安全清理必须与生命周期正确绑定；同时不能误删新值。

---

### [BUG-008] `NAV_RESUME` 通知 deep link 不会导航到 Home，恢复入口可能不可见

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | Medium |
| **影响范围** | 用户从通知点击“恢复同步”时，如果当前停留在审计、策略、SSH key、Diff、冲突页，可能看不到 Home banner 上的 Resume 入口。 |
| **触发条件** | Activity 已存在且当前 NavController 不在 `Routes.HOME`，收到 `NotificationPublisherImpl.NAV_RESUME`。 |

**证据**

`NAV_RESUME` 分支直接 `Unit`，只在注释里假设“stay on Home”：

```120:130:app/src/main/java/com/example/simplygit/ui/MainActivity.kt
androidx.compose.runtime.LaunchedEffect(pendingNav, pendingNavRepoId) {
    when (pendingNav) {
        NotificationPublisherImpl.NAV_AUDIT -> navController.navigate(Routes.AUDIT)
        NotificationPublisherImpl.NAV_RESUME -> Unit // stay on Home; banner surfaces the "Resume" button.
        NotificationPublisherImpl.NAV_CONFLICT -> {
            val repoId = pendingNavRepoId ?: return@LaunchedEffect
            if (repoId > 0L) navController.navigate(Routes.conflict(repoId))
        }
    }
    if (pendingNav != null) onNavConsumed()
}
```

**根因**

通知语义是“把用户带到可恢复入口”，但实现只消费事件，不改变当前路由。注释描述的是启动页初始状态，不覆盖 `onNewIntent` 场景。

**修复建议**

`NAV_RESUME` 应 `navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = false }; launchSingleTop = true }`，确保 Home banner 可见。

**建议验证方式**

手动停留在 `Routes.AUDIT_DETAIL`，发送带 `NAV_RESUME` 的通知 Intent，点击后应回到 Home。

**关联**

- BUG 模式：事件生成条件的隐式守卫 / 状态机 UI 闭环。

---

## 观察项

### [OBS-001] Compose 多处使用 `collectAsState()`，未使用生命周期感知收集

| 字段 | 值 |
|------|-----|
| **疑似等级** | P4 |
| **置信度** | Low |
| **位置** | `ui/home/HomeScreen.kt`、`ui/policy/SyncPolicyScreen.kt`、`ui/audit/*`、`ui/browser/*`、`ui/conflict/*`、`ui/diff/*`、`ui/ssh/*` |

**现象**：多处页面直接 `collectAsState()`，例如 `HomeScreen.kt:82-86`、`SyncPolicyScreen.kt:86`、`SyncAuditDetailScreen.kt:36`。后台页面不可见时仍可能收集 Flow。

**待验证**：当前 ViewModel Flow 多为轻量 StateFlow，未形成明确崩溃或数据错误。建议引入 `androidx.lifecycle:lifecycle-runtime-compose` 后迁移到 `collectAsStateWithLifecycle()`。

### [OBS-002] 远程 URL 只校验非空，可能持久化带 userinfo 的 HTTPS token URL

| 字段 | 值 |
|------|-----|
| **疑似等级** | P3 |
| **置信度** | Low |
| **位置** | `domain/usecase/BindRepoUseCase.kt:39-46`、`ui/home/HomeScreen.kt` RemoteSection |

**现象**：`BindRemoteUseCase` 只 `require(url.isNotBlank())` 后保存 `url.trim()`。如果用户粘贴 `https://user:token@github.com/org/repo.git`，token 会进入普通 Room 字段并被 UI 展示。

**待验证**：需要确认 UI 是否已在输入层禁止/清洗 userinfo。若没有，应在 Domain 层解析 URL 并拒绝/脱敏 userinfo。

---

## 趋势对比

| 指标 | 上次（2026-05-03 `bug_report_20260503_snao.md`） | 本次 | 变化 |
|------|-------------------|------|------|
| BUG 总数 | 9 | 8 | -1 |
| P0+P1 数 | 3 | 2 | -1 |
| 观察项数 | 3 | 2 | -1 |

**持续存在的问题**：未发现与上次报告标题完全相同且仍未修复的问题。

**上次问题在当前代码中的变化**：

- 上次 `BUG-001`（passphrase cache 无生产端）已出现 `SshKeyRepositoryImpl.generate/import -> passphraseCache.put(...)` 调用痕迹，P0 主链路问题基本消失。
- 上次 `BUG-002` / `BUG-003` 的 sanitizer 绕过路径已有代码注释标注并修复。
- 上次 `BUG-006` 的 `filesChanged` 重复累加已改为集合 union。

**本次新发现**：

- [BUG-001] 后台 push 返回值被忽略；
- [BUG-002] SSH-only 链路仍被 PAT preflight 拦截；
- [BUG-003] `RUNNING` 并发闸门缺失；
- [BUG-004] 通知权限首次请求误判；
- [BUG-005] SAF 持久授权失败吞错；
- [BUG-006] SSH Auth 分类不闭环；
- [BUG-007] passphrase TTL 旧任务误删新值；
- [BUG-008] `NAV_RESUME` 不导航 Home。

**趋势分析**：上一轮高危 P0 已明显收敛，但新增问题集中在“Iteration 3 SSH 分支接入后，Domain/UI/Worker 调用链未完全同步”的同一类根因。建议优先建立 `authType` 分支矩阵测试与后台同步契约测试。

---

## 审查摘要

| 维度 | 数据 |
|------|------|
| 扫描模式 | 全仓扫描（并行合并） |
| 扫描范围 | 12 个模块，118 个 Kotlin 生产文件 + Manifest / Gradle 抽查 |
| 确认 BUG | P0: 0 / P1: 2 / P2: 4 / P3: 2 / P4: 0 |
| 观察项 | 2 条 |
| 置信度分布 | High: 5 / Medium: 3 / Low: 2 |

### 建议优先修复

1. [BUG-001] 后台同步忽略 `push()` 失败并记录 OK — P1 / High。
2. [BUG-002] SSH 仓库仍强制依赖 PAT，SSH-only 用户无法同步 — P1 / High。
3. [BUG-003] 后台同步缺少 `RUNNING` 并发闸门 — P2 / High。
4. [BUG-004] Android 13+ 通知权限首次请求误判 — P2 / High。

### 观察名单

1. [OBS-001] Compose `collectAsState()` 未生命周期感知 — 需评估电量/后台收集影响。
2. [OBS-002] Remote URL 只校验非空 — 需确认是否可能持久化 token userinfo。
