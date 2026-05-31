# 迭代 1 Spec 评审报告：MVP 核心链路

---

## 📋 修订后复审结论（Spec v1.1）

**复审日期**：2026-05-01
**复审对象**：Spec v1.1（作者已根据 v1.0 评审报告逐项修订）

### 修订项核对

| 原 P0/P1/P2 编号 | 问题摘要 | 修订位置 | 状态 |
|---|---|---|---|
| **P0 Q1** | Credential 默认 data class → 明文泄漏 | §6.1（非 data class + `CharArray` + 手写 `toString=***` + equals/hashCode） | ✅ 已修复 |
| **P0 Q2** | AndroidManifest 缺 `INTERNET` 权限 | §4.1.2 独立 Manifest 清单，新增 `INTERNET` + `ACCESS_NETWORK_STATE` + `allowBackup="false"` + `SimplyGitApp` 入口 | ✅ 已修复 |
| **P0 Q3** | `libs.versions.toml` 未登记依赖 | §4.1.1 完整 TOML + build.gradle 增量（Hilt/KSP/DataStore/security-crypto/JGit/desugar/coroutines） | ✅ 已修复 |
| **P0 Q4** | 包名不一致 | §4.1 声明沿用 `com.example.simplygit`，不改包 | ✅ 已修复 |
| **P0 Q5** | PAT 方案命名漂移（EDS vs ESP） | §3.1.2 表头统一为 ESP + 段首术语说明；§3.2 / §4.7 / §6.1 全部一致 | ✅ 已修复 |
| **P1 Q6** | `PersonIdent.email` 来源未定义 | §4.2 新增 `github_email` 持久化字段 + 默认值规则；§4.4 `commitAll` 签名显式接收 `authorName/authorEmail`；§6.3 矩阵补字段行 | ✅ 已修复 |
| **P1 Q7** | 与总方案 §9 P1.5 不一致 | §1 新增"对齐声明"；G7 纳入目标；§5.2/§7.1 A8 新增 Pull 流程与验收 | ✅ 已修复（选择"纳入 Pull"路径） |
| **P1 Q8** | PAT 用 String 无法清零 | §6.1 全链路 `CharArray`；§4.5 UseCase 模板 `try/finally + Arrays.fill`；R-4 三层防护 | ✅ 已修复 |
| **P1 Q9** | SAF 未做 canRead/canWrite 探测 | §4.3 `ResolveResult` 三态 + `NotReadable` 分支；权限失效检测 | ✅ 已修复 |
| **P1 Q10** | JGit 异常 message 可能泄漏敏感信息 | §4.4 新增 `JGitExceptionSanitizer`；R-6 说明；A10 验收；NF6 静态检查 | ✅ 已修复 |
| **P1 Q11** | `coreLibraryDesugaring` 只提一半配置 | §4.1.1 完整双配置；R-3 明确"任一缺失即运行时报错" | ✅ 已修复 |
| **P1 Q12** | `FLAG_SECURE` / 剪贴板 60s 清空未落地 | §4.6 `MainActivity.onCreate` 示例 + 剪贴板调度代码；A9 验收 | ✅ 已修复 |
| **P2 Q13** | `targetSdk=34` 与现状不一致 | NF4 修正为 `targetSdk=36`（沿用现状） | ✅ 已修复 |
| **P2 Q14** | 验收步骤不可操作 | A2 改为 `run-as` 方案 + 检查 Base64 密文；A4 量化为"主线程无 >500ms 阻塞" | ✅ 已修复 |
| **P2 Q15** | 技术负债未登记 | `docs/retro/patterns.md` D1 已登记（v1.0 评审时同步完成） | ✅ 已修复 |

### 复审总评

**总体评分**：🟢 优

全部 P0/P1/P2 问题已修订，Spec 已具备拆任务条件。v1.1 相对 v1.0 的关键提升：
1. **安全基线达标**：PAT 全链路 `CharArray` + `FLAG_SECURE` + 异常脱敏 + 禁备份，满足总方案 §5.1/§5.3/§5.4 合规红线
2. **工程基线完整**：依赖清单 + Manifest 清单 + desugar 双配置 + bcprov 排除 + 包名锁定，研发可零歧义开工
3. **目标对齐总方案**：G7 Pull 纳入，与总方案 §9 P1.5 完全对齐，无偏离
4. **可验收性提升**：A1-A10 + NF1-NF6 含具体命令、正则、阈值，可直接转测试用例

