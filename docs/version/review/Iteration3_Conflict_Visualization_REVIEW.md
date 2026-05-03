# 迭代 3 Spec 评审报告

**文档**：`docs/version/Iteration3_Conflict_Visualization_SPEC.md`
**版本**：v1.0
**评审日期**：2026-05-03
**评审人**：alexjhwen（via `/spec_review`）
**评审基准**：`AGENTS.md` + `docs/version/BOUNDARIES.md` + `docs/retro/golden-rules.md`（R1–R10 + P1–P7）+ 现有源码（迭代 1 / 2 交付物）

---

## 一、结构完整性

| 章节 | 状态 | 备注 |
|------|------|------|
| 文档信息（版本/日期/前置依赖） | ✅ 完整 | 版本、作者、日期、目标、前置依赖 4 项齐备 |
| 背景与目标 | ✅ 完整 | §2.1 当前状态 + §2.2 调研 + §2.3 目标 + §2.4 非目标，结构完备 |
| 当前状态 / 问题分析 | ✅ 完整 | §2.1 列出 4 项"尚未实现的关键能力"，指向明确 |
| 非目标 | ✅ 完整 | §2.4 列出 7 项，边界清晰 |
| 方案决策（对比 + 选型） | ✅ 完整 | §3.1 含 3 组对比表（SSH / 目录树 / 冲突 UI）均带量化数字 |
| 详细设计 | ⚠️ 不足 | §4 结构完整但多处引用**不存在**的接口/类/Screen 名（见 C3 / C1）|
| 数据模型 / 接口定义 | ⚠️ 不足 | §6.1 Migration 缺少 `Repository.auth_type` 列扩展；§6.2 表格接口名与现状错位 |
| 交互流程 / 用户流程 | ⚠️ 不足 | §5.2 流程 A 依赖通知跳转 `ConflictResolveScreen`，但现有通知 `NAV_*` 未扩展 |
| 跨层字段传递 | ⚠️ 不足 | §6.3 最后一行 "`Repository.auth_type` 列" 在现有 `RepositoryEntity` 不存在，且表格未暴露 `SyncStateRepository.clearConflictPause` 的字段来源 |
| 验收标准 | ✅ 完整 | F1–F5 + NF1–NF5 覆盖功能 + 非功能，含可执行性检查（G8） |
| 风险与缓解 | ✅ 完整 | R-1 ~ R-6 覆盖 SSH / 性能 / 编码 / 状态机 / TOFU / 体积 |
| 变更记录 | ✅ 完整 | §10 初版一条，符合规范 |

---

## 二、方案内部一致性

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| IC1 | 目标→解法覆盖 | ✅ | G1→P3.1 / G2→P3.2 / G3→P3.3 / G4→P3.4 / G5→§4.6+NFR 一一对应，无缺口 |
| IC2 | 解法→目标溯源 | ✅ | §4 各 Phase 可追溯；无"提出解法但无目标"的冗余 |
| IC3 | 非目标边界一致 | ✅ | §2.4 声明的 7 项非目标在详细设计中均未出现（行级 chunk、Diff 编辑、多分支、Hardware Key、.gitignore、Blame、分类器重构） |
| IC4 | 迭代边界一致 | ✅ | 与 `BOUNDARIES.md` 第 3 行摘要（`FileTreeCache` 目录树 + 单栏 Diff + 整文件二选一 + SSH ed25519）一致 |
| IC5 | 冗余内容检测 | ⚠️ | §4.2.1 `DiffSource.COMMIT_VS_COMMIT` 标注"保留扩展，当前不开放给 UI"——本迭代非目标却写入 sealed enum，属轻度前瞻冗余（低优先级，仅 P2） |
| IC6 | 前后表述一致 | ❌ | **与代码现状不一致**是文档自身一致性的重灾区：§5.1 将 `RepoDetailScreen` 和 `BindRepoScreen` 标为"既有"，但代码中两者**都不存在**（`app/src/main/java/.../ui/` 下只有 `home / audit / policy`）；§4.4.2 与 §6.2 反复使用 `PullRepository / PushRepository / CloneRepository`，但现有代码只有单一 `GitRepository`；§4.3.1 引用 `SyncStateRepository`，代码中完全不存在此接口。前述术语在 Spec 内部虽前后一致，但全部与现实错位，导致研发按 Spec 拆任务时"找不到挂载点" |

