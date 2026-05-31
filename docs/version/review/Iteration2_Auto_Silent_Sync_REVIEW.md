## 📋 Spec 评审报告

**文档**：Iteration2_Auto_Silent_Sync_SPEC.md
**版本**：v1.0
**评审日期**：2026-05-02

### 一、结构完整性

| 章节 | 状态 | 备注 |
|------|------|------|
| 文档信息（版本 / 日期 / 前置依赖 / 对齐声明） | ✅ 完整 | §1 含版本、前置依赖（迭代 1 已验证）、与总方案 §9 Phase 2 的偏离声明（N5/N6） |
| 背景与目标 | ✅ 完整 | §2.1 现状、§2.2 生态调研（含 3 个反面教材 + 3 条调研结论）、§2.3 10 条目标 G1–G10 |
| 当前状态 / 问题分析 | ✅ 完整 | §2.1 已枚举 4 类缺口 + 3 类技术风险 |
| 非目标 | ✅ 完整 | §2.4 N1–N11 覆盖冲突 UI / SSH / OkHttp Transport / FileObserver / Shallow / 通知静默策略等 |
| 方案决策（对比 + 选型） | ✅ 完整 | §3.1 三张对比表（调度/冲突分类/策略存储）均含"数据权威性 + 供应链安全"列，§3.2 结论逐项对应 |
| 详细设计 | ✅ 完整 | §4.1 目录、§4.2 ConflictClassifier、§4.3 RunSyncUseCase、§4.4 Worker/Scheduler、§4.5 状态机、§4.6 Room、§4.7 UI、§4.8 DI、§4.9 性能 |
| 数据模型 / 接口定义 | ⚠️ 不足 | §6.1 Domain model 齐全；§6.2 `GitRepository` 扩展仅列 2 个新签名，但**未说明原 `pull/commitAll` 是保留还是被替换**；另 `RepoBindingRepository` 新增 `currentOrNull()` 签名在 Spec 任何地方**未列出**（§4.3 代码里直接调用） |
| 交互流程 / 用户流程 | ✅ 完整 | §5.2 四流程（策略首配 / 后台静默 / 冲突介入 / 网络熔断 / 冷启动 catch-up）覆盖完整路径 |
| 跨层字段传递 | ✅ 完整 | §6.3 矩阵 13 行 + 4 条边界过滤点 |
| 验收标准 | ✅ 完整 | §7.1 A1–A10 覆盖 G1–G10 全部目标；§7.2 NF1–NF7 含可度量数值（耗电 2%、端到端 15s、体积 2.5MB、耗电回归门 NF7） |
| 风险与缓解 | ✅ 完整 | R-1 至 R-10，其中 R-1（Doze 配额）和 R-2（JGit 资源泄漏）缓解有具体技术手段 |
| 变更记录 | ✅ 完整 | §10 首版条目齐备 |

---

### 二、方案内部一致性

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| IC1 | 目标→解法覆盖 | ✅ | G1–G10 在 §4.1–§4.9 均有对应章节；§9 自检表 SC3 已自证 |
| IC2 | 解法→目标溯源 | ✅ | §4 各模块均注明对应目标编号；无"有解法无目标"冗余设计 |
| IC3 | 非目标边界一致 | ✅ | N1–N11 中的 FileObserver / OkHttp Transport / Shallow / WindowCacheConfig / 冲突解决 UI 在 §4 均未出现 |
| IC4 | 迭代边界一致 | ✅ | 与 `BOUNDARIES.md` 迭代 2 摘要（WM 周期 / 防抖 / `ConflictClassifier` 六类 / 状态机 / Room 审计 / 通知分级 / catch-up / ZIP 导出）完全对齐 |
| IC5 | 冗余内容检测 | ⚠️ | §4.7 保留迭代 1 的"Clone/Pull/Commit/Push 四操作按钮在 `PAUSED_*` 下仍可手动执行"——这与 G4 + G7 "状态未由用户解除前不尝试任何 push"语义存在**语义张力**（手动 push 也是 push）；建议 Spec 显式声明"手动操作不触发状态机检查"是刻意决策还是不一致 |
| IC6 | 前后表述一致 | ⚠️ | §6.2 "关键决策"声明"**禁止**把 `Repository` 引用透出到 Domain 层外部"，但同节定义的 `data class PullOutcomeWithRaw(..., val repoSnapshot: Repository, ...)` 是**Domain 层数据类型**（`GitRepository` 是 domain 接口）——类型定义本身已经透出，"靠约定不透出"与类型系统矛盾；后文依赖约定才成立 |