**文档状态**：已由"评审中"流转为"评审完成"。

### 建议的下一步

1. 运行 `/dev` 命令开始按 Spec v1.1 拆分开发任务（建议按"Gradle/DI 骨架 → Credential 模块 → SAF 模块 → JGit 模块 → UI 模块"顺序，每段独立 PR）。
2. `/dev` 启动前先确认现有 `app/build.gradle.kts` 的 `namespace = "com.example.simplygit"` 与 Spec v1.1 一致（已一致，无需改动）。
3. 开发期间若发现新问题，走 `/code_review` 沉淀到 `docs/retro/`。

---

## 📋 原始评审报告（v1.0）



**文档**：`Iteration1_MVP_Core_Link_SPEC.md`
**版本**：v1.0
**评审日期**：2026-05-01
**评审依据**：Spec + 项目现有代码 + `AGENTS.md` + `docs/Android 原生 Git 后台同步工具方案文档.md` + `docs/version/BOUNDARIES.md` + `docs/retro/`（活跃规则/反模式当前均为空）

---

### 一、结构完整性

| 章节 | 状态 | 备注 |
|------|------|------|
| 文档信息（版本/日期/前置依赖） | ✅ 完整 | v1.0 / 2026-05-01 / 前置依赖声明为"无（首个迭代）"，合理 |
| 背景与目标 | ✅ 完整 | §2.1 当前状态 + §2.2 调研 + §2.3 G1-G6 + §2.4 N1-N8 完备 |
| 当前状态 / 问题分析 | ✅ 完整 | §2.1 三大技术风险点列举清晰 |
| 非目标 | ✅ 完整 | N1-N8 边界清晰，但 N8 与总方案 §9 P1.5 冲突（见 IC4/A3） |
| 方案决策（对比 + 选型） | ✅ 完整 | 三张对比表含"数据权威性"与"供应链安全"列（满足 S1） |
| 详细设计 | ⚠️ 不足 | §4.4 `commitAll` 的 `PersonIdent.email` 来源未说明；§4.3 未含 `File.canRead/canWrite` 可用性探测（总方案 §4.1 明确要求） |
| 数据模型 / 接口定义 | ⚠️ 不足 | §6.1 `data class Credential` 默认 `toString()` 会泄漏 PAT，违反总方案 §5.1 强制要求；PAT 字段类型为 `String`，无法主动清零，违反总方案 §5.1 "`CharArray` + `Arrays.fill('\u0000')`" 规则 |
| 交互流程 / 用户流程 | ✅ 完整 | §5.2 流程图覆盖成功/失败分支 |
| 跨层字段传递 | ⚠️ 不足 | §6.3 矩阵清晰，但缺少 `author_email` / `commit_author_ident` 字段的来源与传递路径 |
| 验收标准 | ⚠️ 不足 | A2 "adb shell 查 encrypted_prefs.xml" 在非 debuggable 包下无法直接执行；A4 "主线程无 ANR" 指标未量化 |
| 风险与缓解 | ✅ 完整 | R-1 ~ R-5 覆盖主要技术风险 |
| 变更记录 | ✅ 完整 | §10 初版记录 |

---