---

## 三、合理性评审

**总评**：🟡 良

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| A1 | 问题-方案匹配 | ✅ | 目录树/Diff/冲突解决/SSH 四块能力恰对 §2.1 的四个"尚未实现"缺口 |
| A2 | 方案对比充分性 | ✅ | 3 组对比含 APK 体积（2.8 vs 0.6 MB）、CVE 数、帧率（55 vs <30）、内存（800 KB vs 3.2 MB）、操作耗时（30 s vs 90 s），量化充分 |
| A3 | 迭代边界遵守 | ✅ | 严守 `BOUNDARIES.md` 摘要；未引入多分支 / 行级 chunk / Diff 编辑 |
| A4 | 复用优先原则 | ✅ | 复用 ESP（§4.4.1）、复用 `PushRepository`（§4.3.1 步骤 5）、复用 `DiagnosticsLogger`、复用 `JGitExceptionSanitizer`（R8）；新增模块均有必要性 |
| A5 | 非目标合理性 | ✅ | 7 项非目标均呼应总方案 §4.5 / §10 / §9，排除理由充分 |
| A6 | 前置依赖 | ⚠️ | §1 声明前置"迭代 2 的 `ConflictClassifier / PAUSED_*` 状态机"，但未说明 **`ConflictResolveScreen` 如何从通知跳转**——现有 `NotificationPublisherImpl` 的 `NAV_*` 只有 `NAV_AUDIT / NAV_RESUME`，新增路由隐式依赖未声明 |
| A7 | 目标-解法一致性 | ✅ | Phase 内所有子任务都服务于 G1–G5；无"顺手改造" |
| A8 | 冗余度 | ⚠️ | `DiffSource.COMMIT_VS_COMMIT`（§4.2.1）明确"当前不开放"，属 IC5 冗余；此外 `ResolutionChoice.SKIP`（§4.3.1）的语义（"跳过文件在二次确认弹窗中列警告"）虽合理但拉长了状态机闭环——若 Skip 文件保留在冲突态，UI 侧"已本地解决"与"未解决" 的断言成立性需要更细刻画（中优先级） |

---

## 四、清晰度评审

**总评**：🟡 良

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| B1 | 术语一致性 | ✅ | 文档内 `FileTreeNode / DiffLine / ConflictFile / SshKeyPair` 等术语全局一致；`PAUSED_CONFLICT / CLEAR` 用词统一 |
| B2 | 接口定义精确性 | ⚠️ | `SshKeyRepository.generate(passphrase: CharArray?)` 返回 `SshKeyPair`，但 `SshKeyPair.keyId` 声明为"对应 encrypted/cred_ 的 suffix"——与 §6.1 新增的 `encrypted/ssh_<keyId>` 键位不一致（B1/B2 交叉），需统一 |
| B3 | 流程可追踪性 | ⚠️ | §4.3.1 步骤 7 "Push 失败 → 保留 PAUSED_CONFLICT"——但 §4.3.1 同时写"Push 成功 → clearConflictPause"；这与 ResolveRequest 含 `ResolutionChoice.SKIP` 的语义叠加后，**"文件已 commit 但有 SKIP 的情况下 PAUSED_CONFLICT 是否已清除"不明确**（若 Skip 文件仍处于未 resolve 状态，commit 本身就不应走 `CheckoutCommand.Stage.*`，只是保留原 index——流程 2/3 步骤需补清晰规则） |
| B4 | 代码示例可理解性 | ⚠️ | §4.4.2 `setKeyPasswordProvider { _, _, _ -> passphraseCache.get(repoId) }` lambda 使用三参数形式匹配 `KeyPasswordProvider.getPassword`，但返回值是 `CharArray?` 还是 `String`？Spec 未点明；MINA SSHD 2.13.x 该接口返回 `String`，而 `SshPassphraseCache` 持 `CharArray`（R3 合规），两者需适配层，Spec 未提 |
| B5 | 验收标准可操作性 | ✅ | F1–F5 每条含输入（10k 文件仓库 / >10k 行 diff / PNG）+ 操作步骤 + 预期输出，可直接转测试用例 |
| B6 | 任务可拆解性 | ✅ | §4.0 P3.1–P3.4 的 Phase 边界清晰，P3.4 可独立并行，依赖关系明确 |
| B7 | 任务粒度与依赖 | ✅ | 每 Phase 预估 Kt 文件数（10/8/6/8）量级合理；依赖 P3.1→P3.2→P3.3 呈线性，P3.4 并行 |

