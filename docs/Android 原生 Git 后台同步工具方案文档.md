# Android 原生 Git 后台同步工具方案文档

> 本方案是 SimplyGit 的产品与技术总纲，作为后续所有迭代 Spec 的上游锚点。
> 三条贯穿全文的设计主线：**性能（低内存/低延迟）**、**安全（数据主权/凭证保护）**、**耗电（Doze 友好/可预测预算）**。
> 任何与三条主线冲突的功能需求，默认否决或推迟。

---

## 1. 产品概述

### 1.1 核心定位

一款面向 **Obsidian 等本地文件驱动型应用** 的 Android 原生 Git 后台同步引擎。

不做全功能 Git IDE、不做在手机上编码的工具，只做一件事：**让用户的笔记在手机端的 Git 同步像 iCloud 一样隐形**——除了第一次授权，用户不应该再需要打开这个 APP。

### 1.2 价值主张

> **"零打扰同步 + 零数据泄漏 + 零可感耗电"**

这三个"零"是产品的否决权锚点：任何需要用户主动拉起 APP、任何会把笔记内容送出设备、任何会让手机温度可感上升的设计，一律被质疑其必要性。

### 1.3 用户画像

| 画像 | 典型特征 | 优先级 |
| :---- | :---- | :---- |
| **Obsidian 重度用户** | 多端编辑，已有 GitHub 私有仓库作为 Vault 后端 | P0（MVP 唯一目标） |
| **纯文本笔记党** | 追求数据主权，偏好 Markdown / 纯文本 | P1 |
| **dotfiles / 配置携带者** | 手机上偶发查看 & 同步脚本、配置 | P2 |

### 1.4 明确不做（非目标）

- 在手机上**写代码**的开发者 IDE 辅助场景
- **多仓库并行**（MVP 只支持一个仓库绑定一个 Vault）
- **GitLab / Gitea / 自建 Git** 支持（MVP 只支持 GitHub）
- **LFS / Submodule / Subtree** 等 JGit 支持度差的高级特性
- **服务器端中转 / 云侧合并**（数据不出端是红线）

### 1.5 核心场景

1. **无感备份**：用户在 Obsidian 中持续编辑，APP 在后台按策略聚合提交并推送，冲突自动拉取合并。
2. **轻量干预**：仅当出现**无法自动解决的冲突**时，通过通知把用户拉进 APP，用单栏 Diff 视图完成整文件二选一。
3. **故障可查**：后台默默失败是本类工具最大的坑，必须提供本地同步审计记录 + 一键导出日志。

### 1.6 成功指标（MVP 验收基线）

| 维度 | 指标 | 测量方式 |
| :---- | :---- | :---- |
| 首次配置耗时 | ≤ 3 分钟 | 用户测试录屏 |
| 日常 APP 打开频次 | ≤ 1 次 / 周 | 本地埋点（不上报） |
| 无冲突场景同步成功率 | ≥ 98% | 本地 `sync_log` 表统计 |
| 冲突通知触达率 | 100% | NotificationManager 回执 |
| 日均后台耗电 | ≤ 2%（典型使用） | Battery Historian 采样 |
| Crash-Free Session | ≥ 99.5% | 本地 Crash 捕获 |

---

## 2. 产品设计

### 2.1 核心功能模块

| 模块 | 功能描述 | 关键交互 |
| :---- | :---- | :---- |
| **仓库管理** | 绑定本地目录 + 配置远程 | SAF 授权 Vault 目录 → 配置 GitHub PAT / SSH → 首次 `clone` 或 `init` |
| **自动同步引擎** | 按策略在后台 Pull / Commit / Push | 用户可配置间隔（5/15/30/60 min）、网络条件（仅 Wi-Fi）、电池条件（非低电） |
| **冲突通知与解决** | 冲突分级 + 通知 + 轻量解决 | 行内可合并自动处理；不可合并暂停同步并通知；点击进入整文件二选一 |
| **目录与 Diff 视图**（Phase 3） | 浏览仓库 + 查看变更 | 懒加载目录树 + LazyColumn 单栏 Diff |
| **同步审计** | 本地记录所有同步行为 | 历史列表 + 详情 + 授权后导出日志 |

