# SimplyGit BUG 审查报告

| 字段 | 值 |
|------|-----|
| **审查日期** | 2026-05-03 |
| **审查模式** | 全仓扫描 |
| **审查范围** | `app/src/main/java/com/example/simplygit/` 全部生产代码（120 个 Kt 文件，覆盖 data/git、data/ssh、data/saf、data/binding、data/credential、data/sync、data/filetree、data/diagnostics、domain/usecase、domain/service、domain/repository、domain/model、runtime、notification、ui/home、ui/MainActivity、di/）。排除 test / resources。 |
| **分支** | main |

---

## Tier 1 — 主链路（data/git + domain/usecase/RunSync + data/binding + data/ssh + runtime）

### [BUG-001] SSH passphrase 缓存从未被任何调用点写入 —— 加密私钥 100% 无法解锁

| 字段 | 值 |
|------|-----|
| **等级** | P0 |
| **置信度** | High |
| **影响范围** | 所有绑定 SSH 且私钥**有 passphrase 保护**的场景（generate 时填了 passphrase、或 import 的用户原有加密私钥）。本次扫描 `git grep -n 'passphraseCache\.\|SshPassphraseCache'` 仅命中 2 处消费端（`loadKeyPairs` / `CachedKeyPasswordProvider`），0 处生产端（`.put(...)`）。Clone / Pull / Push 走 SSH 链路会 100% 报 "no matching auth method"。 |
| **触发条件** | 用户在"SSH 密钥"页 generate 一个带 passphrase 的密钥、或 import 一个加密私钥，然后绑定为仓库的 SSH 凭证并触发任意 Git 操作。 |

**证据**

- 全局搜索 `SshPassphraseCache.put(` 调用点：
  ```
  $ search_content pattern="passphraseCache\.put\(|SshPassphraseCache.*\.put"
  Found 0 matching results
  ```
- `data/ssh/SshPassphraseCache.kt:30-37` 定义了 `put(keyId, passphrase)` 方法并有 10 分钟 TTL 清理协程，但**没有任何 UI / UseCase / Repository 调用它**。
- `data/ssh/GitSshSessionFactoryProvider.kt:86-89`：
  ```kotlin
  val pass = passphraseCache.get(keyId)
  return try {
      val provider: FilePasswordProvider? = pass?.let {
          FilePasphraseProvider.of(String(it))
      }
  ```
  `cache.get(keyId)` 永远返回 null → `provider` 为 null → MINA SSHD 把加密私钥按 "无密码" 尝试 → 抛 `InvalidKeyException` → 被第 99 行 `catch (_: Throwable) { emptyList() }` 吞掉 → SSHD 报 "no more authentication methods available"。
- `CachedKeyPasswordProvider.getPassphrase`（第 122 行）也只从 `cache.get(keyId)` 取值，同样永远是 null，回退 `CharArray(0)`；`keyLoaded` 固定返回 false（不重试），SSHD 立刻放弃该 key。
- `ui/ssh/SshKeyScreen.kt` / `SshKeyViewModel.kt` / `HomeViewModel.kt` 均未实现 passphrase 输入对话框。

**根因**

SPEC §4.4.1 Iteration 3 定义了 `SshPassphraseCache` 的 TTL/put/get 三件套，但 UI 层从未实现"首次使用 SSH 时弹出 passphrase 输入"的 Composable，也没有在 import 流程里把用户输入的 `passphrase: CharArray?` 转交给 cache。属于 `patterns.md#P11` 反模式"类定义孤岛——定义了但未接入调用链"的二次出现：这次是 `SshPassphraseCache.put` 本身被孤立。Spec 定义了缓存契约却没定义"谁 / 何时往里 put"的生产方。

落地偏差在 SPEC v1.2 Change Log 里有留痕——"完整的 `KeyPasswordProvider` + 加密私钥解密读取留待后续集成测试验证"——v1.3 声称"SSH 链路从骨架升级为端到端可用"但实际只接了消费端。

**修复建议**

分两条最小修复路径（推荐同步做）：

1. **新增 `HomeIntent.UnlockSshKey(keyId: String, passphrase: CharArray)`**，在 `HomeViewModel` 里处理：
   ```kotlin
   is HomeIntent.UnlockSshKey -> viewModelScope.launch {
       sshPassphraseCache.put(intent.keyId, intent.passphrase)
       Arrays.fill(intent.passphrase, '\u0000')
   }
   ```
   通过构造函数注入 `SshPassphraseCache`（已经 `@Singleton`）。