---

## 五、可行性评审（代码验证）

**总评**：🔴 差

| # | 检查项 | 评分 | 发现 | 代码依据 |
|---|--------|------|------|---------|
| C1 | 代码可达性 | ❌ | **关键挂载点不存在**：①`RepoDetailScreen`（§5.1、§4.1.2 "浏览仓库"按钮注入点）；②`BindRepoScreen`（§4.4.3、§5.1 "认证方式 Radio"注入点）——代码中 UI 目录只有 `home / audit / policy` 三个 Screen。没有这两个页面，P3.1 / P3.4 的用户入口链路无法落地 | `app/src/main/java/com/example/simplygit/ui/` 实际目录列表 |
| C2 | 改动完备性 | ❌ | **`Repository.auth_type` 列被 Spec 多处使用（§5.1、§6.3、§4.4.2）但 §6.1 Migration v2→v3 只 CREATE `file_tree_cache`，未 ALTER `repository` 表加 `auth_type`**。即便沿用现有 `authRef` 列（目前值恒为 "github_pat"），Spec 也没说明如何区分 PAT / SSH。必须：①在 `RepositoryEntity` 加 `authType: String` 字段 + Migration `ALTER TABLE repository ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'PAT'`；②或显式声明复用 `authRef` 的取值空间（PAT / SSH_<keyId>） | `RepositoryEntity.kt:33-47` 无 `authType` 字段；`SimplygitDatabase.kt:27 version=2` + `MIGRATION_1_2` 无对应 ALTER |
| C2 | 改动完备性（续） | ❌ | **`SyncWorker.doWork()` 末尾注入 `fileTreeRepository.rescan()`**（§4.1.1 触发时机 2）——但代码中**没有名为 `SyncWorker` 的类**，实际是 `GitSyncWorker`（`runtime/GitSyncWorker.kt`）；此外"rescan 失败不影响 Worker 结果"需要在 `GitSyncWorker` 内明确 try/catch 包装，Spec 未说明 | `runtime/GitSyncWorker.kt` 实际类名 |
| C3 | 命名与路径一致性 | ❌ | **`PullRepository / PushRepository / CloneRepository` 这三个接口在代码中不存在**，实际是统一的 `GitRepository`（`domain/repository/GitRepository.kt:23`）。Spec §4.4.2、§6.2、§3.2 反复以这三个名字出现，导致研发拆任务时找不到修改锚点；必须改写为 `GitRepository` 的签名扩展（或显式声明"新增 3 个接口替代 GitRepository"——但那是破坏性重构，和 §4.4 "签名不变"自相矛盾） | `GitRepository.kt:23-57` 只有单一接口 |
| C3 | 命名与路径一致性（续） | ❌ | **`SyncStateRepository` 在代码中完全不存在**。Spec §4.3.1 / §4.4.2 多次引用 `SyncStateRepository.clearConflictPause / pauseConflict / resumeFromPause`，实际状态变更由 `SyncLogRepository.updateSyncState(repoId, state)` + `pauseAndFinish(...)` 完成（`SyncLogRepository.kt:42-52`）；`resumeFromPause()` 其实是 `ResumeFromPauseUseCase`（UseCase 层，非 Repository）。Spec R9 声明"与 resumeFromPause() 并存"属虚构 | `ResumeFromPauseUseCase.kt:16-24` |
| C3 | 命名与路径一致性（续） | ❌ | **`PushOutcome` 类型在代码中不存在**。Spec §4.3.1 `ResolveResult.Success(val pushResult: PushOutcome)` 字段类型虚构；现状 Push 的返回是 `GitOpResult`（`GitOpResult.kt:8-12`），Failure 时 cause 是 `SanitizedGitException` | `GitOpResult.kt` + `JGitDataSource.push` 返回 `Result<Unit>` |
| C3 | 命名与路径一致性（续） | ⚠️ | **`CredentialRepository` 扩展方向错位**：Spec §4.4.1 说"新增 `saveSshKey(...)` 与既有 `savePat()` 并存"，但现状方法名是 `save(username, email, pat)`（非 `savePat`）（`CredentialRepository.kt:37`）；Spec §6.2 说"`readCredential()` 返回类型扩展为 `sealed Credential`"，但代码中根本没有 `readCredential()` 方法（有 `observe(): Flow<CredentialPublicView?>` 和 `loadPatOnce(): CharArray?`）。改动方案命名的基点全部错位 | `CredentialRepository.kt:27-40` |
| C3 | 命名与路径一致性（续） | ⚠️ | **`encrypted/cred_<repoId>` 键位与现状不符**：Spec §6.1 说"既有 PAT 沿用 `cred_<repoId>`"，但 `EncryptedCredentialDataSource` 当前只用固定 key `github_pat / github_username / github_email`，**不按 repoId 分桶**（`EncryptedCredentialDataSource.kt:84-86`）。单仓 N4 契约下这是合理简化，但 Spec 把它当"既有"显然是错的——若要引入 SSH 多 key 管理同时保留单一 PAT，键位设计需要重做（统一带 repoId 还是维持全局） | `EncryptedCredentialDataSource.kt:84-86` |
| C4 | 向后兼容性 | ⚠️ | §6.2 声明 "`readCredential()` 返回类型由 `Credential.Pat?` 改为 `sealed Credential?`；既有 `Credential.Pat` 保持二进制兼容"——但现状 `Credential` 是普通 `class`（非 sealed，无 `Pat` 子类）（`domain/model/Credential.kt:15`）；该条迁移前提不成立 | `domain/model/Credential.kt:15-35` |
| C5 | 异常与降级 | ✅ | 4 个新异常全部入 `JGitExceptionSanitizer` 白名单（§6.2），与 R8 / P5 对齐；SSH `TofuServerKeyDatabase` 拒绝场景降级为 `PAUSED_AUTH` 清晰 |
| C6 | 工程风险 | ⚠️ | 现有 `RunSyncUseCase.finally` 只对 PAT `CharArray` 做 `Arrays.fill`（`RunSyncUseCase.kt:185-187`）；SSH 路径 PAT 为 null 时的 finally 仍会无害 noop，但若 SSH passphrase 也需同样处理（R3），需在 `GitSshSessionFactory` 的 provider 回调中做；Spec §4.4.1 声明"内存 10 min 过期"但未明确清理由哪个 scope 调度（R7 合规性） |
| C7 | 安全与可测试性 | ✅ | TOFU known_hosts 策略 + `FLAG_SECURE`（§4.6）+ Logcat 无私钥字符串（NF3）—— 验收明确；`DiffRepository` 接口化便于 mock |