### 二、方案内部一致性

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| IC1 | 目标→解法覆盖 | ✅ | G1→§4.2、G2→§4.3、G3/G4/G5→§4.4、G6→§4.1/§4.5/§4.7，全覆盖 |
| IC2 | 解法→目标溯源 | ✅ | 四个 UseCase / Data 模块均能回溯到 G1-G6，无冗余 |
| IC3 | 非目标边界一致 | ✅ | 详细设计未涉及 WorkManager/Diff/冲突解决/SSH/多仓/Room/FS 适配 |
| IC4 | 迭代边界一致 | ⚠️ | **与总方案 §9 Phase 1 的 P1.5 "手动 Pull + Auto-merge" 不一致**：Spec N8 明确"不做增量 Pull；Clone 成功即视为本迭代链路通过"，但总方案 Phase 1 把 Pull + Auto-merge 列为验收项。总方案 BOUNDARIES "任何迭代 Spec 若突破总方案约束，必须显式标注『总方案变更』并同步更新总方案"——Spec 未标注 |
| IC5 | 冗余内容检测 | ✅ | 无与 G1-G6 无关的设计 |
| IC6 | 前后表述一致 | ⚠️ | **PAT 存储方案的命名前后漂移**：§3.1.2 对比表方案 A 标题为"EncryptedDataStore（Preferences DataStore + Tink）"，§4.2 依赖写 `androidx.security:security-crypto` + DataStore，§4.7 落地改为 `EncryptedSharedPreferences`，§6.1 持久化表确认为 ESP。方案对比表讨论的对象与最终落地对象名称和实现路径**不是同一个**，读者无法判断最终选型是 EDS 还是 ESP |

---

### 三、合理性评审

**总评**：🟡 良

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| A1 | 问题-方案匹配 | ✅ | 三大风险点（SAF×JGit / PAT 存取 / 协程化）均有对应解法 |
| A2 | 方案对比充分性 | ✅ | Git 引擎 / PAT 存储 / SAF 桥接 三处对比，含量化数字 |
| A3 | 迭代边界遵守 | ⚠️ | 削减了总方案 §9 P1.5 Pull 能力；未按 BOUNDARIES "显式标注总方案变更"的要求处理 |
| A4 | 复用优先原则 | ✅ | JGit / Jetpack Security / DataStore 均选官方复用路径 |
| A5 | 非目标合理性 | ⚠️ | N8 "不做增量 Pull" 与总方案 P1.5 冲突；其余合理 |
| A6 | 前置依赖 | ✅ | 首个迭代，无前置依赖 |
| A7 | 目标-解法一致性 | ✅ | 本 Spec 子任务全部服务于 Phase 1 连通性验证目标 |
| A8 | 冗余度 | ✅ | 设计精简聚焦 |

---

### 四、清晰度评审

**总评**：🟡 良

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| B1 | 术语一致性 | ⚠️ | "EncryptedDataStore" vs "EncryptedSharedPreferences" 前后漂移（见 IC6）；"本地绝对路径" 在 §4.3 为 `localAbsPath`，§6.3 为 `local_abs_path`（大小写风格不一，但上下文可辨）|
| B2 | 接口定义精确性 | ⚠️ | `JGitDataSource.commitAll(..., author: PersonIdent)` 未说明 `PersonIdent.email` 来源：PAT 录入仅含 `username`，email 从哪来？Spec 未定义 |
| B3 | 流程可追踪性 | ✅ | §5.2 用户流程完整，含失败分支 |
| B4 | 代码示例可理解性 | ✅ | §4.4 伪代码简洁可读 |
| B5 | 验收标准可操作性 | ⚠️ | A2 "adb shell 查看 encrypted_prefs.xml"——生产构建的 APP 私有目录需 `run-as <package>`，且用户 APP 非 debuggable 时 `run-as` 不生效；建议改为"`run-as com.simplygit cat /data/data/.../shared_prefs/encrypted_prefs.xml` 输出为密文二进制，无明文 `ghp_` / `github_pat_` 前缀"。A4 "主线程无 ANR" 未量化；建议改为"Clone 期间 UI 可交互（滚动不卡顿），Android Studio Profiler 主线程无 >500ms 阻塞" |
| B6 | 任务可拆解性 | ✅ | §4.1 目录结构可拆成独立 PR：DI 骨架 / Credential 模块 / SAF 模块 / Git 模块 / UI 模块 |
| B7 | 任务粒度与依赖 | ⚠️ | 未明确 UseCase 的执行前置条件（如 Clone 必须在 RepoBinding 完整后才能触发，否则 UiState 转换规则不全） |

---

### 五、可行性评审（代码验证）