### 2.2 核心用户链路

**初始化（≤ 3 min）**
用户登录 GitHub → 粘贴 PAT（Keystore 加密存储）→ SAF 选择 Vault 目录 → 选择远端分支 → 首次 `clone` 或绑定既有 `.git`。

**日常运转（零打扰）**
用户离开 APP → `WorkManager` 周期任务按约束条件执行 → 变更聚合、空 commit 抑制、指数退避重试 → 成功即沉默，失败才通知。

**冲突介入（仅必要时）**
远程与本地同区域冲突 → 暂停同步 → 通知 → 用户点击进 APP → Diff 视图 → 整文件二选一 → 恢复同步。

### 2.3 交互原则

- **静默优先**：成功不通知，仅同步历史可追溯。
- **失败显性**：凡暂停同步的失败（冲突 / 401 / 权限回收 / 磁盘不足）必须通知，且文案给出**一步可执行动作**。
- **轻量优先**：所有列表走 LazyColumn 惰性渲染；任何一次性全量加载超过 1000 项的 UI 视为性能缺陷。

---

## 3. 技术架构

### 3.1 总体分层（MVVM + 单向数据流）

```
┌─────────────────────────────────────────────────────────┐
│ UI Layer (Compose)                                      │
│   仓库列表 / 配置页 / 同步历史 / 冲突 & Diff 视图         │
│   只消费 StateFlow，不直接访问 JGit / SAF                │
└─────────────────────────────────────────────────────────┘
                     ▲ StateFlow
┌─────────────────────────────────────────────────────────┐
│ Presentation Layer (ViewModel)                          │
│   意图 → UseCase；持有 UiState；不含业务规则             │
└─────────────────────────────────────────────────────────┘
                     ▲ suspend UseCase
┌─────────────────────────────────────────────────────────┐
│ Domain Layer                                            │
│   SyncStrategy / ConflictClassifier / DiffParser        │
│   纯 Kotlin，无 Android / JGit 依赖，可单测              │
└─────────────────────────────────────────────────────────┘
                     ▲ Repository 接口
┌─────────────────────────────────────────────────────────┐
│ Data Layer                                              │
│  ├─ GitRepository    封装 JGit，所有 Git I/O 在 IO Dispatcher
│  ├─ FileRepository   SAF / DocumentFile 访问
│  ├─ CredentialStore  Keystore + EncryptedFile 凭证库
│  ├─ SyncLogDao       Room 审计表
│  └─ PreferencesStore DataStore 配置
└─────────────────────────────────────────────────────────┘
                     ▲ WorkManager 调度
┌─────────────────────────────────────────────────────────┐
│ Background Runtime                                      │
│   GitSyncWorker (CoroutineWorker) + Constraints         │
│   ChangeObserver（可选的 FileObserver/ContentObserver） │
└─────────────────────────────────────────────────────────┘
```

**强边界（写入 `docs/retro/golden-rules.md`）**：
- JGit 调用**只能出现在 Data 层**；Domain 与 UI 对 JGit 零感知。
- SAF 访问**只能通过 FileRepository**；禁止裸 `DocumentFile` / `java.io.File` 散落各处。
- 凭证读取**只能通过 CredentialStore**；禁止任何层直接读 DataStore 凭证键。

### 3.2 技术栈

| 领域 | 选型 | 理由 |
| :---- | :---- | :---- |
| 语言 | Kotlin | Android 官方现代栈 |
| UI | Jetpack Compose | 声明式、LazyColumn 天然适合大 Diff/TreeView |
| DI | Hilt | 控制 Worker、Repository 单例生命周期 |
| 并发 | Coroutines + Flow | 结构化并发，便于取消后台任务 |
| 后台任务 | WorkManager | Doze/App Standby 友好，唯一合规选择 |
| 本地存储 | DataStore（配置/凭证引用） + Room（审计 & 目录缓存） | 配置用 Preferences DataStore；结构化数据用 Room |
| 凭证安全 | Android Keystore + Tink AEAD / EncryptedFile | Hardware-backed 密钥 |
| Git 引擎 | JGit（Eclipse） | 纯 Java/Kotlin 可用，Android 原生编译最顺手 |
| 日志 | Timber + 文件 Appender（滚动 7 天） | 本地可查、可导出 |