---

### 三、合理性评审

**总评**：🟢 优

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| A1 | 问题-方案匹配 | ✅ | §2.1 四个缺口（无 WM / 冲突透传 / 无审计 / 无防抖）与 G1/G3/G4/G5 一一对应 |
| A2 | 方案对比充分性 | ✅ | §3.1 三张表均 3+ 维度、含量化（耗电 1×/1.2~3×/2~5×；代码量 80 vs 400 行；多仓扩展返工成本） |
| A3 | 迭代边界遵守 | ✅ | 与 BOUNDARIES 迭代 2 摘要一致；未引入超范围功能 |
| A4 | 复用优先原则 | ✅ | 调度 WM / 冲突分类 JGit `MergeStatus` / 策略存储 Room / 导出 FileProvider 均为一方库复用 |
| A5 | 非目标合理性 | ✅ | N5（OkHttp Transport 推迟）、N6（FileObserver 不做）均有调研依据（§2.2）；N7（Shallow Clone）引用 JGit 6.10 bug；理由充分 |
| A6 | 前置依赖 | ✅ | §1 显式列出迭代 1 的 6 项产出物作为前置依赖；§4 多处标注"迭代 1 已有" |
| A7 | 目标-解法一致性 | ✅ | 所有 Phase 子任务均能回溯到 G1–G10；无"顺手改造" |
| A8 | 冗余度 | 🟡 | 基本精简，唯一灰区是 §4.7 "迭代 1 四操作按钮保留"与状态机的关系（见 IC5） |

---

### 四、清晰度评审

**总评**：🟡 良

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| B1 | 术语一致性 | ✅ | `SyncState` / `ConflictClass` / `SyncResult` / `SyncTrigger` 四枚举首尾引用一致 |
| B2 | 接口定义精确性 | ⚠️ | ①`pullWithMergeResult(binding, pat)` **丢掉了 username 参数**（当前 `pull(binding, username, pat)` 含 username，JGit `UsernamePasswordCredentialsProvider` 需要）；②`commitAllIfDirty(binding, message)` 丢掉 `authorName / authorEmail`（当前签名有 4 个参数）——改为仅 2 参数后，username/email 的获取链路没有在 §6.2/§6.3 中说明 |
| B3 | 流程可追踪性 | ✅ | §5.2 四条流程从触发源到终点完整；§4.3 `RunSyncUseCase` 步骤 1-6 编号连贯 |
| B4 | 代码示例可理解性 | ⚠️ | ①`ConflictClassifier.resolveConflictKind` 调用 `isBinaryByAttribute(repo, path)` / `isBinaryByFirst8KB(repo, path)` 但这两个 helper **未定义**（正文说"§4.2 实现"但实际未给出代码或签名约束）；②`classify(PullResult, Repository)` 的二参数签名与"JGit 原生类型仅限 `Git.use{}` 作用域内"的约束耦合，调用时序在 §6.2 "关键决策"虽有说明，但 classifier 的入参类型声明本身暴露了 JGit `Repository` 到 Domain 层 |
| B5 | 验收标准可操作性 | ✅ | A1 含 `adb shell cmd jobscheduler` 命令；A2c 含 `getWorkInfosForUniqueWork().periodStartTime` 断言；A4 含 5 类冲突实值断言；A9d 含 `grep -E "ghp_\|github_pat_\|Authorization:"` 断言；A10 Battery Historian 采样——均可执行 |
| B6 | 任务可拆解性 | ✅ | §4.1 目录结构按子包划分，能拆出 5 组并行任务（Room+DAO / Worker+Scheduler / ConflictClassifier+Classification / Notification+Permission / UI Policy+Audit） |
| B7 | 任务粒度与依赖 | ⚠️ | 依赖顺序未显式声明：建议 Room schema → Domain usecase → Worker → UI 顺序；§4 未画依赖图，且 **DataStore→Room 迁移（§4.6 R-7）与迭代 1 `SafUriStore` 的生命周期关系**未说明——迁移期间 `RepoBindingRepositoryImpl` 是否改写为 Room 版？若是，迭代 1 的 DataStore-based 实现何时淘汰？ |