**总评**：🟠 中（多处与现有代码基线不一致，需修订才能直接拆任务）

| # | 检查项 | 评分 | 发现 | 代码依据 |
|---|--------|------|------|---------|
| C1 | 代码可达性 | ⚠️ | Spec §4.1 给出的包名 `com.simplygit.*`，但现有代码 namespace 为 `com.example.simplygit`，Spec 未声明是否需要改包名 | `app/build.gradle.kts:8`（`namespace = "com.example.simplygit"`）、`MainActivity.kt:1`（`package com.example.simplygit`）|
| C2 | 改动完备性 | ❌ | ①`AndroidManifest.xml` 缺 `<uses-permission android:name="android.permission.INTERNET"/>`，Spec 未声明需新增，直接写 Clone/Push 代码会在运行时失败；②`libs.versions.toml` 未登记 JGit / Hilt / DataStore / security-crypto / desugar_jdk_libs，Spec 未给 TOML 增量；③R-3 `coreLibraryDesugaring` 需 `build.gradle.kts` 的 `compileOptions.isCoreLibraryDesugaringEnabled = true` 和 `dependencies { coreLibraryDesugaring(...) }` 两处改动，Spec 只提了一半 | `app/src/main/AndroidManifest.xml:5-25`（无 `uses-permission`）、`gradle/libs.versions.toml`（无 JGit/Hilt 等）、`app/build.gradle.kts:30-36`（`compileOptions` 无 desugar）|
| C3 | 命名与路径一致性 | ❌ | ①包名不一致（见 C1）；②Spec NF4 写 `targetSdk=34`，现状 `compileSdk=36, targetSdk=36`，Spec 未说明是"降级到 34 还是继续 36"；③Spec §3.1.1 提到"需排除 `bcprov` 冲突"，但 Spec 未给出 `exclude group: "org.bouncycastle"` 等配置片段 | `app/build.gradle.kts:9,14`（sdk=36）|
| C4 | 向后兼容性 | ✅ | 首个迭代，无旧数据；DataStore / ESP 首次创建即为最新格式 |
| C5 | 异常与降级 | ⚠️ | §4.4 "异常统一包装为 `Result.failure`，UI 展示 `Throwable.message`"——**JGit 异常 message 可能含仓库 URL / refspec / 远程错误详情**，违反总方案 §5.1 "禁止把凭证写入 `Log.*` / 异常堆栈"（虽然 PAT 不在 message 里，但远程 URL 仍属敏感，且 JGit `TransportException` 的 cause 链可能含 HTTP 头）；需在 Spec 增加"message 过滤层：剥离 `Authorization` 头、token 参数" |
| C6 | 工程风险 | ⚠️ | ①JGit 6.x 的 `bcprov` 冲突未在 Spec 给出规避写法；②`security-crypto:1.1.0-alpha06` 的 `MasterKey` 在 Android 14+ 部分 ROM 有 "failed to decrypt" 已知问题，R-2 有兜底但未具体到 API 版本 |
| C7 | 安全与可测试性 | ❌ | ①`Credential` 定义为 `data class`，Kotlin 默认 `toString()` 会输出 `Credential(username=..., pat=...)`，**明文 PAT 会进入日志/Logcat/异常堆栈**，严重违反总方案 §5.1 "强制自定义 `toString` 返回 `Credential(redacted)`"；②PAT 字段类型 `String`（不可变且由 JVM 管理生命周期），无法主动清零，违反总方案 §5.1 "`CharArray` + `Arrays.fill('\u0000')`"；③总方案 §5.3 要求 PAT 输入界面 `FLAG_SECURE`，Spec §4.6 UI 未提；④总方案 §5.4 要求 PAT 粘贴框 60s 清空剪贴板，Spec 未提；⑤总方案 §4.1 要求"启动时用 `File.canRead() && File.canWrite()` 探测可用性"，Spec §4.3 `SafPathResolver` 只做路径解析 |

---

### 六、Harness 实践评审（LLM 交互设计）

**总评**：不适用（本迭代为纯工程改动：SAF/JGit/DataStore/Compose，未涉及 Prompt/Skill/Agent 行为设计）

