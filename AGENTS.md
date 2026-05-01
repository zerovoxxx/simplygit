# SimplyGit

> 本文件是项目的导航地图，详细内容通过链接指向对应文档。

## 1. 项目定位

Android 原生 Git 后台同步工具，专注 Obsidian 笔记的**静默备份**与**轻量版本管理**。
不做全功能 Git IDE，只做"让用户忘记它存在"的后台版本控制引擎。

- **核心场景**：Obsidian Vault 的无感 Pull/Commit/Push + 冲突轻量介入
- **技术基调**：Kotlin + Jetpack Compose + MVVM + Hilt + WorkManager + JGit + SAF
- **设计总纲**：[`docs/Android 原生 Git 后台同步工具方案文档.md`](docs/Android%20原生%20Git%20后台同步工具方案文档.md)

## 2. 核心开发原则

1. **Spec 驱动**：所有功能变更必须先有 `docs/version/IterationN_*.md`，后编码
2. **文档即权威**：Spec 与本 AGENTS.md 是评审与 CR 的唯一权威依据
3. **最小改动**：只修改 Spec 涉及的模块，不顺手重构；UI/Domain/Data 三层严守边界
4. **现代 Android 合规优先**：
   - 文件访问**只走 SAF**（`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`），禁止硬编码 `java.io.File` 路径
   - 后台任务**只走 WorkManager**（`CoroutineWorker` + Constraints + 指数退避），禁止长驻 Service 或裸协程
   - 敏感凭证（PAT / SSH key）**只存 DataStore (encrypted)**，禁止落明文 SharedPreferences
5. **UI 与引擎解耦**：Compose 层只消费 `StateFlow`；JGit 调用只能出现在 Data 层，Diff 解析只能出现在 Domain 层
6. **机械校验优于人工约定**：能用 lint / detekt / CI 检查的规则不靠自觉遵守
7. **知识回流**：评审和 CR 中的发现自动沉淀到 `docs/retro/`

## 3. 权威文档

- `docs/Android 原生 Git 后台同步工具方案文档.md` — 产品与技术总方案（只读锚点，不随迭代改写）
- `docs/version/` — 版本设计文档（Spec，按迭代演进）
- `docs/retro/golden-rules.md` — 黄金法则（评审自动沉淀）
- `docs/retro/patterns.md` — 反模式库

## 4. 导航索引

| 文档 | 内容 | 维护方式 |
|------|------|---------|
| [`docs/version/INDEX.md`](docs/version/INDEX.md) | 迭代状态表 + 变更记录 | `/spec`、`/mr` 自动维护 |
| [`docs/version/BOUNDARIES.md`](docs/version/BOUNDARIES.md) | 迭代边界规则 | `/spec` 自动维护 |
| [`docs/retro/golden-rules.md`](docs/retro/golden-rules.md) | 黄金法则（活跃规则） | `/spec_review`、`/code_review` 自动沉淀 |
| [`docs/retro/patterns.md`](docs/retro/patterns.md) | 反模式库 | 同上 |

## 5. 当前活跃迭代

（尚未创建迭代，运行 `/spec` 启动 Phase 1 "MVP 核心链路"）

**演进路线（来自总方案，供 `/spec` 参考）**：

| Phase | 目标 | 关键交付 |
|-------|------|---------|
| Phase 1 — MVP 核心链路 | 验证 SAF + JGit + GitHub PAT 连通性 | 手动 Clone / Commit / Push |
| Phase 2 — 自动化静默同步 | 实现"让用户忘记 APP"的后台同步 | WorkManager + 策略配置 + 冲突通知 |
| Phase 3 — 冲突可视化 | 闭环版本管理体验 | 目录树 + 单栏 Diff + 基础冲突解决 |

## 6. 命令体系

| 命令 | 作用 |
|------|------|
| `/spec` | 创建新迭代的设计文档 |
| `/spec_review` | 评审 Spec，沉淀规则到 `docs/retro/` |
| `/dev` | 按 Spec 开发 |
| `/code_review` | 代码评审，加载 `docs/retro/` 规则 |
| `/mr` | 提交 MR 并流转 INDEX.md 状态 |
| `/retro` | 迭代回顾与知识沉淀 |
