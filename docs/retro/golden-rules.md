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

---

## 归档规则

> 📦 已归档的规则不参与评审加载，但保留供查阅和检索。
> 归档原因通常为：连续 5 个迭代未被触发 / 相关模块已重构 / 被更精确的新规则替代。

（待积累）