---

## 六、Harness 实践评审（LLM 交互设计）

**总评**：不适用（本迭代不涉及 Prompt / Skill / Agent 注入，§4.5 已显式标注 N/A）

---

## 七、关键问题清单（按优先级排序）

> ❌/⚠️ 项共 13 条（含 IC6 + 6 组 C3 子项）。严格按"能否拆出好任务"排序。

| 优先级 | 编号 | 问题摘要 | 维度 | 影响 | 建议修改方向 |
|--------|------|---------|------|------|-------------|
| **P0** | P0-1 | **`PullRepository / PushRepository / CloneRepository` / `SyncStateRepository` 四个虚构接口遍布 §4 / §6**，代码中只有统一的 `GitRepository` + `SyncLogRepository.updateSyncState`，研发无从下手 | C3 / IC6 | 阻塞任务拆解 | 全文替换：①"认证分派下沉到 Data 层"改写为"**在 `GitRepositoryImpl` / `JGitDataSource` 的 `clone/pull/push` 方法内**根据 `binding.authType` 切换 `TransportConfigCallback`"；②`SyncStateRepository.clearConflictPause(repoId)` 改为"**新增 `ClearConflictPauseUseCase`**（对 `SyncLogRepository.pauseAndFinish` 的包装，写 `SyncResult.CONFLICT_RESOLVED` + `SyncState.IDLE`）"；§4.3.1 / §6.2 / §3.2 / §4.4.2 均需同步修订 |
| **P0** | P0-2 | **`RepoDetailScreen` / `BindRepoScreen` 在 §5.1 被标注为"既有"但代码中不存在**，UI 挂载点断链，P3.1 "浏览仓库" 与 P3.4 "认证方式 Radio" 无处可挂 | C1 / IC6 | 阻塞任务拆解 | 二选一：①在本迭代把"浏览仓库" / "认证方式"直接挂到**现有 `HomeScreen`**（仓库卡片内"浏览"按钮 + 设置入口内"SSH 密钥管理"），流程 B 增加一步"从设置跳回首页卡片选 SSH 认证"；②显式把 `RepoDetailScreen / BindRepoScreen` 的**新建**也纳入本迭代范围（§4.0 Phase 拆分新增 P3.0 或并入 P3.1），并更新任务量估 |
| **P0** | P0-3 | **`Repository.auth_type` 列 Spec 多处引用但 §6.1 Migration 未 ALTER**，P3.4 SSH 认证分派的持久化基础缺失 | C2 | 阻塞任务拆解 | §6.1 Migration `MIGRATION_2_3` 追加：`db.execSQL("ALTER TABLE repository ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'PAT'")`；`RepositoryEntity` 增加 `authType: String = "PAT"` 字段并在 `§6.3` 跨层矩阵补充该字段的来源（`BindRepoUseCase` 入参 → DAO 列） |
| **P0** | P0-4 | **`CredentialRepository` 扩展基点错位**：既有方法名/签名与 Spec 描述完全不一致（无 `savePat`、无 `readCredential`、键位非按 repoId 分桶） | C3 | 阻塞任务拆解 | §4.4.1 重写：①保留现有 PAT 单实例 `EncryptedCredentialDataSource`（key = `github_pat`），**SSH 走独立 `SshKeyDataSource`**（key = `ssh_<keyId>`），不强制把 PAT 也改为 `cred_<repoId>`；②`CredentialRepository` 不扩展，**新增独立的 `SshKeyRepository`**（已在 §4.4.1 体现，只需删去 `CredentialRepository.readCredential()` 改造条款）；③§6.2 表格移除 `CredentialRepository` 行 |
| **P0** | P0-5 | **通知跳转 `ConflictResolveScreen` 链路未设计**：现有 `NAV_AUDIT / NAV_RESUME` 不覆盖冲突解决页；§5.2 流程 A 第 1 步"通知 → ConflictResolveScreen"是断的 | A6 / B3 | 阻塞任务拆解 | 新增 `NAV_CONFLICT` 常量 + `NotificationPublisherImpl.publishConflict` 的 `navKey` 改派 + `MainActivity` 处理 nav extra 跳转 `ConflictResolveScreen`——这三点必须显式落到 §4.3.2 或新增 §4.3.3 |
| **P1** | P1-1 | **`PushOutcome` 类型虚构**：`ResolveResult.Success(val pushResult: PushOutcome)` 返回类型无处定义，现状 Push 只返回 `GitOpResult` | C3 | 增加集成风险 | §4.3.1 改为：`data class Success(val committedFiles: Int, val pushResultOk: Boolean)` 或复用 `GitOpResult`；§6.3 矩阵对应行同步调整 |
| **P1** | P1-2 | **`SyncWorker` 类名错位**：§4.1.1 触发时机 2 引用不存在的 `SyncWorker`（实际 `GitSyncWorker`） | C3 | 增加集成风险 | 统一改为 `GitSyncWorker.doWork()`；并明确 rescan 调用用 `runCatching { fileTreeRepository.rescan(repoId) }` 吞掉异常，不影响 `Result.success()` |
| **P1** | P1-3 | **`SshPassphraseCache` 的生命周期/清理调度未落到具体 scope**（§4.4.1 只说 Application-scoped）；与 R7 "安全清理不绑 UI" 的合规性无法验证 | C7 / R7 | 增加集成风险 / 安全风险 | §4.4.1 补：`SshPassphraseCache` 内部用 `appScope`（`@Singleton` + `@ApplicationScope CoroutineScope` 注入），`put()` 后 `delay(10.min)` → `Arrays.fill(cached, '\u0000')`；进程重启时缓存自然丢失（需求语义），Spec 明示 |
| **P1** | P1-4 | **`ResolutionChoice.SKIP` 语义闭环不完整**：§4.3.1 Push 成功 → `clearConflictPause` 是否应**在存在 SKIP 时**不清除？"已本地解决，推送失败"与"部分 Skip"的状态组合在状态机中未穷举 | B3 | 增加集成风险 | 明确规则：①所有非 SKIP 文件 resolve 成功 + push 成功 + 有 SKIP → 保留 `PAUSED_CONFLICT`，UI 展示"剩余 X 个冲突待处理"；②所有 SKIP → 视为用户取消，状态不变；③无 SKIP + push 成功 → 清除；在 §4.3.1 步骤 6-7 展开成真值表 |
| **P1** | P1-5 | **`setKeyPasswordProvider` 回调签名与 API 不一致**（返回类型 String vs CharArray 适配层缺失）；MINA SSHD 2.13.x 接口返回 `List<String>` / `String`，R3 要求 `CharArray` | B4 / C3 | 增加集成风险 / 合规风险 | §4.4.2 补 `KeyPasswordProvider` 适配层伪码：`override fun getPassword(...) = passphraseCache.get(repoId)?.let { String(it) }.also { /* 提醒：MINA 内部仍会把 String 托管到 GC */ }`；或显式声明"SSH 场景 passphrase 暂以 String 传递，缓解措施是进程内仅 10 min 驻留 + `SecureRandom` key"，并把该例外写入 R3 补充条款 |
| **P1** | P1-6 | **commit message 绕过既有 `SyncPolicyModel.commitMessageTemplate`**：§4.3.1 直接硬编码 "SimplyGit: resolved N conflicts (ours=X, theirs=Y)"，未说明与策略模板的关系 | B3 / A4 | 锦上添花但影响一致性 | 说明"冲突解决的 commit 走**独立硬编码模板**（不参与 `%ISO%` / policy 替换），因为它是用户意图而非静默同步"——在 §4.3.1 加一句即可；或扩展 `SyncPolicyModel` 增加 `conflictResolveMessageTemplate` |
| **P1** | P1-7 | **现有 `Credential` 类不是 sealed，无 `Credential.Pat` 子类**（§6.2 迁移前提不成立） | C3 / C4 | 增加集成风险 | 若保留 P0-4 的"不扩展 `CredentialRepository`"建议，本条自然消解；否则需在 §6.2 补一条前置重构："迭代 3 先把 `Credential` 类重构为 `sealed interface Credential { class Pat; class Ssh }`"，计入 P3.4 任务量 |
| **P2** | P2-1 | `DiffSource.COMMIT_VS_COMMIT` 在本迭代无消费方却进入 sealed enum | IC5 / A8 | 锦上添花 | 删除该枚举值，待实际需要时再扩展；或改为 `@VisibleForTesting` 注释说明 |