**显式拒绝**：Foreground Service（违背"零打扰"且耗电）、`SharedPreferences` 存凭证（不加密）、`AlarmManager` 精确闹钟（Android 14+ 权限收紧且耗电）。

### 3.3 线程与调度模型

- **主线程**：只做 Compose 渲染与 ViewModel 轻逻辑。
- **Dispatchers.IO**：所有 JGit、SAF、Room、DataStore 读写；`withContext(IO)` 必须覆盖到 JGit 调用。
- **Dispatchers.Default**：Diff 解析、目录 diff 比对等 CPU 密集计算。
- **WorkManager Executor**：Worker 默认线程池；Worker 内部再按需切换到 IO/Default。
- **取消传播**：所有 UseCase 必须响应 `ensureActive()`，Worker 被 WorkManager 终止时能快速释放 JGit 资源（`Repository.close()`）。

---

## 4. 关键技术方案

### 4.1 文件访问：SAF 与 JGit 的桥接

Android 11+ 分区存储是本项目的**最高技术风险点**。原则是"**MVP 只走一条路，复杂方案延后**"。

**MVP 决策：绝对路径优先 + 严格降级提示**

- SAF 授权 Vault 目录后，通过 `DocumentsContract.getTreeDocumentId()` 反解出绝对路径（典型路径 `/sdcard/Documents/<Vault>`）。
- 启动时用 `File.canRead() && File.canWrite()` 探测可用性；不可用则明确提示"当前目录不受支持，请将 Vault 移至 `Documents/` 下"——不静默降级。
- JGit 直接基于 `java.io.File` 操作，无桥接开销。

**Phase 3+ 备选：自定义 JGit `FS` 实现**

- 完整实现 `org.eclipse.jgit.util.FS`，通过 `ContentResolver.openFileDescriptor()` 桥接读写。
- 成本高（需处理 `exec` / `symlink` / `hook` 多个抽象），MVP 不做，登记为**技术负债**追踪到 `docs/retro/patterns.md`。
- 触发升级的信号：Android 15+ 收紧公共目录访问、或用户反馈 Vault 放在非典型位置。

**权限失效的运行时检测**

每次 Worker 启动前执行 `contentResolver.persistedUriPermissions` 校验；权限已被系统回收则**立刻暂停同步并通知**，绝不尝试偷偷继续。

### 4.2 后台同步：WorkManager + 防抖 + 幂等

**Worker 结构**

```
GitSyncWorker : CoroutineWorker
  约束：
    - NetworkType.CONNECTED（用户可升级为 UNMETERED，仅 Wi-Fi）
    - requiresBatteryNotLow = true
    - requiresStorageNotLow = true
  周期：PeriodicWorkRequest，默认 15 min（最小 15 min 是 WorkManager 硬性限制）
  重试：BackoffPolicy.EXPONENTIAL，初始 30s，上限 5h
```

**变更聚合与防抖（避免每次保存都 commit）**

- **静默期**：若最后一次文件修改距今 < 2 min，**跳过本次同步**，留到下一周期——Obsidian 用户正在编辑时不打断。
- **空 commit 抑制**：`git status` 为空时跳过 commit，只做 pull/push。
- **合并提交**：窗口内所有变更合并为一次 commit，默认 message 模板 `chore(sync): auto-commit at <ISO8601>`，用户可自定义。
- **幂等 commit**：重试不产生新 commit——已 commit 未 push 的场景下，下次 Worker 只重试 push。

**事件触发同步（可选，Phase 2 评估）**