| # | 检查项 | 评分 | 发现 |
|---|--------|------|------|
| D1-D7 | 全部 | N/A | 不适用 |

---

### 七、关键问题清单（按优先级排序）

| 优先级 | 编号 | 问题摘要 | 维度 | 影响 | 建议修改方向 |
|--------|------|---------|------|------|-------------|
| **P0** | Q1 | `Credential` 为默认 `data class`，默认 `toString()` 暴露明文 PAT | C7 / 安全 | 违反总方案 §5.1 合规红线；上线即漏洞 | 将 `data class Credential` 改为 `class Credential` 并手写 `equals/hashCode/toString`，`toString` 返回 `"Credential(username=$username, pat=***)"`；或用 `@JvmInline value class Pat(private val value: CharArray) { override fun toString() = "***" }` 封装 PAT |
| **P0** | Q2 | `AndroidManifest.xml` 缺 `INTERNET` 权限且 Spec 未声明新增 | C2 | Clone/Push 直接运行时 `UnknownHostException` | §4.1 或 §6.1 新增"AndroidManifest 改动清单"子节，明列 `<uses-permission android:name="android.permission.INTERNET"/>` |
| **P0** | Q3 | `libs.versions.toml` 未登记 JGit/Hilt/DataStore/security-crypto/desugar | C2 | 研发无法直接开始依赖引入，需反复回查 Spec | §4.2/§4.7 增加"依赖清单"子节，给出 `libs.versions.toml` 增量（含版本号）+ `app/build.gradle.kts` 的 `implementation` 行；JGit 须附 `exclude(group = "org.bouncycastle")` |
| **P0** | Q4 | 包名 `com.simplygit.*` 与现状 `com.example.simplygit` 不一致，Spec 未明确是否改包 | C1 / C3 | 研发拆任务时无法判断包名基线 | §4.1 开篇明确"本迭代将 namespace 从 `com.example.simplygit` 改名为 `com.simplygit`（含 AGP `namespace`、`applicationId`、目录移动）"，或反向把 Spec 中的包名统一回 `com.example.simplygit` |
| **P0** | Q5 | PAT 存储方案命名前后漂移（EDS vs ESP） | IC6 / B1 | 研发不清楚对比表选的到底是 ESP 还是 EDS | §3.1.2 对比表标题与 §4.2/§4.7/§6.1 统一为 `EncryptedSharedPreferences`；若保留 EDS 对比，需额外说明"最终因 DataStore 无加密 API，降级为 ESP" |
| **P1** | Q6 | `JGitDataSource.commitAll` 的 `PersonIdent.email` 字段来源未定义 | B2 / 跨层契约 | 研发无法实现 commitAll，容易硬编码 email 或留 `""` 导致 JGit 抛异常 | §4.2 凭证存储新增 `github_email` 字段（非敏感，明文），§6.3 矩阵补一行；或规定"email 默认为 `$username@users.noreply.github.com`" |
| **P1** | Q7 | 与总方案 §9 P1.5 "手动 Pull + Auto-merge" 不一致，N8 整体排除 Pull 但未按 BOUNDARIES 要求"显式标注总方案变更" | IC4 / A3 | 迭代产出对齐总方案口径时会被判偏差 | 二选一：①把 Pull + Auto-merge（无冲突场景）纳入 G7，将 N8 改为"不做 Pull 冲突检测/冲突 UI"；②保留 N8，在 §1/§2.3 显式加"总方案变更：本迭代将 Pull 推迟到 Phase 2"并同步更新总方案 §9 P1.5 |
| **P1** | Q8 | PAT 用 `String` 持有，违反总方案 §5.1 `CharArray + fill('\u0000')` 要求 | C7 / 安全 | 内存 dump / debug 场景可能泄漏 | §6.1 将 `Credential.pat: String` 改为 `pat: CharArray`；`CredentialRepository.save` 接收 CharArray 并在加密完成后立即 `Arrays.fill(pat, '\u0000')`；UseCase 不缓存 |
| **P1** | Q9 | SAF 授权后未执行 `File.canRead()/canWrite()` 可用性探测 | C7 / 可行性 | 违反总方案 §4.1 "启动时探测"要求；用户可能选了 `Android/data/*` 路径在后续 Clone 阶段才发现失败，体验劣化 | §4.3 `SafPathResolver.tryResolveAbsolutePath` 返回成功后，追加 `File(absPath).canRead() && canWrite()` 探测，失败同样返回 null 并提示 |
| **P1** | Q10 | JGit 异常 message 可能泄漏远程 URL / HTTP 头 | C5 / 安全 | 用户导出日志或 Logcat 可能包含敏感信息 | §4.4 增加"异常 sanitizer"：包装 JGit 异常时剥离 URL query 参数、Authorization 头、refspec 中的 credential 部分 |
| **P1** | Q11 | `coreLibraryDesugaring` 仅提依赖行，未提 `compileOptions.isCoreLibraryDesugaringEnabled = true` | C2 / R-3 | 仅加依赖不开关会编译通过但运行时 `NoClassDefFoundError` | §8 R-3 补充完整两行配置 |
| **P1** | Q12 | 总方案 §5.3 `FLAG_SECURE` 与 §5.4 剪贴板 60s 清空未在 Spec §4.6 落地 | C7 / 安全 | 本迭代即涉及 PAT 输入界面，是第一条必须落地的安全规则 | §4.6 凭证区增加"`LaunchedEffect(Unit) { window.addFlags(FLAG_SECURE) }`（在 DisposableEffect 中清除）"；剪贴板监听在凭证保存成功后 60s 清空自身写入 |
| **P2** | Q13 | Spec NF4 `targetSdk=34` 与现状 `targetSdk=36` 不一致 | C3 | 本迭代编译基线不清晰 | NF4 改为 "`targetSdk=36`（沿用现状）"，或在 Spec 声明"降级 targetSdk 到 34 以规避权限新增复杂度" |
| **P2** | Q14 | A2 / A4 验收步骤不可操作 | B5 | 验收时无法执行 | A2 改为 `run-as` 方案；A4 量化为"主线程无 >500ms 阻塞" |
| **P2** | Q15 | `docs/retro/patterns.md` 未登记"JGit FS 适配延后"技术负债 | 治理 | 总方案 §4.1 明确要求登记；当前文件为空 | 在 patterns.md 追加 P1 条目"FS 适配延后：Phase 1 采用绝对路径直连 JGit，若 SAF 直连失败需 Phase 2 前补做自定义 FS" |