---

## 八、综合评价

**总体评分**：🟠 中

Spec 在**方案选型与调研**（§2.2 / §3.1）、**非目标边界**（§2.4）、**验收标准的可执行性**（F3 覆盖"下一次周期同步正常执行"、F4 覆盖 Push 实际成功）、**R3/R4/R5/R8 等项目规则对齐**（§9 设计自检）四项上质量明显高于平均水位，体现了成熟的方案脑图。**但严重失分于可行性维度（C1/C2/C3 连续命中）——Spec 的改造锚点大量虚构**：`PullRepository/PushRepository/CloneRepository/SyncStateRepository` 四个接口 + `PushOutcome/SyncWorker/RepoDetailScreen/BindRepoScreen/Credential.Pat` 共 9 处代码实体在现状中并不存在，说明本 Spec 是**基于"预想的理想架构"而非"实际代码"**撰写，进入开发时研发会反复遇到"Spec 说的类找不到"的卡点，无法按 §4.0 的 Phase 拆任务。

**最需优先解决的两个核心问题**：
1. **P0-1 + P0-2 + P0-3 + P0-4 联动修订**：把 §4.3–§4.4 / §5.1 / §6.1–§6.3 全部以**现有 `GitRepository` 单接口 + `SyncLogRepository.updateSyncState` + `HomeScreen` 单页 + `authRef` 列**为基点重写挂载方案，保持原有功能目标不变但命名与现状一致；
2. **P0-5**：显式设计通知跳转 `ConflictResolveScreen` 的 `NAV_CONFLICT` 链路，闭合流程 A 的断点。