---

### 五、可行性评审（代码验证）

**总评**：🟠 中（有 1 个 P0、2 个 P1 阻塞项需修订）

| # | 检查项 | 评分 | 发现 | 代码依据 |
|---|--------|------|------|---------|
| C1 | 代码可达性 | ❌ | §4.3 `RunSyncUseCase` 的 `catch (e: AuthFailedException)` / `catch (e: NetworkException)` —— 这两个**异常类不存在**。当前 `JGitExceptionSanitizer.sanitize()` 统一包装为 `SanitizedGitException`（单一类型），不做 HTTP 401/403/网络分流 | `JGitExceptionSanitizer.kt:21-35` / `SanitizedGitException.kt:8-11`（仅有 `message` + `originalType` 字符串） |
| C1 | 代码可达性 | ❌ | §4.3 调用 `e.sanitizedMessage()`，当前 `SanitizedGitException` 没有 `sanitizedMessage()` 扩展；且 sanitizer 的输出已经塞进 `message` 里——方法名冗余 | `SanitizedGitException.kt:8-11` |
| C1 | 代码可达性 | ❌ | §4.3 调用 `bindingRepo.currentOrNull()`，当前 `RepoBindingRepository` 只有 `observe()` / `requireCurrent()`（抛异常） | `RepoBindingRepository.kt:6-17` |
| C2 | 改动完备性 | ⚠️ | §6.2 定义 `pullWithMergeResult(binding, pat)` / `commitAllIfDirty(binding, message)`，但未说明：①原 `pull(binding, username, pat)` / `commitAll(binding, message, authorName, authorEmail)` **是替换还是新增**；②username/authorName/authorEmail 的来源——若改为 UseCase 内部从 `CredentialRepository` 取，需新增 `CredentialRepository.snapshotIdentity(): Pair<String, String>` 之类的接口；Spec 未列 | `GitRepository.kt:13-23` / `GitRepositoryImpl.kt:23-40` |
| C2 | 改动完备性 | ⚠️ | §4.1 提及 `RepoBinding` "新增 id 字段（Long，默认 1）"，但 §6.2 `pullWithMergeResult(binding: RepoBinding, pat: CharArray)` 之 `RepoBinding` 其余字段（`treeUri` / `localAbsPath` / `remoteUrl`）的可空性未说明；§4.6 `RepositoryEntity.localAbsPath: String?` **可空**，但 domain `RepoBinding.localAbsPath: String` **非空**——迁移后若实体允许空，mapper 转换规则未定义 | `RepoBinding.kt:7-11` / §4.6 RepositoryEntity |
| C2 | 改动完备性 | ⚠️ | `RepositoryEntity.syncPolicyId: Long` 引用了 `SyncPolicyEntity.id`，但 **未声明 `@ForeignKey(entity = SyncPolicyEntity::class)`**；`SyncLogEntity` 对 `RepositoryEntity` 的 FK 声明了。Room schema 不一致 | §4.6 代码块 |
| C2 | 改动完备性 | ⚠️ | `DiagnosticsLogger` 当前用单文件 + 256KB 一次性截断（非滚动），**无时间轴语义**；§4.7 / A9b 要求"导出最近 7 天 `diagnostics.log`"，但实现上当前日志文件随时可能被整段截断（超过 256KB 就 `writeText("")`），"最近 7 天"无法保证。需要 Spec 选：(a) 改用按日滚动文件（`diagnostics-YYYY-MM-DD.log` + 7 天清理），或 (b) 验收标准降级为"导出当前 `diagnostics.log` 文件内容，不保证 7 天范围" | `DiagnosticsLogger.kt:48-61` / §7.1 A9b |
| C3 | 命名与路径一致性 | ⚠️ | §4.1 目录声明 `domain/service/ConflictClassifier.kt`，但 `ConflictClassifier` 在 §4.2 的 `classify(pullResult: PullResult, repo: Repository)` 签名依赖 JGit 类型，属 Data-ish 服务，放 Domain 层与其他 Domain 服务（`DebounceGuard` 等纯 Kotlin）分层标准不一致；建议澄清为"纯函数，但允许接受 JGit 类型入参"，或者在 Data 层做一层包装 | §4.1 目录 / §4.2 classifier |
| C3 | 命名与路径一致性 | ✅ | `SimplyGitApp` 现为 `@HiltAndroidApp`，§4.4 扩展为 `Configuration.Provider` 合规；`POST_NOTIFICATIONS` / `FileProvider` / `WorkManagerInitializer tools:node="remove"` 三段 Manifest 改动完整 | `SimplyGitApp.kt:1-11` / `AndroidManifest.xml` |
| C4 | 向后兼容性 | ⚠️ | DataStore → Room 的一次性迁移 §4.6 说"迁移成功后清 DataStore 键"，R-7 又说"迁移成功前不清源数据"——语义一致，但**迭代 1 的 `SafUriStore` 生命周期**（DataStore Preferences 的 key）在迭代 2 启用 Room 后是否 deprecate？若仍保留双源，观察路径（`RepoBindingRepositoryImpl.observe`）优先级未定 | `RepoBindingRepositoryImpl.kt:17-22` / §4.6 迁移段 |
| C5 | 异常与降级 | ✅ | §4.5 六种失败（SAF/401/冲突/网络/未知/磁盘空间）均有状态机转移与通知策略；§4.3 try/catch 按类型分派 |  |
| C6 | 工程风险 | ⚠️ | `PullOutcomeWithRaw.repoSnapshot: Repository` 透出到 Domain interface（`GitRepository` 是 Domain 合约），**类型系统层面已经破防**；§6.2 "关键决策"用约定规避（"仅限 classifier 同步作用域内"），但研发可能在 UseCase 里意外保留引用——这在 R-2 "JGit 资源泄漏" 下属高风险。建议改为：`pullWithMergeResult` 内部完成分类后仅返回 `PullOutcomeClassified(raw: MergeResult, classification: ConflictClass, commitsPulled: Int)`，classifier 注入到 Data 层调用（或作为参数传入） | §6.2 `PullOutcomeWithRaw` / §4.9 R-2 |
| C7 | 安全与可测试性 | ✅ | `ConflictClassifier` 纯函数可单测（R-5 说明六种 `MergeStatus` 覆盖）；`RunSyncUseCase` 含 `Clock` 注入便于时间 mock；`WorkManagerTestInitHelper` 路径可行 |  |