---

### 八、综合评价

**总体评分**：🟠 中

Spec 骨架完整、对比扎实、边界清晰，作为首迭代"验证技术连通性"的目标定位准确；但在**安全红线（Credential 默认 `toString()`/PAT `String` 类型/`FLAG_SECURE`/剪贴板/异常脱敏）**和**工程基线（包名/Manifest/依赖 TOML/desugar 完整配置）**两条线上存在多处阻塞拆任务的问题。其中 Q1-Q5 为 P0，不修订则研发拆任务即落入"总方案红线"坑；建议优先解决 Q1（Credential 安全建模）与 Q3（依赖清单）两项，其他 P0 可在补丁版一并修订后再进入开发。

---

### 九、建议的下一步

1. **修订 Spec 到 v1.1**：解决 Q1-Q5 五个 P0 问题（重点：`Credential` 安全建模、Manifest 权限清单、依赖 TOML 增量、包名基线、PAT 方案名统一）。
2. **与总方案作者对齐 Q7**：选择"Phase 1 补 Pull"还是"总方案 §9 P1.5 下沉到 Phase 2"并同步修改总方案文档。
3. **patterns.md 预登记 Q15**：把 "JGit FS 适配延后" 登记为技术负债，避免 Spec N7 成为"无归档的豁免"。
4. **P1 问题（Q6-Q12）随 Spec v1.1 一并修订**，避免开发期间再次回滚 Spec。
5. Spec v1.1 修订完成后重新进入 `/spec_review`，确认无 P0 后状态置 `评审完成`，并触发 `/dev` 开始拆任务。