2. **在 Git 操作失败且 `SanitizedGitException.originalType == "SshException"`/"InvalidKeyException"/"PrivateKeyException"` 时**，检查当前 binding 的 `authType == "SSH"` 且 `!sshPassphraseCache.has(keyId)`，弹出 passphrase 输入对话框（复用 `tofuPrompt` 同款 AlertDialog 模式），用户提交后 cache.put 并自动重试一次。

备选方案：若 MVP 阶段不想新增 UI，最小改动是**禁止生成带 passphrase 的 key**——在 `SshKeyRepositoryImpl.generate` 里 `require(passphrase == null || passphrase.isEmpty())`，并在 `import` 时只要 passphrase 非空就直接抛 `SshKeyFormatException("encrypted keys not supported yet")`，把问题显式化。

**建议验证方式**

1. 单元测试：`SshKeyRepositoryImpl` mock `SshPassphraseCache`，验证 `import(passphrase = "pw".toCharArray())` 之后 `cache.put(keyId, "pw".toCharArray())` 被调用。
2. 集成测试：在模拟器上 generate 一个带 passphrase 的 key、放入 `ssh-agent`、配置 GitHub Deploy Key，手动触发 Clone。期望：**不再**报 "no more authentication methods"。

**关联**

- 反模式 P11（类定义孤岛）—— 第二次复现。
- 黄金法则 R12（新类型与分派表必须同 commit 闭环）—— 本 bug 扩展覆盖"新缓存组件必须同 commit 接入生产端"。
- SPEC 迭代 3 §4.4.1。

---

### [BUG-002] ResolveConflictUseCase 把原始 Throwable 类名透传到 `sync_log.errorMsg`，绕过 sanitizer 白名单

| 字段 | 值 |
|------|-----|
| **等级** | P1 |
| **置信度** | High |
| **影响范围** | 用户点"解决冲突"后 `commitResolved` 失败的所有路径（JGit 内部 IOException、磁盘满、index 锁定等）。审计日志、诊断日志、未来的 UI 错误详情页都会看到原始类名。 |
| **触发条件** | 在 `ResolveConflictUseCase` 第 113-129 行：`commitResolved` 已经在实现里 `throw sanitizer.sanitize(t)`（`ConflictRepositoryImpl.kt:120`），但外层 `runCatching { ... }.getOrElse { t -> ... }` 捕获的就是 `SanitizedGitException`——然后用 `t.javaClass.simpleName` **覆盖**掉 sanitized message。 |

**证据**

`domain/usecase/ResolveConflictUseCase.kt:120-129`：
```kotlin
}.getOrElse { t ->
    syncLogRepository.finishLog(
        logId = logId,
        result = SyncResult.ABORTED,
        endedAt = Instant.now(clock),
        errorMsg = t.javaClass.simpleName,          // ← 丢掉 sanitized message
        errorType = t.javaClass.simpleName,
    )
    return ResolveResult.PartialFailure(nonSkip.keys.toList(), SyncErrorKind.Unknown)
}
```

对比 `RunSyncUseCase.kt:176-184` 的正确姿势：
```kotlin
} catch (e: SanitizedGitException) {
    diagnostics.logGitOpFailure("SYNC", e)
    handleSanitized(repoId, logId, e)
} catch (e: Throwable) {
    val sanitized = jgitExceptionSanitizer.sanitize(e)
    ...
}
```

**根因**

`commitResolved` 抛出的 `t` 已经是 `SanitizedGitException`（类名就叫 "SanitizedGitException"），但这里取 `javaClass.simpleName` 得到的是字面量 `"SanitizedGitException"`，把 sanitizer 辛苦计算出来的 "Git commit failed | caused-by: …" 文本丢掉，换成一个毫无意义的类名。审计日志从此失去可用性，用户遇到问题时工程师无法判断失败原因。

附加风险：若未来 `ConflictRepositoryImpl.commitResolved` 被改写成 **不** 自己 sanitize（比如直接 rethrow），这里的 `javaClass.simpleName` 就变成真实 JGit 异常名（如 `NoFilepatternException`），虽然不含 PAT 但违反 R8 的"所有错误走 sanitizer"铁律。

**修复建议**

改为按 RunSyncUseCase 的模式显式分派：

```kotlin
}.getOrElse { t ->
    val sanitized = if (t is SanitizedGitException) t
                    else jgitExceptionSanitizer.sanitize(t)
    syncLogRepository.finishLog(
        logId = logId,
        result = SyncResult.ABORTED,
        endedAt = Instant.now(clock),
        errorMsg = sanitized.message,
        errorType = sanitized.originalType,
    )
    return ResolveResult.PartialFailure(nonSkip.keys.toList(), sanitized.kind)
}
```

注入点已经存在（`ResolveConflictUseCase` 构造函数缺 `JGitExceptionSanitizer`，需要加上；`ConflictRepositoryImpl` 已注入）。`SyncErrorKind.Unknown` 硬编码也应改为 `sanitized.kind` 以便网络错误仍能归类为 Network。

**建议验证方式**

单元测试：mock `ConflictRepository.commitResolved` throw `SanitizedGitException("fake msg", "FakeException", SyncErrorKind.Network)`，验证 `syncLogRepository.finishLog` 收到的 `errorMsg == "fake msg"` 而非 `"SanitizedGitException"`；`ResolveResult.PartialFailure.kind == Network`。

**关联**

- 黄金法则 R8（UI/日志前异常必须走显式分派 + sanitizer 兜底）。
- 反模式 P5（异常进入 UI/日志前缺乏统一脱敏/分派兜底）—— 同样模式，这次发生在 Data→Domain 边界。
- SPEC 迭代 3 §4.3.1。

---

### [BUG-003] HomeViewModel.finishManualLogFailure 把原始 Throwable.simpleName 写入 errorMsg/errorType

| 字段 | 值 |
|------|-----|
| **等级** | P1 |
| **置信度** | High |
| **影响范围** | 所有手动 Clone/Pull/Commit/Push 失败的审计行。非 `SanitizedGitException` / `SafPermissionRevokedException` / `MissingCredentialException` / `MissingBindingException` 四类白名单之外的异常（例如 JGit 自己漏 sanitize 的 `IOException`、Hilt 构造阶段抛的 `IllegalStateException`、协程取消的 `CancellationException`）会把原始类名写进 `sync_log.errorType` 和 `errorMsg`。 |
| **触发条件** | `HomeViewModel.kt:429-434`：`else -> Triple(SyncResult.ABORTED, cause.javaClass.simpleName, cause.javaClass.simpleName)`。触发 `GitOp.CLONE/PULL/PUSH/COMMIT` 时任意未被 `GitOpPreflight.afterOp` 兜住的 Throwable。 |

**证据**

`ui/home/HomeViewModel.kt:401-445` `finishManualLogFailure` 分支表：
```kotlin
else -> Triple(
    SyncResult.ABORTED,
    cause.javaClass.simpleName,        // ← 未经 sanitizer
    cause.javaClass.simpleName,
)
```

虽然 `GitOpPreflight.afterOp` 会 sanitize 所有 failure（`domain/usecase/GitUseCases.kt:62-88`），但它只处理 `GitOpResult.Failure`；如果 `block()` 本身抛异常（例如 `cloneRepo()` 的 suspend body 抛 `CancellationException`——viewModelScope 被取消时常见），`runOp` 第 326 行 `val result = block()` 会让异常直接上抛到 `viewModelScope` 顶层，ViewModel 的 `supervisorJob` 行为 + Kotlin 协程 runtime 把它吞为 CancellationException。**正常情况下**这个分支确实难触发，但只要有一处漏网之鱼（比如未来新增 op 忘了走 GitOpResult 契约），就会直接把原始异常 class name 持久化。

**根因**

R8 要求"契约上只允许 `SanitizedException` 或白名单"。这里的 `else` 分支违反该契约——任何未预期的 Throwable 都应先过 `jgitExceptionSanitizer.sanitize(cause)`。

**修复建议**

1. 构造函数注入 `JGitExceptionSanitizer`。
2. 修改 `else` 分支：
   ```kotlin
   else -> {
       val sanitized = jgitExceptionSanitizer.sanitize(cause)
       Triple(SyncResult.ABORTED, sanitized.message, sanitized.originalType)
   }
   ```
3. 同时把 `MissingCredentialException` / `MissingBindingException` / `SafPermissionRevokedException` 的 `errorMsg` 也改成走 sanitizer 或本地化 string key，而不是直接 `javaClass.simpleName`（SPEC R8 原话："UI 层所有错误文案通过 `strings.xml` 渲染"）。

**建议验证方式**

单元测试：在 ViewModel 里 mock `block()` throw 一个自定义异常 `FooException("ghp_xxxxxxxxxxxxxxxxxxxx")`。验证 `sync_log` 行里 `errorMsg` 不含 `ghp_` 字符串。

**关联**

- 黄金法则 R8、R12。
- 反模式 P5。

---

### [BUG-004] DiffRepositoryImpl 对新 clone 仓库（无 HEAD）的 Diff 提前返回，但 `headId` 局部变量失效

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | High |
| **影响范围** | 新克隆 / 初始化但尚未有任何 commit 的 Vault，用户在 `RepoBrowserScreen` 点任何文件都会看到 "文件不存在" 错误。实际应该显示 "WORKING_VS_HEAD with empty HEAD = full file as ADDED"。次要影响：`scanWorkingVsHead` / `scanOursVsTheirs` 内部各自重新 `repo.resolve("HEAD")`，是 death code 重复。 |
| **触发条件** | 空仓库（或 HEAD 未写入时的 detached 瞬间）调用 `DiffRepository.diff`。 |

**证据**

`data/git/DiffRepositoryImpl.kt:86-89`：
```kotlin
private fun computeDiff(repo: Repository, path: String, source: DiffSource): DiffOutcome {
    val headId = repo.resolve("HEAD") ?: return DiffOutcome.Failed(DiffFailure.FILE_MISSING)
    // headId is never used below — scanWorkingVsHead re-resolves HEAD internally.
```

- 第 87 行声明 `headId` 但后续流程 100% 不使用它，IDE 级别的死代码。
- 同时对"空仓库"的处理是直接 `return Failed`，但 JGit 的 `FileTreeIterator(repo)` + 空 `CanonicalTreeParser` 组合本来可以输出纯 ADDED 行（所有工作区文件都是新增）。
- `scanWorkingVsHead` 第 123 行又独立做一次 `repo.resolve("HEAD") ?: return emptyList()`，之后 `parseEntry` 会报 `FILE_MISSING`。

**根因**

作者最初打算把 headId 下沉到 scan 方法，后来重构时把 scan 里的 resolve 保留了但忘记删顶层的 `headId`。顶层的 `?: return FILE_MISSING` 成了"意外的 early return"，把所有空仓库路径打死。

**修复建议**

1. 删除第 87 行的 `val headId = ...` 这一整行（以及对应 early return），改成：
   ```kotlin
   private fun computeDiff(repo: Repository, path: String, source: DiffSource): DiffOutcome {
       val out = ByteArrayOutputStream()
       ...
   ```
2. 在 `scanWorkingVsHead` 内当 HEAD 为 null 时，构造一个空的 `EmptyTreeIterator()` 作为 old side，这样空仓库的 working copy 也能走 DiffFormatter 正常对比。

**建议验证方式**

集成：`git init` 一个空目录 + 放一个 `README.md` + 调 `DiffRepository.diff(repoId, "README.md", WORKING_VS_HEAD)`，期望返回 `DiffOutcome.Full` 且所有行 kind = ADDED。

**关联**

- 反模式 P8（虚构挂载点）的变体：变量声明了但不服务于声明位置的语义。

---

### [BUG-005] TofuServerKeyDatabase.lookup() 永远返回空列表 —— 潜在破坏 SSHD 算法协商

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | Medium |
| **影响范围** | 连接使用非默认 host key 算法（如仅启用 ssh-ed25519 的服务端）的 Git 主机时，SSHD 无法基于历史 known_hosts 做 `HostKeyAlgorithms` 优先级排序，可能导致第二次及以后的连接意外触发 `SshHostKeyChangedException`（服务器优先提供不同算法的 host key）。 |
| **触发条件** | 用户首次成功连接 github.com 后，GitHub 服务器端变更 host key 算法优先（非假设，GitHub 2023 年就做过一次 RSA→Ed25519 迁移）。 |

**证据**

`data/ssh/TofuServerKeyDatabase.kt:46-50`：
```kotlin
override fun lookup(
    connectAddress: String,
    remoteAddress: InetSocketAddress,
    config: ServerKeyDatabase.Configuration,
): MutableList<PublicKey> = mutableListOf()
```

注释写"A conservative empty list is fine; the real gate is `accept`"——但 MINA SSHD 2.13 的 `OpenSSHKnownHostsFile.lookup` 文档说明 lookup 用于 `HostKeyAlgorithms` 协商。始终返回空列表等价于告诉 SSHD "没见过这个服务器"，让 client 跟 server 重新协商，而 `accept` 再根据 fingerprint 文件裁决。**理论上 TOFU 语义没错**，但实际上 accept 里存的是 fingerprint 而不是 PublicKey 对象——无法在 lookup 里重建 PublicKey 来 feed SSHD。

**根因**

当前 `known_hosts` 存储格式是 `host unknown - fingerprint`，丢失了 PublicKey 的 bytes。要实现完整 lookup 必须也存 base64 encoded pubkey，然后读回来用 `KeyUtils.buildKey` 重建——这是 `patterns.md#P9`"跨协议/SDK 序列化格式混淆"的反面镜像：这里是**故意丢失**了 serializer 侧的信息，导致 reader 侧无法 round-trip。

**修复建议**

重新设计 `known_hosts` 行格式为标准 OpenSSH 格式 `<host> <algo> <base64-pubkey>`（与 ssh-keygen 兼容），并在 `accept(host, fingerprint)` 和 `accept(connectAddress, serverKey, ...)` 里都写入 serverKey 的完整 bytes。`lookup` 回读时用 `KeyUtils.parsePublicKey` 重建 PublicKey 列表。

当前最小缓解：在 `accept(serverKey)` 里用 `AuthorizedKeyEntry` 或 `OpenSSHKeyPairResourceWriter.writePublicKey` 把 `PublicKey` 序列化成单行 `ssh-ed25519 AAAA…`，附加到 fingerprint 字段后面，lookup 回读时 parse 出来。

**建议验证方式**

集成：

1. mock 一个 SSHD 服务器，首连时提供 ed25519+rsa 两个 host key。
2. 通过 SimplyGit 完成 first-connect TOFU accept。
3. 修改 mock 服务器配置让 rsa 排在第一位。
4. 再次触发 pull。**期望**：客户端仍信任之前的 ed25519 key（不再触发 TOFU）。**当前实现**可能会触发 `SshHostKeyChangedException`。

**关联**

- 反模式 P9（跨协议序列化格式混淆）。
- SPEC 迭代 3 §4.4.2 TOFU 设计。

---

### [BUG-006] JGitDataSource.commitAllIfDirty.filesChanged 按 JGit status 字段重复累加，可能超报

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | Medium |
| **影响范围** | 所有 `commitAllIfDirty` 成功的路径，`CommitOutcome.filesChanged` 被用于审计日志 `sync_log.filesChanged`。对用户来说只是"改了多少文件"的统计数字偏大，不影响实际 git 操作。 |
| **触发条件** | 同一路径同时出现在 `status.added` 与 `status.untracked`（理论上 JGit 的 `status()` 各集合互斥，但 `modified` 与 `missing` 在 "文件被外部 rm 后 git add -u" 前的瞬间可能有重叠）。 |

**证据**

`data/git/JGitDataSource.kt:147-152`：
```kotlin
val filesChanged = status.added.size +
    status.changed.size +
    status.modified.size +
    status.removed.size +
    status.missing.size +
    status.untracked.size
```

`git.add().addFilepattern(".").call()` 会把 untracked 变成 added、把 modified 变成 changed、把 missing 变成 removed，但这个转换**发生在 status() 调用之前 还是 之后**取决于 call 顺序——当前代码是 `add → status → commit`，所以 status 已经反映了 add 之后的状态，理论上：
- untracked 已全部变 added（或空）
- modified 已全部变 changed（或空）
- missing 已全部变 removed（或空）

**但** `git.add().addFilepattern(".")` 不会 `add -u`——它 **不会** stage 已被外部删除的文件（missing）。所以在"用户直接删了文件"的场景，status 同时含 `missing`（工作区删除）和 `removed`（如果是 git rm 过的）是可能分离的。不致命，只是计数可能略大。

**根因**

作者把 JGit 的 status 分桶当成"互斥 6 类文件"来加总，但实际这 6 个集合在某些中间状态下可能有重叠或语义重复。

**修复建议**

使用并集：
```kotlin
val filesChanged = (status.added + status.changed + status.modified +
                    status.removed + status.missing + status.untracked).size
```
或者更精确：`status.uncommittedChanges.size` —— JGit 有这个 API 直接给出"需要 commit 的路径并集"。

**建议验证方式**

单元测试：构造一个文件同时出现在 modified 和 missing 的 mock Status，断言 `commitAllIfDirty` 返回的 `CommitOutcome.filesChanged` 等于 path 数量而非 2×path。

**关联**

- 反模式无直接关联；属于 JGit API 语义误解。

---

## Tier 2 — UI / Infra（domain/usecase + ui/home + data/filetree + data/saf + data/diagnostics）

### [BUG-007] FileTreeRepositoryImpl 把 JGit 的 `status.missing` 映射为 MODIFIED，丢失"已删除"语义

| 字段 | 值 |
|------|-----|
| **等级** | P2 |
| **置信度** | High |
| **影响范围** | `RepoBrowserScreen` 用户看到的 "已修改" 红点，实际上可能是"已删除"。用户无从区分"我改了文件" vs "我删了文件"。 |
| **触发条件** | 用户在文件管理器或 Obsidian 里删除一个已跟踪的文件但尚未 `git rm`，打开 RepoBrowser。 |

**证据**

`data/filetree/FileTreeRepositoryImpl.kt:83-84`：
```kotlin
status.modified.forEach { put(it, GitFileStatus.MODIFIED) }
status.missing.forEach { put(it, GitFileStatus.MODIFIED) }
```

JGit 的 `status.missing` 语义是"已跟踪但工作区不存在"（对应 `git status` 的 "deleted"），不是 "modified"。

**根因**

`GitFileStatus` 枚举没有 `DELETED` / `MISSING` 值（见 `domain/model/GitFileStatus.kt` 未读但从聚合优先级 `CONFLICT > MODIFIED > STAGED > UNTRACKED > CLEAN` 可知只有 5 个值），作者在映射时选了最接近的 MODIFIED。语义合并掩盖了 bug。

**修复建议**

1. `GitFileStatus` 新增 `DELETED` 值（SPEC §4.1.1 需要相应更新）。
2. `FileTreeRepositoryImpl.rescan` 将 `status.missing` 映射为 `DELETED`；聚合优先级插入在 MODIFIED 和 STAGED 之间（`CONFLICT > DELETED > MODIFIED > STAGED > UNTRACKED > CLEAN`）。
3. `RepoBrowserScreen` 对 DELETED 节点显示划线样式或红色"已删除"徽标。

**建议验证方式**

单测：在 mock `git.status()` 里把 `foo.md` 放进 `missing` 集合，调用 rescan，验证 `file_tree_cache.where(path = "foo.md").gitStatus == "DELETED"`。

**关联**

- 黄金法则 R12（新枚举分支必须同 commit 更新分派表）—— 反过来，这里是该加新分支时复用了现有分支，产生了语义合并。

---

### [BUG-008] CredentialRepositoryImpl.snapshotIdentity 通过 callbackFlow 读快照，每次都注册+注销一次 SharedPreferences listener

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | High |
| **影响范围** | 后台同步每次 `RunSyncUseCase.invoke` 都调一次 `credRepo.snapshotIdentity()`（第 113 行）。按 15 分钟周期看每天 96 次；加上 ResolveConflict / ManualOp 里的 `firstOrNull()` 调用，总调用量每天几百次。每次都注册再注销 `SharedPreferences.OnSharedPreferenceChangeListener`，是 AOSP 内部的一次 ArrayMap.put/remove，压力小但可观察。 |
| **触发条件** | 每次 RunSyncUseCase / GitOpPreflight.prepare 执行。 |

**证据**

`data/credential/CredentialRepositoryImpl.kt:27`：
```kotlin
override suspend fun snapshotIdentity(): CredentialIdentity? =
    dataSource.observe().firstOrNull()?.let { CredentialIdentity(it.username, it.email) }
```

`data/credential/EncryptedCredentialDataSource.kt:50-64` 的 `observe()` 是 `callbackFlow`：每订阅一次注册 listener、`firstOrNull` 消费完首个 emit 立刻取消 → `awaitClose` 执行 `unregisterOnSharedPreferenceChangeListener`。

**根因**

把 "observe" 和 "一次性读取" 混在同一个 API 上。Iteration 2 fix I-2 SPEC 注释写"Built on top of the existing Flow so we don't duplicate ESP read logic"——但实际这是副作用更多、性能更差的选择。

**修复建议**

新增 `EncryptedCredentialDataSource.snapshotIdentity()` 直接读：
```kotlin
suspend fun snapshotIdentity(): CredentialPublicView? = withContext(io) {
    val u = prefs.getString(KEY_USERNAME, null) ?: return@withContext null
    val e = prefs.getString(KEY_EMAIL, null) ?: return@withContext null
    CredentialPublicView(u, e)
}
```
然后 `CredentialRepositoryImpl.snapshotIdentity` 直接委托给它，不走 Flow。

**建议验证方式**

运行时：启用 StrictMode + 打开日志，触发 20 次 RunSyncUseCase，期望 `registerOnSharedPreferenceChangeListener` 调用次数不增加。

**关联**

- 无直接反模式，属于架构反复造轮子。

---

### [BUG-009] DiagnosticsLogger.append 在单日文件满时 `writeText("")` 整段清空，丢失 64KB 审计历史

| 字段 | 值 |
|------|-----|
| **等级** | P3 |
| **置信度** | High |
| **影响范围** | 连续写入超过 64KB（约 500-1000 条日志）时，当天早些时候的所有记录一次性清空。工程师排障时看到的是"当天只有最新几条"，可能漏掉关键根因。 |
| **触发条件** | 单日诊断日志 > 64KB；对 BROKEN 循环 + 每次同步几十行输出的场景很容易触发。 |

**证据**

`data/diagnostics/DiagnosticsLogger.kt:78`：
```kotlin
if (file.length() > DAY_MAX_BYTES) file.writeText("")
```

**根因**

作者选了"直接截断"而非"滚动到 .old 后缀"或"保留尾部 N 行"。注释自述"no rolling-logger dependency"——但自写一个简单的 "保留尾部 50% + 续写" 只需 3 行代码。

**修复建议**

改为 tail-keep 策略：
```kotlin
if (file.length() > DAY_MAX_BYTES) {
    val tail = file.readText().takeLast(DAY_MAX_BYTES.toInt() / 2)
    file.writeText(tail)
    file.appendText("\n--- LOG TRUNCATED ---\n")
}
```

或者更简单：当超过上限时新建 `diagnostics-YYYY-MM-DD.1.log`，`snapshotRecentLogFiles` 和 `pruneOldLogs` 同步扫描带 `.N.log` 后缀的变体。

**建议验证方式**

单测：mock `Clock`, 写入 200 条 1KB 的日志到同一天，然后断言 `file.length() > 0` 且文件内容包含最早和最新两端的样本。

**关联**

- 无直接反模式；属于 "自实现简易组件 < 依赖成熟库" 的取舍。

---

## 观察项（置信度 Low / 证据链未完全闭合）

### [OBS-001] SshKeyDataSource.readPublic 对非标准 OpenSSH 私钥文本定位失败

| 字段 | 值 |
|------|-----|
| **疑似等级** | P3 |
| **置信度** | Low |
| **位置** | `data/ssh/SshKeyDataSource.kt:77-82, 111-126` |

**现象**：`persist` 写入 `<priv>\n# PUBLIC-KEY #\n<pub>`；`readPublic` 用 `text.indexOf("\n# PUBLIC-KEY #\n")` 定位分隔符。如果用户 `import` 的私钥原文**不以 `\n` 结尾**（某些 Windows 编辑器保存的 OpenSSH 文本），写入后的 text 变成 `<priv># PUBLIC-KEY #\n<pub>`，`indexOf("\n# PUBLIC-KEY #\n")` 匹配失败，`readPublic` 返回 null，UI 上"复制公钥"按钮失效。

**待验证**：找一个无尾部 \n 的 OpenSSH 私钥文本 `import` 进来，调 `exportPublic(keyId)`，期望非空，实际可能为空。

---

### [OBS-002] SyncPolicy.intervalMinutes 未做下限校验，UI 提供的值是安全的，但数据层不防御

| 字段 | 值 |
|------|-----|
| **疑似等级** | P3 |
| **置信度** | Low |
| **位置** | `data/sync/SyncPolicyRepositoryImpl.update` + `runtime/SyncSchedulerImpl.schedulePeriodic` |

**现象**：WorkManager `PeriodicWorkRequestBuilder` 的 interval 下限是 15 分钟；当前 UI 只允许 15/30/60/-1，数据层 `SyncPolicyRepositoryImpl.update` 没有 `require(...)`。如果未来有迁移脚本、深链接或测试代码传 `intervalMinutes = 5`，`PeriodicWorkRequestBuilder(5L, MINUTES)` 会抛 `IllegalArgumentException`，崩进程。

**待验证**：写一个单测 `syncPolicyRepository.update(SyncPolicyModel(5, ...))` 然后 `scheduler.schedulePeriodic(...)` 是否抛。

---

### [OBS-003] TofuServerKeyDatabase 对同一 host 的重复 accept 行只读第一条

| 字段 | 值 |
|------|-----|
| **疑似等级** | P3 |
| **置信度** | Low |
| **位置** | `data/ssh/TofuServerKeyDatabase.kt:86-92` |

**现象**：`readStoredFingerprint` 用 `firstOrNull { it.startsWith("$host ") }`——如果用户先 accept 了 host 的 old fingerprint，随后 host 的 fingerprint 变更，用户再次 accept 后文件里会有两行（因为 `accept` 用 `appendText`），`readStoredFingerprint` 拿到的还是旧 fingerprint，导致新 fingerprint 被当成"mismatch"报错。

**待验证**：在同一 host 上连续调 `accept(h, "fpA")` 和 `accept(h, "fpB")`，然后 `accept(h1, srv_with_fpB)` 是否返回 true。

---

## 趋势对比

首次扫描，无历史对比数据。

建议：`docs/retro/patterns.md` 的 P11（类定义孤岛）在本次扫描中再次命中（BUG-001 的 SshPassphraseCache.put 未接入），应考虑把 P11 升级为 `patterns.md` 高优先级条目，并让 R12 强制 "新 cache 组件必须同 commit 有生产端 put/write 点"。

---

## 审查摘要

| 维度 | 数据 |
|------|------|
| 扫描模式 | 全仓扫描 |
| 扫描范围 | 8 个模块（data/git、data/ssh、data/saf、data/binding、data/credential、data/sync、data/filetree、data/diagnostics）+ domain/usecase + ui/home + runtime + di + notification，共 ~120 Kt 文件 |
| 确认 BUG | P0: 1 / P1: 2 / P2: 3 / P3: 3 / P4: 0 |
| 观察项 | 3 条 |
| 置信度分布 | High: 6 / Medium: 2 / Low: 3 |

### 建议优先修复

1. **[BUG-001]** SSH passphrase 缓存从未被任何调用点写入 —— P0 / High。SSH 链路实质断路，必须立即修复。
2. **[BUG-002]** ResolveConflictUseCase 把原始 Throwable.simpleName 透传到 errorMsg —— P1 / High。审计可用性 + R8 契约违反。
3. **[BUG-003]** HomeViewModel.finishManualLogFailure 绕过 sanitizer —— P1 / High。同上。
4. **[BUG-004]** DiffRepositoryImpl 空 HEAD 仓库 early return —— P2 / High。空仓库 diff 完全不工作。
5. **[BUG-005]** TofuServerKeyDatabase.lookup() 丢失 PublicKey 信息 —— P2 / Medium。SSHD 算法协商可能退化。
6. **[BUG-007]** FileTreeRepositoryImpl 把 MISSING 映射为 MODIFIED —— P2 / High。UI 语义混淆。

### 观察名单

1. **[OBS-001]** SshKeyDataSource.readPublic 对非标准换行的 OpenSSH 私钥定位失败 —— 需实测无 `\n` 结尾的导入样本。
2. **[OBS-002]** SyncPolicy.intervalMinutes 数据层未做下限校验 —— 需确认未来是否有非 UI 写入路径。
3. **[OBS-003]** TofuServerKeyDatabase 同 host 重复 accept 行只读第一条 —— 需实测 rotate 场景。