`FileObserver` / `ContentObserver` 监听 Vault 变更，触发一次性 `OneTimeWorkRequest`（含 5 min 延迟合并）。如果耗电 A/B 测试显示收益小于成本，则**放弃此路径**，退化为纯周期。

**同步子流程顺序**

```
1. 权限自检（SAF）           → 失败：暂停 + 通知
2. 变更扫描 + 防抖判断       → 静默期：返回 RETRY
3. git fetch                → 失败：网络错误，指数退避重试
4. 冲突前置检测（见 4.5）    → 不可合并冲突：暂停 + 通知
5. git merge (fast-forward / auto)
6. 本地变更 → git add / commit（空则跳过）
7. git push                 → 401：暂停 + 通知；网络错：退避重试
8. 写 sync_log
```

### 4.3 性能工程

**内存与 OOM 防线**

- **仓库规模上限（软约束）**：文件数 ≤ 10,000，单文件 ≤ 10 MB，仓库 ≤ 500 MB。超限给明确提示而非崩溃。
- **Diff 提取**：避免 `DiffFormatter` 把整个 diff 写入 `ByteArrayOutputStream`；改用**流式 DiffFormatter** 按 hunk 回调写入磁盘临时文件，Domain 层逐块解析为 `List<DiffLine>`。
- **Diff 渲染**：Compose `LazyColumn` + 稳定 `key`（行号+文件路径哈希）；单行 Composable 轻量，避免在 `Modifier` 链里创建临时对象。
- **目录树**：10,000 文件的平铺列表用 `LazyColumn` + paging；展开/折叠只更新受影响节点。禁止一次性 `List<TreeNode>.flatten()` 后整表渲染。

**JGit 调优**

- **Shallow Clone**（Phase 2 评估）：`--depth=1` 可大幅压缩首次克隆空间，但 JGit 对 shallow 支持有限，需在仓库绑定前做兼容性探测。
- **关闭 `PackFile` 缓存膨胀**：`WindowCacheConfig` 按设备内存档位调整（低端机 `packedGitLimit` 设 32 MB，中高端 128 MB）。
- **复用 Repository 对象**：Worker 内单次生命周期共享一个 `Repository` 实例，结束时 `use { }` 确保关闭。
- **避免 `fullSync` 全量遍历**：增量 `status`（`IndexDiff`）代替全仓扫描，Obsidian Vault 上可节省 10x+。

**启动性能**

- 首屏可交互 ≤ 1.5 s（中端机型）：Hilt 仅在实际被注入时初始化；JGit 相关依赖**延迟到首次同步**才加载。
- 冷启动不做 Git I/O；Home 页只从 Room 读最近 sync_log 摘要。

### 4.4 耗电预算与 Doze 兼容

这是 Android 后台工具生死线。

**硬性规则**

- **只用 WorkManager**，不创建长驻 Service、不使用 `AlarmManager.setExactAndAllowWhileIdle`。
- **周期最短 15 min**（WorkManager 硬约束，也是 Doze 友好值）。
- **默认只在有网 + 非低电 + 非低存储**时触发。用户可叠加"仅 Wi-Fi"、"仅充电时"。
- **网络请求必须支持 HTTP keep-alive**，JGit `TransportHttp` 复用 OkHttp 客户端（Phase 2 对接），减少 TLS 握手耗电。
- **禁止轮询**：配置页实时状态一律走 `Flow` 推送，不走 `while (true) delay()`。

**电量预算目标**

- 典型用户（日均 5 次编辑、每次聚合 commit）：日均耗电 ≤ 2%。
- 首次 clone 100 MB 仓库：单次电量消耗 ≤ 3%，执行中展示前台进度但**不抢焦**（用 `NotificationCompat` 进度条而非 Activity）。

**Doze 兼容**

- 所有 Worker 默认加 `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` 的**降级策略**——配额不足时退化为普通任务。
- 冷启动检测 `last_sync_at` 距今 > 策略间隔 × 2 时，立即触发一次 catch-up 同步（Doze 唤醒后的补偿）。
- 厂商 ROM 杀后台场景：提供"引导加白"深链到系统电池优化页面，但**不硬性要求**，策略必须在未加白时仍能工作（只是同步间隔会被系统延长）。

