# SimplyGit

> Android 原生 Git 后台同步工具，专注 Obsidian 笔记的**静默备份**与**轻量版本管理**。

[![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)](https://developer.android.com/)
[![minSdk](https://img.shields.io/badge/minSdk-26-blue.svg)](https://developer.android.com/tools/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09-4285F4.svg)](https://developer.android.com/jetpack/compose)

SimplyGit 不做全功能 Git IDE，只做"让用户忘记它存在"的后台版本控制引擎 —— 在 Android 上为 Obsidian Vault 提供无感 Pull / Commit / Push，以及轻量的冲突介入。

---

## ✨ 核心特性

- **静默后台同步**：后台自动拉取远端更新、提交本地变更并推送（Phase 2 交付，基于 WorkManager）
- **SAF 原生兼容**：通过 `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` 合规访问用户 Vault 目录，适配 Android 11+ 分区存储
- **凭证安全建模**：GitHub PAT 经 `EncryptedSharedPreferences` (AES-256-GCM) 加密落盘；内存中以 `CharArray` 承载，用毕即 `Arrays.fill('\u0000')` 清零；含凭证输入的界面启用 `FLAG_SECURE`
- **异常脱敏**：所有 JGit 错误信息经 `JGitExceptionSanitizer` 剥离 `Authorization` 头 / PAT / URL Basic Auth 后再进入 UI 与日志
- **剪贴板保护**：用户粘贴 PAT 60s 后自动清空（仅清理本应用写入的内容）
- **MVVM 四层架构**：UI / Presentation / Domain / Data 严守边界，JGit 调用封装在 Data 层

---

## 🎯 项目定位

| 维度 | SimplyGit | 既有方案（MGit / Obsidian Git Mobile 等） |
|------|-----------|------------------------------------------|
| 定位 | **静默后台同步引擎**（Obsidian 专用） | 通用 Git IDE / 手动操作 |
| 存储合规 | 原生 SAF + 持久化权限 | 部分仍依赖旧版存储权限 |
| 冲突处理 | 轻量介入（单栏 Diff + 分级策略，Phase 3） | 体验差或不支持 |
| 引擎 | JGit（Eclipse 官方维护，供应链可信） | 多为 isomorphic-git / libgit2-JNI |

详见：[`docs/Android 原生 Git 后台同步工具方案文档.md`](docs/Android%20原生%20Git%20后台同步工具方案文档.md)

---

## 🧱 技术栈

| 领域 | 选型 |
|------|------|
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose (BOM 2024.09) |
| 架构 | MVVM · Hilt 2.51 (DI) · Coroutines 1.8 · StateFlow |
| Git 引擎 | **JGit 6.10** (HTTPS + PAT；`bcprov` / `jsch` 已 exclude) |
| 文件访问 | **SAF** (`ACTION_OPEN_DOCUMENT_TREE`) + `DocumentsContract` 解析至绝对路径 |
| 后台调度 | **WorkManager**（Phase 2；Phase 1 为手动触发） |
| 存储 | `EncryptedSharedPreferences` (凭证) + `DataStore Preferences` (仓库绑定) |
| 构建 | AGP 8.11 · Gradle KTS · Core Library Desugaring · detekt 1.23 |

---

## 📐 架构总览

```
app/src/main/java/com/example/simplygit/
├── SimplyGitApp.kt                  # @HiltAndroidApp
├── di/                              # Hilt 模块（DataStore / JGit / EncryptedPrefs）
├── data/
│   ├── credential/                  # CredentialDataSource (ESP) + Repository
│   ├── saf/                         # SafUriStore + SafPathResolver (Uri → 绝对路径)
│   ├── git/                         # JGitDataSource + JGitExceptionSanitizer
│   └── binding/                     # RepoBindingRepository
├── domain/
│   ├── model/                       # Credential / RepoBinding / GitOpResult
│   └── usecase/                     # Bind / Clone / Pull / Commit / Push
└── ui/
    ├── MainActivity.kt              # 单 Activity + FLAG_SECURE
    └── home/                        # HomeScreen + HomeViewModel + HomeUiState
```

**层间约束**：

- UI 层只消费 `StateFlow`；**禁止**直接调用 JGit
- JGit 调用**只出现在**`data/git/`；**必须**包裹 `withContext(ioDispatcher)`
- PAT 以 `CharArray` 参数形式跨层透传，**禁止**进入 `HomeUiState` / 日志 / `toString()`
- 所有对外异常**必须**经 `JGitExceptionSanitizer` 脱敏

---

## 🗺️ 演进路线

| Phase | 目标 | 关键交付 | 状态 |
|-------|------|---------|------|
| **Phase 1 — MVP 核心链路** | 验证 SAF + JGit + GitHub PAT 连通性 | 手动 Clone / Pull / Commit / Push | 🏗️ 进行中（见 [Iteration1_SPEC](docs/version/Iteration1_MVP_Core_Link_SPEC.md)） |
| Phase 2 — 自动化静默同步 | 让用户忘记 APP | WorkManager + 同步策略 + 冲突通知 | ⏳ 规划中 |
| Phase 3 — 冲突可视化 | 闭环版本管理体验 | 目录树 + 单栏 Diff + 基础冲突解决 | ⏳ 规划中 |

迭代状态总表：[`docs/version/INDEX.md`](docs/version/INDEX.md)

---

## 🚀 快速开始

### 构建要求

- JDK 17+
- Android Studio Ladybug 或更高版本（AGP 8.11）
- Android SDK：`compileSdk = 36`、`minSdk = 26`、`targetSdk = 36`

### 本地构建

```bash
# Clone 仓库
git clone https://github.com/<your-org>/simplygit.git
cd simplygit

# 编译 Debug APK
./gradlew :app:assembleDebug

# 安装到已连接设备
./gradlew :app:installDebug

# 静态检查
./gradlew :app:lintDebug
./gradlew :app:detekt
```

### 使用流程（Phase 1 手动链路）

1. 启动 APP，在凭证区输入 **GitHub username / email / PAT**（需赋予 `repo` 权限）并保存
2. 点击 **"选择 Vault 目录"**，通过系统文件选择器选取 `/sdcard/Documents/` 下的目录
   - ⚠️ 当前仅支持 `primary:` 存储分区（内置存储）；SD 卡 / `Android/data/` 受限目录会被明确拒绝
3. 输入 GitHub 远程仓库 URL（HTTPS），保存
4. 依次点击 **Clone → Pull → 编辑笔记 → Commit → Push**

---

## 🔐 安全承诺

| 风险 | 缓解 |
|------|------|
| PAT 被快照（Compose savedState / 最近任务截图） | `FLAG_SECURE` + `CharArray` + 不用 `rememberSaveable` |
| PAT 进入日志 / 异常 | `JGitExceptionSanitizer` 递归脱敏 `cause` 链，匹配 `ghp_*` / `github_pat_*` / `Authorization:` 等模式 |
| PAT 落盘 | `EncryptedSharedPreferences` + `MasterKey(AES256_GCM)`；`android:allowBackup="false"` 阻止 `adb backup` 拷贝 |
| 剪贴板残留 | 本应用写入 PAT 时带 `simplygit-pat` 标签，60s 后自动清空 |
| 权限被回收 | 每次操作前 `GitOpPreflight` 检查 `contentResolver.persistedUriPermissions` |

详细建模见 Spec §4.2 / §4.4 / §4.6 / §6.1。

---

## 📚 文档导航

| 文档 | 说明 |
|------|------|
| [`AGENTS.md`](AGENTS.md) | 项目治理导航（命令体系 / 核心开发原则） |
| [`docs/Android 原生 Git 后台同步工具方案文档.md`](docs/Android%20原生%20Git%20后台同步工具方案文档.md) | 产品与技术总方案（只读锚点） |
| [`docs/version/INDEX.md`](docs/version/INDEX.md) | 迭代状态总表 |
| [`docs/version/BOUNDARIES.md`](docs/version/BOUNDARIES.md) | 迭代边界规则 |
| [`docs/retro/golden-rules.md`](docs/retro/golden-rules.md) | 黄金法则（评审自动沉淀） |
| [`docs/retro/patterns.md`](docs/retro/patterns.md) | 反模式库 |

---

## 🤝 开发原则

1. **Spec 驱动**：功能变更先写 `docs/version/IterationN_*.md` 再编码
2. **文档即权威**：Spec 与 `AGENTS.md` 是 CR 的唯一权威依据
3. **最小改动**：只动 Spec 涉及的模块；UI / Domain / Data 三层严守边界
4. **现代 Android 合规优先**：SAF / WorkManager / 加密 DataStore 是硬性要求
5. **机械校验优于人工约定**：能用 lint / detekt / CI 检查的规则不靠自觉

### 命令体系（Harness）

| 命令 | 作用 |
|------|------|
| `/spec` | 创建新迭代的设计文档 |
| `/spec_review` | 评审 Spec，沉淀规则到 `docs/retro/` |
| `/dev` | 按 Spec 开发 |
| `/code_review` | 代码评审，加载 `docs/retro/` 规则 |
| `/mr` | 提交 MR 并流转 `INDEX.md` 状态 |
| `/retro` | 迭代回顾与知识沉淀 |

---

## 📄 License

TBD（待定；当前仓库尚未声明 License，提交代码前请联系维护者）