---

### 六、Harness 实践评审（LLM 交互设计）

**不适用**。本迭代纯工程改动（WorkManager / Room / Notification / SAF / JGit），未涉及 Prompt 注入、Skill 定义、Agent 行为约束或 LLM 输出格式规范。D1–D7 全部标注 N/A。

---

### 七、关键问题清单（按优先级排序）

> 只列 ⚠️ 和 ❌ 项，按对"研发计划和任务拆解"的影响排序。

| 优先级 | 编号 | 问题摘要 | 维度 | 影响 | 建议修改方向 |
|--------|------|---------|------|------|-------------|
| **P0** | I-1 | `RunSyncUseCase` catch 的 `AuthFailedException` / `NetworkException` 在代码中**不存在**；`e.sanitizedMessage()` 扩展亦不存在 | C1 | 阻塞 `RunSyncUseCase` 实现，状态机（§4.5）分流依据直接失效 | 在迭代 2 §4.2 或 §6.2 新增：①`sealed interface SyncErrorKind { data object Auth; data object Network; data class Unknown(...) }`；②修改 `JGitExceptionSanitizer` 输出 `SanitizedGitException` 时额外携带 `kind: SyncErrorKind`（基于 HTTP 状态码 / 异常类名启发式分类：`TransportException` + "401/403" → Auth；`NoRouteToHostException`/`UnknownHostException`/`SocketException` → Network；余下 → Unknown）；③`RunSyncUseCase` 改按 `when (sanitized.kind)` 分派，统一用 `sanitized.message` 作文案来源（已脱敏）。对齐黄金法则 R8（"UI/日志前的异常必须走显式分派 + sanitizer 兜底"） |
| **P0** | I-2 | `RepoBindingRepository.currentOrNull()` / `GitRepository.pullWithMergeResult` + `commitAllIfDirty` 在 §6.2 只列了新签名，未说明原签名是替换还是并存，且 username / authorName / authorEmail 获取链路断裂 | C2/B2 | 阻塞 Worker 端到端 pull/commit/push 链路实现 | §6.2 显式列出：①`RepoBindingRepository` 新增 `suspend fun currentOrNull(): RepoBinding?`（等价于 `observe().first()`）；②`GitRepository` 的 `pullWithMergeResult` / `commitAllIfDirty` **替换**原 `pull` / `commitAll`（或并存迁移）；③新增 `CredentialRepository.snapshotIdentity(): Pair<String, String>?`（返回 username + email 供 Worker 用，等价于 `observe().first()`）或约定 `RunSyncUseCase` 在 `try` 块内先 `credRepo.observe().first()` 拿 identity，再 `loadPatOnce()` 拿 pat；`§6.3` 跨层矩阵补"username/email"行 |
| **P1** | I-3 | `PullOutcomeWithRaw.repoSnapshot: Repository` 将 JGit 原生引用透出到 Domain interface，类型系统层面与 "禁止透出" 约定互相矛盾，违反 R-2 资源泄漏防线 | IC6/C6 | 增加集成/泄漏风险；评审通过后研发易在 UseCase 内无意持有 `Repository` 引用 | 将 classifier 的调用下沉到 `GitRepositoryImpl.pullWithMergeResult` 内部：`Git.open(dir).use { git -> val raw = git.pull()...call(); val klass = classifier.classify(raw, git.repository); PullOutcomeClassified(klass, commitsPulled, conflictPaths) }`；`GitRepository` 接口返回 `PullOutcomeClassified`（纯数据，无 JGit 引用），`ConflictClassifier` 注入到 `GitRepositoryImpl`；`RunSyncUseCase` 不再调用 classifier。同时更新 §6.3 "边界过滤点"第 4 条文案 |
| **P1** | I-4 | `DiagnosticsLogger` 当前为 256KB 一次性截断的单文件，无时间轴；§4.7 / A9b 断言"导出 7 天内 `diagnostics.log`"验收不可执行 | C2 | 阻塞 G9 / A9b 验收 | 二选一：①Spec 升级 `DiagnosticsLogger` 为按日滚动（`logs/diagnostics-YYYY-MM-DD.log` + 启动时 prune 7 天外），ExportLogsUseCase 打包最近 7 个文件；或 ②A9b 降级为"打包当前 `diagnostics.log` 内容（可能 < 7 天）+ 近 500 条 `sync_log`"，移除 7 天约束。推荐 ①（成本低，10 行代码） |
| **P1** | I-5 | `RepositoryEntity.syncPolicyId` 引用 `SyncPolicyEntity.id` 但未声明 `@ForeignKey`，与 `SyncLogEntity` 风格不一致；且 `SyncPolicyEntity` 默认 id=0 自增与 `RepositoryEntity.syncPolicyId` 默认值、迁移时如何"先插 policy 再插 repo"的顺序未定义 | C2/B2 | 增加 Room 迁移风险 | §4.6 补 `@ForeignKey(entity = SyncPolicyEntity::class, parentColumns = ["id"], childColumns = ["syncPolicyId"], onDelete = ForeignKey.RESTRICT)` 及索引；§4.6 "迁移策略"段新增一步："迁移时先 `INSERT` 默认 `SyncPolicyEntity`（DEFAULT 值），拿到 id 后再 `INSERT RepositoryEntity`（`syncPolicyId = 该 id`）" |
| **P1** | I-6 | `ConflictClassifier` 伪代码调用 `isBinaryByAttribute` / `isBinaryByFirst8KB` helper 但无签名/定义 | B4 | 降低可拆解性；不同研发实现会发散 | §4.2 补两行签名：`private fun isBinaryByAttribute(repo: Repository, path: String): Boolean`（读 `AttributesNodeProvider` → `attributes(path).get("binary").isSet`）+ `private fun isBinaryByFirst8KB(repo: Repository, path: String): Boolean`（`TreeWalk.forPath(repo, path, headTree).objectId(0)` → `repo.open(id).openStream().use { readNBytes(8192).any { it == 0.toByte() } }`）；注明 `TreeWalk` / `ObjectReader` 资源 `use{}` 释放 |
| **P2** | I-7 | §4.7 "迭代 1 四操作按钮在 `PAUSED_*` 下仍可手动执行但不解除暂停"——与 §4.5 "状态未由用户手动解除前不尝试任何 push" 存在表面张力 | IC5/A8 | 验收时歧义；用户点手动 push 是否触发 push 需澄清 | §4.7 补一行："手动按钮（Clone/Pull/Commit/Push）调用原 UseCase 链路（不经 `RunSyncUseCase`），不读写 `syncState`；其语义是'故障诊断 / 手动验证'，与 Worker 自动链路独立"；或声明"手动按钮在 `PAUSED_*` 下置灰，统一引导走'恢复同步'" |
| **P2** | I-8 | DataStore→Room 迁移后，迭代 1 的 `SafUriStore` / `RepoBindingRepositoryImpl` 生命周期未定义（保留作为缓存？淘汰？） | C4 | 实现时可能出现"双源 observe"不一致 | §4.6 "迁移策略"段追加："迁移完成后，`RepoBindingRepositoryImpl` 内部 `observe()` **全部切换为读 Room `RepositoryDao`**；`SafUriStore` 保留但仅作为迁移源，一次性迁移后 `clear()` 并在代码注释中标注 `@Deprecated` 等迭代 3 删除" |
| **P2** | I-9 | `ConflictClassifier` 放在 `domain/service/` 但依赖 JGit 原生类型，与同目录其他纯 Kotlin 服务（`DebounceGuard`、`NotificationPublisher`）分层标准不齐 | C3 | 分层洁癖；不影响可拆解 | 二选一：①接受"Domain 允许持有 JGit 类型作为入参但不持有引用"并在 §4.1 添一句说明；或 ②将 `ConflictClassifier` 移到 `data/git/`，`GitRepositoryImpl` 直接注入（与 I-3 修复方向天然一致，推荐合并修） |
| **P2** | I-10 | §4.9 "Worker 冷启动预算 200ms" 是经验值还是实测？`Dispatchers.IO` 上的 Room first-access 冷启动是否能命中？ | B5 | 可操作性低 | NF2 已覆盖端到端 15s，可删掉"Worker 冷启动预算"一行；或把 200ms 写入 NF 段作为可监测项 |

