# 黄金法则

> 从历次评审和开发中提炼的正面规则。
> `/spec_review`、`/code_review` **仅加载「活跃规则」区域**，归档区域不加载。
> 活跃规则上限 15 条，超出时应将低频规则移入归档。
> 编号全局唯一递增（R1, R2, ...），归档后编号不回收。

## 活跃规则

> ⚡ 以下规则在每次评审/CR 时自动加载。保持精简。

### Spec 设计规则

- **R1. 方案对比表选型对象 = 落地实现对象**：§"方案决策/方案对比"中每个方案的命名与 §"详细设计"中的最终落地实现保持名称一致；若对比过程中发生降级替换（A → A'），在对比表下方显式说明原因，避免读者在"方案 A"与"最终实现 A'"间迷路。（来源：迭代 1）
- **R2. 偏离总方案必须显式标注"总方案变更"**：若 Spec 将总方案中声明为本 Phase 的能力排除/推迟/提前，必须在 Spec 开篇显式标注"总方案变更：本迭代将 X 调整到 Phase N"并同步修改总方案文档相应章节，否则视为边界越界。（来源：迭代 1，对应 `BOUNDARIES.md` 总体锚点条款）

### 代码实现规则

- **R3. 敏感凭证建模三件套**：①承载凭证的类禁用 `data class`（默认 `toString()` 泄漏字段），改用普通 `class` + 手写 `toString` 返回 redacted，或 `@JvmInline value class` 包装；②字段类型用 `CharArray` 而非 `String`，使用后 `Arrays.fill('\u0000')`；③禁止进入 `UiState`、`Log.*`、`Intent.Extra`、`Bundle`、异常堆栈。（来源：迭代 1，对应总方案 §5.1）
- **R4. 外部依赖引入四件套**：Spec 中引入新依赖时，必须在详细设计中给出完整清单：①`libs.versions.toml` 的 `[versions]`/`[libraries]` 增量；②`app/build.gradle.kts` 的 `implementation` 行；③必要的 `exclude`（如 JGit 的 `bcprov` 冲突）；④相关的 `compileOptions` 开关（如 `isCoreLibraryDesugaringEnabled = true` 与 `coreLibraryDesugaring(...)` 成对出现）。缺任何一项均视为 C2 改动完备性不足。（来源：迭代 1）
- **R5. AndroidManifest 改动清单独立成节**：Spec 若涉及需新增权限（`INTERNET`、`POST_NOTIFICATIONS` 等）、Provider、Service、Activity 属性（`FLAG_SECURE` 场景），必须在详细设计或数据模型中单列"AndroidManifest 改动清单"，逐行写明 XML 片段，避免研发遗漏运行时崩溃。（来源：迭代 1）

### 跨层契约规则

- **R6. JGit 原生类型字段必须追溯到持久化层**：若 Data 层 API 签名含 JGit 原生类型（如 `PersonIdent`、`ObjectId`、`RefSpec`），Spec 必须在 §"跨层字段传递矩阵"中追溯其每个组成字段（如 `PersonIdent` 的 `name`/`email`/`when`）的来源——是哪个持久化 key、哪个用户输入、还是哪个默认规则（如 `{username}@users.noreply.github.com`）。（来源：迭代 1）
- **R7. 安全清理的生命周期不跟 UI 绑**：定时/延迟清理敏感数据（剪贴板、缓冲区、临时文件）的协程必须挂到 `viewModelScope` 或 Application-scoped singleton，不得用 `rememberCoroutineScope` / `LaunchedEffect`。判别标准：若动作语义是"用户态的定时义务"（即使 UI 关掉也要完成），就不能绑 UI 生命周期。（来源：迭代 1 CR M-2）
- **R8. UI/日志前的异常必须走显式分派 + sanitizer 兜底**：Domain/UI 层不得直接使用 `Throwable.message` 呈现错误文案；必须经过 `sealed interface ErrorKind`（或同形态分派结构）：白名单域异常走本地化分支，其余统一走 sanitizer 包装后的 `Sanitized` 分支。`Result.Failure.cause` 在契约上仅允许 `SanitizedException` 或白名单类型。（来源：迭代 1 CR L-2）
- **R9. Spec 中的接口扩展必须与既有链路显式对齐**：Spec §"接口定义"新增 / 修改既有 Repository / UseCase 签名时，必须显式说明三件事：①新签名与原签名是**替换还是并存**（若并存，迁移窗口）；②新签名删减的参数（如 username / author）之**新来源**（从哪个 Repository 的新方法读，还是 UseCase 内部拼）；③新签名抛出的异常类型是否**已在代码中存在**（不存在则同步声明新异常或在 sanitizer 中补分类枚举）。缺任一项视为 C1/C2 改动完备性不足，阻塞研发拆解。（来源：迭代 2 I-1 / I-2）
- **R10. Spec 内部状态机 / UI 行为描述必须跨章节一致**：Spec 中跨 §"状态机规则" / §"UI 设计" 描述同一个状态（如 `BROKEN`）的可操作入口、文案、按钮时，两处必须一致。若状态机规则说"状态 X 只能由操作 A 清除"，UI 设计就必须在该状态下呈现 A 的入口，不能只给"诊断类"入口（如"查看日志"）而遗漏 A。Spec 评审需 grep 交叉核对，CR 阶段再兜底。（来源：迭代 2 CR P3-01）
- **R11. Spec 挂载点必须以现有代码为基点**：Spec 描述新增/修改的接口、类、Screen、Entity、Column、DI bean、Worker 名必须**逐一与现有代码对账**——要么精确命中现状（如 `GitRepository` / `SyncLogRepository.updateSyncState` / `GitSyncWorker` / `authRef`），要么显式声明"本迭代新建并纳入本 Spec 范围"。禁止使用"预想的理想架构命名"（如 `PullRepository / SyncStateRepository / PushOutcome / RepoDetailScreen`）当作"既有"挂载点。Spec 评审需 grep 代码库验证每一个被标注为"既有/扩展"的符号是否存在，命中率应为 100%。（来源：迭代 3 P0-1 ~ P0-4）
- **R12. 新类型与分派表必须同 commit 闭环**：引入新的白名单异常、新的 `sealed interface` 分支、新的 DTO 子类时，**同一 commit 内**必须同步更新所有分派表（`JGitExceptionSanitizer.classifyKind()` / UseCase 的 `when` / UI 的 `ErrorKind` 映射）；新增的 `class`、`Composable`、`Screen` 必须在同一 commit 内至少有一个生产端调用点（非 test/spec/doc）。评审机制：对新增符号做 `grep -r` 至少命中一处装配/调用；Kotlin 的 sealed interface `when` 一律用**表达式返回值**形式，让编译器强制穷尽。（来源：迭代 3 CR 问题 #6 / #8，对应反模式 P10 / P11）

---

## 归档规则

> 📦 已归档的规则不参与评审加载，但保留供查阅和检索。
> 归档原因通常为：连续 5 个迭代未被触发 / 相关模块已重构 / 被更精确的新规则替代。

（待积累）