---

## 九、建议的下一步

1. **修订 Spec v1.1**，按 P0-1 ~ P0-5 五项通盘重写 §4 / §6 的接口/类/Screen 命名与 Room Migration；P1 项随刊修订；P2 项删除 `COMMIT_VS_COMMIT` 枚举值。
2. **修订完成后再次 `/spec_review`**，状态保持"评审中"，等 P0 闭环后再流转"评审完成（待开发）"。
3. 评审结论联动：把"Spec 挂载点命名必须以现有代码为基点（不得虚构接口/Screen/Entity）"提炼为新黄金法则 R11 候选（`docs/retro/golden-rules.md`），下一次评审触发时纳入加载；把"C3 命名路径虚构"提炼为新反模式 P8 候选。

---

## 十、v1.1 修订闭环记录（2026-05-03）

Spec 作者已按本报告 P0-1 ~ P2-1 共 13 条逐项修订 Spec 至 v1.1；逐项核对结果：

| 编号 | 闭环状态 | 定位（Spec v1.1 章节） | 要点 |
|------|---------|---------------------|------|
| **P0-1** | ✅ 闭环 | §4.3.1 / §4.4.2 / §6.2 | `PullRepository/PushRepository/CloneRepository/SyncStateRepository` 四个虚构接口全部移除；`GitRepository` 签名保持不变，认证分派下沉到 `JGitDataSource.applyAuth()`（内部按 `binding.authType` 切换 `TransportConfigCallback`）；`ClearConflictPauseUseCase` 以薄包装用例形式显式定义，与 `ResumeFromPauseUseCase` 并存语义完整说明 |
| **P0-2** | ✅ 闭环 | §4.4.3 / §5.1 / §5.2 | UI 挂载点改为现有 `HomeScreen / MainActivity / SimplygitNavHost` 扩展：仓库卡片加"浏览"按钮、`SyncStateBanner` `PAUSED_CONFLICT` 加"解决冲突"按钮、AppBar overflow 加"SSH 密钥"、绑定表单加"认证方式"Radio；不再提 `RepoDetailScreen / BindRepoScreen` |
| **P0-3** | ✅ 闭环 | §6.1 Migration v2→v3 (b) | `ALTER TABLE repository ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'PAT'`；`RepositoryEntity` 显式扩 `authType`；`RepoBinding` 同步扩 `authType / authRef` |
| **P0-4** | ✅ 闭环 | §4.4.1 / §6.1 / §6.2 | `CredentialRepository`、`EncryptedCredentialDataSource`、`Credential` 类**全部保持现状不动**；SSH 走独立 `SshKeyRepository + SshKeyDataSource + EncryptedFile`；PAT 键位 `github_pat` 保持不变 |
| **P0-5** | ✅ 闭环 | §5.1 / §4.3.2 | `NotificationPublisherImpl` 新增 `NAV_CONFLICT / EXTRA_REPO_ID`；`publishConflict` 改派；`MainActivity.Routes` 扩展 `CONFLICT = "conflict/{repoId}"`；`SimplygitNavHost.LaunchedEffect` 新增 `NAV_CONFLICT` 分支 |
| **P1-1** | ✅ 闭环 | §4.3.1 / §6.2 末尾声明 / §6.3 | `ResolveResult.Success(committedFiles, pushOk, remainingSkipped)`；`PushOutcome` 类型彻底移除 |
| **P1-2** | ✅ 闭环 | §4.1.1 触发时机 / §6.2 Runtime 行 | `SyncWorker` → `GitSyncWorker`；rescan 调用用 `runCatching` 包裹 |
| **P1-3** | ✅ 闭环 | §4.4.1 `SshPassphraseCache` | 显式声明 `@Singleton` + `@ApplicationScope CoroutineScope` 注入；清理协程不绑 UI（P4/R7 规避） |
| **P1-4** | ✅ 闭环 | §4.3.1 步骤 6 真值表 | 6 行 `(committedFiles × pushOk × remainingSkipped)` 状态处理真值表；覆盖"本地解决推送失败"/"部分 Skip"/"全 Skip"/"完全成功"四大场景 |
| **P1-5** | ✅ 闭环 | §4.4.2 `GitSshSessionFactoryProvider` + §6.1 R3 豁免点 | MINA SSHD `KeyPasswordProvider` 返回 `String` 作为显式 API 约束；String 仅在 provider 回调内一次性构造并返回 SSHD 内部；我方侧 cache 依然 `CharArray`；R3 豁免点声明 |
| **P1-6** | ✅ 闭环 | §4.3.1 步骤 4 | 冲突解决 commit message 独立硬编码模板，**不走** `SyncPolicyModel.commitMessageTemplate` |
| **P1-7** | ✅ 闭环（随 P0-4 消解） | §6.2 | `Credential` 类不改 sealed；此条前提不成立 |
| **P2-1** | ✅ 闭环 | §4.2.1 | `DiffSource.COMMIT_VS_COMMIT` 删除，仅保留 `WORKING_VS_HEAD / OURS_VS_THEIRS` |

**治理文档同步**：
- Spec 文档状态：`评审中` → `评审完成（待开发）`
- `docs/version/INDEX.md` 追加 v1.9 条目记录本次修订
- `docs/retro/golden-rules.md` R11 / `docs/retro/patterns.md` P8 沿用首轮评审已沉淀的条目，无需重复写入

**结论**：13 项问题全部闭环，Spec v1.1 可以进入 `/dev` 开发阶段；所有挂载点已逐一对齐现有代码（通过 `R11` 自检），研发可按 §4.0 Phase 拆分直接领任务。