---

### 八、综合评价

**总体评分**：🟡 良（修完 P0 即可开工）

Spec 整体设计深度充分：目标-解法双向对齐、方案对比含量化数字、跨层字段矩阵完整、验收标准可执行性高、风险缓解具体。主要问题集中在"接口扩展的完整性"——Spec 定义了新签名（`pullWithMergeResult` / `commitAllIfDirty` / `currentOrNull` / `AuthFailedException` / `NetworkException`）但没有同步更新对应的既有接口链路，导致 `RunSyncUseCase` 的伪代码在当前代码库中**不可直接实现**（需先补 5 个定义才能开工）。**最需优先解决**：(1) I-1 异常分派层缺失（直接阻塞 Worker 失败路径）；(2) I-2 Domain 接口扩展不完整（阻塞端到端 pull/commit/push 链路）。一个建设性收益较大的架构改动是 I-3（把 classifier 下沉到 Data 层），可一次性化解 IC6 / C6 / I-9 三个问题。

---

### 九、建议的下一步

1. **P0 修订**：针对 I-1 / I-2 更新 Spec §6.2 接口清单，新增 `SyncErrorKind` sealed interface 设计 + `SanitizedGitException.kind` 扩展；补充 `RepoBindingRepository.currentOrNull()` / `CredentialRepository.snapshotIdentity()` 两个签名；明确 `pullWithMergeResult` / `commitAllIfDirty` 与原 `pull` / `commitAll` 的替换或并存关系。
2. **P1 修订**：执行 I-3 的架构调整（classifier 下沉到 Data 层、`PullOutcomeClassified` 纯数据返回），同步修改 §6.2 / §6.3；决定 I-4 的日志策略方向（推荐按日滚动）；补 Room `@ForeignKey`（I-5）；补 `ConflictClassifier` 两个 helper 签名（I-6）。
3. **P2 修订**：澄清手动按钮与状态机关系（I-7）、DataStore→Room 迁移后双源策略（I-8）、ConflictClassifier 分层（I-9，可与 I-3 合并）。
4. **文档状态流转**：P0 修订完成后由 `/spec_review` 复评，状态更新为 `评审完成`；当前保持 `评审中`。
5. **知识沉淀**：将 I-3（"JGit 原生引用透出到 Domain 接口"）沉淀为新反模式 P6 到 `docs/retro/patterns.md`；将 I-4（"滚动日志需要按日文件而非单文件截断"）作为经验教训备查；将 I-2（"Spec 接口扩展必须与既有链路显式对齐"）作为黄金法则 R9 候选。

