# 反模式库

> 从历次评审和开发中识别的反模式，每条关联对应的黄金法则。
> 编号全局唯一递增（P1, P2, ...）。

## 设计类反模式

- **P1. Credential 用 data class 持明文**：将 `Credential` 定义为 Kotlin `data class` 并用 `String` 字段承载 PAT。默认 `toString()` 会在日志/异常/调试输出中泄漏明文；`String` 由 JVM 托管且不可变，无法主动清零。（来源：迭代 1，对应总方案 §5.1）。✅ 应改为：①改为普通 `class` 并手写 `toString()` 返回 redacted；或用 `@JvmInline value class` 封装 PAT 并覆盖 `toString`；②PAT 字段类型用 `CharArray`，加密入库后立即 `Arrays.fill(pat, '\u0000')`，UseCase 层不缓存。
- **P2. Spec 声明非目标但未标注"总方案变更"**：Spec 在"非目标"排除了总方案明确要求的 Phase 能力（如 Phase 1 的"手动 Pull + Auto-merge"），但未按 BOUNDARIES "任何 Spec 若突破总方案约束必须显式标注『总方案变更』并同步更新总方案"的规则处理。（来源：迭代 1）。✅ 应改为：①把该能力纳入目标；或②在 Spec 开篇显式加"总方案变更：本迭代将 X 推迟到 Phase N"并同步修改总方案文档的对应章节。
- **P3. 方案对比表选型对象与落地实现错位**：§3.1.x 对比表讨论方案 A（如 "EncryptedDataStore"），§4.x 详细设计却改为另一实现（如 "EncryptedSharedPreferences"），读者无法判断最终选型与对比表的对应关系。（来源：迭代 1）。✅ 应改为：①对比表讨论对象与最终落地实现保持名称一致；②若对比过程中发现"方案 A 不可行，降级为 A'"，在对比表下方显式说明"最终采用 A' 的原因"。

## 技术负债登记

- **D1. JGit FS 适配延后（迭代 1）**：Phase 1 采用 SAF → 绝对路径 → `java.io.File` 直连 JGit 的最简桥接，未实现自定义 `org.eclipse.jgit.util.FS` 子类。若用户 Vault 位于非 `primary:` 路径（SD 卡、云端 provider、`Android/data/*`）会直接失败。触发升级的信号：①Android 15+ 进一步收紧公共目录直接 IO；②线上用户反馈 Vault 放在非典型位置。应在 Phase 2 前评估是否补做 FS 适配迭代（参考总方案 §4.1 "Phase 3+ 备选"）。

## 实现类反模式

- **P4. 时间敏感的安全清理操作绑定到 UI Scope**：将"N 秒后清空剪贴板/缓冲区"这类安全收尾动作放到 `rememberCoroutineScope()`（Composable scope）或 `LaunchedEffect` 中，Composable 离开 composition / Activity 被回收 / 屏幕旋转都会直接取消协程，清理不会发生，留下敏感信息残留。（来源：迭代 1 CR M-2）。✅ 应改为：①放到 `viewModelScope` 或 Application-scoped singleton 的协程中；②或直接走 `WorkManager` one-shot task（适合跨进程场景）。判别标准——若动作的语义是"用户态的定时义务"而非"UI 展示的附属效果"，就不该绑定 UI 生命周期。
- **P5. 异常进入 UI/日志前缺乏统一脱敏/分派兜底**：UseCase/Repository 层定义了专用脱敏器（如 `JGitExceptionSanitizer`），但在 UI/ViewModel 的错误分支里采用 `(cause as? Sanitized)?.message ?: cause.message` 的 Elvis 兜底。新增类型异常（或域层异常如 `MissingXxxException`）会直接把原始 `message` 透传到 UI/日志，绕过脱敏；且无法做本地化分派。（来源：迭代 1 CR L-2）。✅ 应改为：①引入 `sealed interface ErrorKind` 做显式分派（未知 throwable 一律走 `Sanitized` 分支，强制经过 sanitizer）；②`GitOpResult.Failure.cause` 契约上只允许 `SanitizedGitException` 或白名单域异常；③UI 层所有错误文案通过 `strings.xml` 渲染，不直接使用 `Throwable.message`。
- **P6. JGit / 其他 Data 层原生类型透出到 Domain 接口**：Domain 层 interface 的返回值 / 数据类中包含 `org.eclipse.jgit.lib.Repository`、`org.eclipse.jgit.api.PullResult` 等需要显式 `close()` / `use{}` 的原生资源引用。即便 Spec 用约定（"仅限某某作用域内使用"）规避，类型系统层面已经破防——研发在 UseCase 里意外保留引用即泄漏 pack mmap / 文件句柄。（来源：迭代 2 I-3）。✅ 应改为：①把依赖原生类型的加工逻辑（如 `ConflictClassifier.classify(PullResult, Repository)`）**下沉到 Data 层**，Data 层持有 `Git.open(dir).use{}` 作用域并在同作用域完成分类；②Domain 接口仅返回**纯数据 DTO**（如 `PullOutcomeClassified(classification, commitsPulled, conflictPaths)`），不暴露原生引用；③若确需跨层传 JGit 对象，用 `@JvmInline value class` + `internal` 包装并在 Data 模块边界处解包。
- **P7. Spec 声明的异步挂载点与代码实际挂载点飘移**：Spec §"启动流程"声明某个冷启动初始化动作（如 DataStore → Room 迁移）"由 `Application.onCreate` 协程异步挂接"，但实现把它埋到某个读接口（如 `currentOrNull()`）的第一行——只有"恰好调用了该读接口"的路径才触发。`observe()` / UI 首帧 Flow 订阅不触发；依赖间接入口（如 `CatchUpTrigger`）的启动链不稳定。（来源：迭代 2 CR P2-01）。✅ 应改为：①Spec 声明的挂载点必须有显式代码调用（`Application.onCreate { appScope.launch { repo.migrate() } }`），不靠"读接口顺带触发"；②该动作设计成**幂等**，允许多个入口各自调用而不重复；③失败路径必须有可观测状态（如计数器 + UI 降级横幅），不能只打日志。

## 流程类反模式

（待积累）
