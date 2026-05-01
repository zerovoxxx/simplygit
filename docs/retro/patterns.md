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

（待积累）

## 流程类反模式

（待积累）