---

## 🔧 修订闭环（2026-05-02）

**评审对象版本升级**：v1.0 → v1.1；**文档状态**：`评审中` → `评审完成`

本次评审发现的 2 个 P0、6 个 P1、2 个 P2 共 10 个问题，已在 Spec v1.1 中全部闭环修订。变更概要见 Spec §10 v1.1 变更记录；逐问题映射如下：

| 编号 | 问题 | 修订位置 | 修订摘要 |
|------|------|----------|---------|
| I-1 (P0) | `AuthFailedException` / `NetworkException` 不存在 | §4.2 / §4.3 / §4.5 / §6.2.3 / §6.3 / A11 | 引入 `SyncErrorKind { Auth / Network / Unknown }` sealed interface；`SanitizedGitException.kind` 原地扩展；`classifyKind()` 启发式；`RunSyncUseCase` 改 `when (e.kind)` 分派 |
| I-2 (P0) | Domain 接口扩展不完整（username/email/currentOrNull） | §6.2.1 | `RepoBindingRepository.currentOrNull()` + `CredentialRepository.snapshotIdentity()` 显式列出；`pullAndClassify` / `commitAllIfDirty` 显式声明**与原方法并存**（不替换）；`RunSyncUseCase` 调用链路补全 |
| I-3 (P1) | JGit 原生引用透出 Domain | §4.1 / §4.2 / §4.3 / §6.1 / §6.2.3 / §6.3 / A11d / R-2 | `ConflictClassifier` 下沉至 `data/git/` 并标 `internal`；返回纯数据 `PullOutcomeClassified`；CI 静态 grep `import org.eclipse.jgit` 在 `domain/` / `ui/` 零命中 |
| I-4 (P1) | `DiagnosticsLogger` 单文件截断无 7 天语义 | §4.9.1 / §6.1 / §6.3 / A9b / A9e | 升级为按日滚动 `diagnostics-YYYY-MM-DD.log`（64 KB / 日上限 + 7 天 prune）；新增 `snapshotRecentLogFiles()`；兼容迭代 1 遗留单文件一次性迁移 |
| I-5 (P1) | Room `@ForeignKey` 缺失 + 迁移顺序未定 | §4.6 | `RepositoryEntity.syncPolicyId` 补 `@ForeignKey(entity = SyncPolicyEntity::class, onDelete = RESTRICT)` + Index；迁移顺序 "先 INSERT policy 再 INSERT repository" 明确 |
| I-6 (P1) | classifier helper 未定义 | §4.2 | 补 `isBinaryByAttribute` / `isBinaryByFirst8KB` / `isAncestor` 完整实现要点（RevWalk / TreeWalk / ObjectReader 资源 use 释放） |
| I-7 (P2) | 手动按钮与状态机语义张力 | §4.7 | 显式声明"手动按钮调用迭代 1 原 UseCase、不读写 `syncState`、不落 `SyncLog`、手动成功不自动解除 `PAUSED_*`" |
| I-8 (P2) | DataStore→Room 迁移后双源策略 | §4.6 | `SafUriStore` 标 `@Deprecated` 仅作迁移源；`observe()` 切换至 `RepositoryDao`；新写入只落 Room；连续 3 次迁移失败降级为"引导重新绑定" |
| I-9 (P2) | classifier 分层不齐 | §4.1 | 合并入 I-3 修订：classifier 移至 `data/git/`，Data 层 `internal class` |
| I-10 (P2) | "200ms 冷启动预算" 可操作性低 | §4.9 | 删除该条；NF2 端到端 < 15s 已覆盖 |

**新增验收与风险项**：
- **A11**：异常分派 + 架构边界 4 项验收（含 CI 静态 grep 兜底）
- **R-11**：`SyncErrorKind` 分类启发式误判风险及 JGit 版本升级回归

**新增自检项**：
- **SC15** 由 ⚠️ 转 ✅（技术负债 P6 已沉淀至 `docs/retro/patterns.md`）
- **SC16** 新增：架构边界——Domain 层不持有任何 Data 层原生类型；异常通道统一走 sanitizer

**结论**：全部 P0 / P1 / P2 已闭环，Spec v1.1 **可作为研发拆解输入**。