### 4.5 冲突分类与处理策略

静默工具的第一纪律：**宁可暂停，不可误合并。**

| 冲突类型 | 触发场景 | MVP 策略 | 后续演进 |
| :---- | :---- | :---- | :---- |
| Fast-forward | 本地无变更，远程有新 commit | 自动 pull | — |
| Auto-merge 成功 | 改动不同文件或不同段落 | 自动 merge + push | — |
| 文本行级冲突 | 同文件同区域两端都改 | **暂停 + 通知**，整文件二选一 | Phase 3 行级 chunk 选择 |
| 二进制冲突 | 图片/PDF 等 | **暂停 + 通知**，整文件二选一 | 同 MVP |
| 删除 vs 修改 | 一端删、一端改 | **暂停 + 通知**，整文件二选一 | 同 MVP |
| 远程 force-push / rewrite | 远程历史被改写 | **暂停 + 通知**，**不自动 reset**，引导手动处理 | 同 MVP |

暂停状态在 Room 中落地为 `repository.sync_state = PAUSED_CONFLICT`，Worker 启动时先查此状态；状态未由用户手动解除前，不尝试任何 push。

### 4.6 Diff 视图渲染

- **提取**：JGit `DiffFormatter` 以 hunk 为粒度流式输出。
- **解析**：Domain 层 `DiffParser` 产出 `List<DiffLine>`（行号 / 内容 / 状态：ADDED/REMOVED/CONTEXT/NO_NEWLINE）。
- **渲染**：`LazyColumn` + 行级 `key`；背景色由状态标志决定，避免在 composable 内做字符串运算。
- **超大文件降级**：> 10,000 行自动提示"仅展示前 5000 行变更，完整差异请在桌面查看"——不冒 OOM 风险。

---

## 5. 安全与隐私模型

这是本项目的**合规红线**，不接受任何妥协。

### 5.1 凭证存储

| 凭证 | 存储 | 加密 | 备注 |
| :---- | :---- | :---- | :---- |
| GitHub PAT | `EncryptedFile` or DataStore + Tink AEAD | Android Keystore 派生主密钥（`AES-256-GCM`） | 内存中以 `CharArray` 持有，使用后立即 `Arrays.fill('\u0000')` |
| SSH 私钥（Phase 3+） | 应用私有目录 + Keystore 加密 | 同上 | 私钥生成优先 `ed25519` |
| 远程 URL / 用户名 | DataStore（明文可接受） | — | 非敏感 |

**禁止项（一律 CI 检查）**

- 禁止 `SharedPreferences` 存任何含 `token` / `secret` / `key` 前缀字段。
- 禁止把凭证写入 `Log.*` / 异常堆栈 / `Intent.Extra` / `Bundle`。
- 禁止凭证落到可被 ADB 读取的路径（`/sdcard/*`）。
- 禁止在 `toString()` 中暴露包含凭证的对象——`Credential` 类强制自定义 `toString` 返回 `Credential(redacted)`。

### 5.2 数据不出端

- 应用**不上报**任何用户笔记内容、文件名、仓库地址、commit message、堆栈。
- 崩溃堆栈仅本地保留；用户显式授权方可导出为文件分享给开发者。
- 网络白名单：`github.com` / `api.github.com` / 用户配置的 Git 远程 Host。其他域名 `OkHttpClient` 拦截器一律拒绝。
- **不集成** Firebase Crashlytics / Google Analytics / 任何第三方埋点 SDK。

### 5.3 权限最小化

| 权限 | 用途 | 策略 |
| :---- | :---- | :---- |
| `INTERNET` | Git 网络 | Normal，安装时生效 |
| `POST_NOTIFICATIONS`（Android 13+） | 冲突/失败通知 | 首次触发通知前申请；被拒则在 APP 首页用内置 badge 降级提示 |
| 存储相关 | — | **不申请** `READ/WRITE_EXTERNAL_STORAGE`，只走 SAF |
| `FOREGROUND_SERVICE` / 精确闹钟 | — | 不使用 |

### 5.4 运行时完整性

- **Root / 模拟器检测（可选，Phase 3+）**：检测到 root 时提示"凭证安全风险，请确认在可信设备上使用"，不强制退出。
- **屏幕录制保护**：含凭证输入的界面（PAT 粘贴页）启用 `FLAG_SECURE`。
- **剪贴板保护**：PAT 粘贴框 60 秒后自动清空剪贴板（仅对自身写入的剪贴板内容）。

---

## 6. 数据模型

### 6.1 Room 表（简化定义）

```sql
Repository(
  id               INTEGER PK,
  display_name     TEXT,
  remote_url       TEXT,
  auth_type        TEXT,    -- PAT | SSH
  auth_ref         TEXT,    -- CredentialStore 的引用 key
  local_tree_uri   TEXT,    -- SAF URI
  local_abs_path   TEXT?,   -- 反解后的绝对路径
  default_branch   TEXT,
  sync_policy_id   INTEGER FK,
  sync_state       TEXT,    -- IDLE | RUNNING | PAUSED_CONFLICT | PAUSED_AUTH | PAUSED_FS
  last_sync_at     INTEGER?,
  last_sync_result TEXT?,
  created_at       INTEGER
)

SyncPolicy(
  id                      INTEGER PK,
  interval_minutes        INTEGER,   -- 15 | 30 | 60 | MANUAL_ONLY
  require_unmetered       INTEGER,   -- 0/1
  require_charging        INTEGER,   -- 0/1
  commit_message_template TEXT
)

SyncLog(
  id              INTEGER PK,
  repo_id         INTEGER FK,
  started_at      INTEGER,
  ended_at        INTEGER,
  trigger         TEXT,     -- MANUAL | PERIODIC | EVENT | CATCHUP
  result          TEXT,     -- OK | CONFLICT | NETWORK_ERR | AUTH_ERR | FS_ERR | ABORTED
  commits_pulled  INTEGER,
  commits_pushed  INTEGER,
  files_changed   INTEGER,
  error_code      TEXT?,
  error_msg       TEXT?
)

FileTreeCache(                       -- Phase 3
  repo_id        INTEGER,
  path           TEXT,
  type           TEXT,     -- FILE | DIR
  git_status     TEXT,     -- CLEAN | MODIFIED | UNTRACKED | STAGED | CONFLICT
  size           INTEGER,
  last_modified  INTEGER,
  PRIMARY KEY(repo_id, path)
)
```

`SyncLog` 保留最近 500 条 + 7 天，超出自动滚动清理，保证 Room 不膨胀影响冷启动。

### 6.2 DataStore 键位

```
encrypted/
  cred_<repoId>         -- PAT / SSH 私钥密文

preferences/
  theme                 -- LIGHT | DARK | SYSTEM
  last_app_version
  allow_log_export      -- 默认 false
  onboarding_done
```

---

## 7. 可观测性（本地优先）

后台静默同步最怕"默默失败"，必须让用户**能看见、能自助排查**。

- **结构化日志**：Timber + 自定义文件 Appender，单文件 1 MB，保留最近 7 天，位于应用私有目录 `logs/`。凭证写入拦截器强制过滤。
- **同步审计页**：展示最近 30 次同步的时间、触发方式、结果、耗时、变更统计。失败项可展开错误码与排查提示。
- **一键导出日志**：必须用户显式授权，导出前弹窗展示"将包含仓库名、文件路径（不含文件内容），请确认后分享"。
- **通知分级**：
  - `CONFLICT` / `AUTH_ERR` / `FS_PERM_LOST`：高优先级，立即通知
  - `NETWORK_ERR`：连续失败 ≥ 3 次才提醒
  - 成功：永不通知

---

## 8. 失败与降级矩阵

| 场景 | 检测 | 降级动作 |
| :---- | :---- | :---- |
| SAF 权限被回收 | Worker 启动前 `persistedUriPermissions` 校验 | `sync_state = PAUSED_FS` + 通知重新授权 |
| 磁盘空间不足 | `StatFs` 预检（需至少 2× 仓库大小余量） | 本次跳过 + 通知 |
| JGit OOM / 崩溃 | Worker try-catch + `UncaughtExceptionHandler` | 标记 FAILED，连续 3 次失败暂停自动同步 |
| Token 过期 / 撤销 | HTTP 401 | `sync_state = PAUSED_AUTH` + 通知更新 |
| 仓库不存在 / 403 | HTTP 404 / 403 | 通知检查配置 |
| WorkManager 被厂商杀 | 冷启动检测 `last_sync_at` 老化 | 触发 catch-up 同步 |
| 远程 force-push | `fetch` 后比对本地 HEAD 不是远端祖先 | 暂停 + 通知，不自动 reset |
| 仓库规模超限 | 绑定前预检 | 拒绝绑定并明确提示 |

---

## 9. 演进路线

原则：每个 Phase 的每个子项都必须**可独立验收**。

### Phase 1 — MVP 核心链路（2~3 周）

| 子项 | 验收标准 |
| :---- | :---- |
| P1.1 SAF 授权 | 选择 Vault 目录并固化权限；重启后仍有效 |
| P1.2 PAT 加密存储 | Keystore 加密；ADB dump 无法读出明文 |
| P1.3 JGit Clone | 能 clone ≤ 50 MB 的 GitHub 私有仓库 |
| P1.4 手动 Commit + Push | UI 触发，成功后 sync_log 落库 |
| P1.5 手动 Pull + Auto-merge | 无冲突场景正常 merge |
| P1.6 基础 UI + 导航 | Compose + Hilt + 仓库详情页 |

### Phase 2 — 自动化静默同步（3~4 周）

| 子项 | 验收标准 |
| :---- | :---- |
| P2.1 WorkManager 周期任务 | Doze 下行为符合预期 |
| P2.2 同步策略配置 | 间隔 / 网络 / 电量条件生效 |
| P2.3 防抖 + 空 commit 抑制 | 连续保存 10 次只产出 1 个 commit |
| P2.4 冲突检测 + 通知 | §4.5 MVP 策略全部实现 |
| P2.5 同步审计 + 日志导出 | §7 要求达成 |
| P2.6 NFR 达标 | 耗电 ≤ 2% / 日；同步成功率 ≥ 98% |

### Phase 3 — 冲突可视化（3~4 周）

| 子项 | 验收标准 |
| :---- | :---- |
| P3.1 目录树 | 10,000 文件不卡顿 |
| P3.2 单栏 Diff 视图 | 50,000 行不 OOM；降级策略生效 |
| P3.3 整文件二选一解决 | 操作后自动恢复同步 |
| P3.4 SSH Key 支持 | 生成 / 导入 ed25519；加密存储 |

### Phase 4+ 候选（需 `/spec_review` 确认价值）

行级冲突解决 / 自定义 JGit `FS` / 多仓库 / GitLab 兼容 / 桌面端配对 Wi-Fi Direct / Git hooks / shallow clone。

---

## 10. 待决策的开放问题（`/spec` 前置输入）

1. **`.gitignore` 自动生成**：Obsidian `.obsidian/workspace.json` 频繁变更，是否默认纳入 ignore？
2. **大仓库首次 clone UX**：> 100 MB 是否需要前台进度页？与"隐形"原则如何平衡？
3. **shallow clone 适用性**：JGit 的 `--depth=1` 支持需实测。
4. **通知权限被拒的降级路径**：Android 13+ 拒绝通知时，冲突如何触达？
5. **只读浏览模式**：纯查看 GitHub 仓库不修改，是否值得单独做轻量分支？

---

*附录：*

- [JGit 官方仓库](https://github.com/eclipse-jgit/jgit)
- [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [WorkManager 指南](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Android Keystore System](https://developer.android.com/training/articles/keystore)
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
