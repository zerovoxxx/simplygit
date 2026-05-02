# 迭代 2 Spec：自动化静默同步

> **文档状态: 已完成**
>
> **开发进度（2026-05-02 实施快照）**：
> - G1 周期调度 — ✅ 已完成（`runtime/GitSyncWorker.kt` + `SyncSchedulerImpl.kt` + `WorkTags`；`SimplyGitApp` 实现 `Configuration.Provider` 注入 `HiltWorkerFactory`）
> - G2 策略配置 — ✅ 已完成（`SyncPolicyRepository` Room 后端 + `UpdateSyncPolicyUseCase` + `SyncPolicyScreen`；`ExistingPeriodicWorkPolicy.UPDATE` 保证下周期生效）
> - G3 防抖 + 空 commit 抑制 — ✅ 已完成（`DebounceGuard.withinQuietWindow` + `GitRepository.commitAllIfDirty` 返回 nullable）
> - G4 冲突分类 — ✅ 已完成（`data/git/ConflictClassifier.kt` 下沉 Data 层，`internal class`；六种 `ConflictClass` 映射；二进制兜底走 `attributesNodeProvider` + 前 8KB NUL 扫描；仅在 `Git.use{}` 作用域内被调用）
> - G5 Room 审计 — ✅ 已完成（`SyncLogEntity` + `SyncLogDao.pruneExpired(500 rows / 7 days)` + `SyncAuditScreen` + `SyncAuditDetailScreen`；v1→v2 Migration 新增 `sync_log.errorType`，Audit Detail 页展示原始异常类型）
> - G6 通知分级 — ✅ 已完成（`NotificationChannels` 双通道 + `NotificationPublisherImpl` + 首页 badge；`POST_NOTIFICATIONS` 运行时申请在首次 Save 触发）
> - G7 失败降级矩阵 + 恢复同步 — ✅ 已完成（`SyncState` 四态 + `PAUSED_STATES` 短路 + `ResumeFromPauseUseCase` + Banner 二次确认；BROKEN 状态同时显示"查看日志"与"恢复同步"两个按钮）
> - G8 catch-up — ✅ 已完成（`CatchUpTrigger.triggerIfStale`，阈值 `interval * 2`；`MainActivity.onCreate` 异步触发）
> - G9 日志导出 — ✅ 已完成（`ExportLogsUseCase` 打包 sync_log.json + `diagnostics-YYYY-MM-DD.log` → `filesDir/exports/*.zip` → `FileProvider` + `ACTION_SEND`；二次确认弹窗）
> - G10 NFR — ⏳ 待真机手测（日均耗电 / 同步成功率 / 通知触达率）；编译链路 `assembleDebug` / `lintDebug` / `detekt` 均通过
>
> **P0/P1/P2 闭环**：I-1（SyncErrorKind + classifyKind）、I-2（`currentOrNull` / `snapshotIdentity` / `pullAndClassify` / `commitAllIfDirty`）、I-3（`ConflictClassifier` 下沉到 `data/git/`，`internal class`，返回 `PullOutcomeClassified` 纯数据 DTO）、I-4（`DiagnosticsLogger` 按日滚动 + `snapshotRecentLogFiles`）、I-5（`RepositoryEntity.syncPolicyId` `@ForeignKey RESTRICT` + `Index`）、I-7（Home 四手动按钮独立于状态机，代码注释已标注）、I-8（DataStore→Room 迁移在 `RepoBindingRepositoryImpl.migrateFromDataStoreIfNeeded`，`SafUriStore` 标 `@Deprecated`）均已落地。
>
> **CR 修订闭环（2026-05-02 第二轮）**：CR-P2-01（`SimplyGitApp.onCreate` 显式异步挂接迁移 + `migration_v1_done` / `migration_v1_retry_count` 双标记，3 次失败降级为 UI 重绑提示 + `isMigrationDisabled`）、CR-P2-02（迁移成功下轮启动 `clearLegacyBindingKeys`）、CR-P2-03（`ConflictClassifier` 改 `internal class`）、CR-P2-05（`SyncLogRepository.observeRepoState` KDoc 明确 N4 单仓下忽略 `repoId`）、CR-P2-06（Spec §4.3 明确 `Unknown` 走 `Result.success` 不 backoff，注释补齐）、CR-P3-01（`BROKEN` 状态 Banner 同时提供"查看日志"+"恢复同步"）、CR-P3-02（`SyncLogEntity.errorType` 新增字段 + Room Migration(1,2) + Audit Detail 页展示）均已落地。
>
> **架构边界 A11d 静态检查结果**：`grep -rE "import org\.eclipse\.jgit" app/src/main/java/com/example/simplygit/{domain,ui}/` 零命中；`grep -rE "catch\s*\(\s*\w+\s*:\s*(Transport|IO)Exception" ...` 零命中。

## 1. 文档信息

- 文档版本: v1.1
- 作者: alexjhwen
- 日期: 2026-05-02
- 迭代目标: 让 Obsidian Vault 的 Git 同步在 APP 关闭状态下按策略自主执行——用户配置一次策略后，后台周期 pull / commit / push，冲突前置暂停并通过通知触达；落地总方案 §1.2 "零打扰同步" 的核心价值主张。
- 前置依赖: 迭代 1（MVP 核心链路，已提供 SAF 授权、ESP 凭证、JGit Clone/Pull/Commit/Push、MVVM 四层骨架、`JGitExceptionSanitizer`、`DiagnosticsLogger`）。
- 与总方案的对齐声明：本迭代严格对齐总方案 §9 Phase 2（P2.1 ~ P2.6），**未引入总方案变更**。总方案 §4.2 的"事件触发同步（`FileObserver`）"在本迭代**不实现**（见 §2.4 N6）；总方案 §4.4 "OkHttp `TransportHttp` 对接" 推迟到 Phase 3（见 §2.4 N5）——两者均属于"若耗电 A/B 显示收益小于成本则放弃/推迟"的条件路径，不算范围变更。

## 2. 背景与目标

### 2.1 当前状态

迭代 1 已跑通**前台手动链路**：UI 点击 → UseCase → `JGitDataSource` → JGit；`SafPathResolver` / `CredentialDataSource` / `JGitExceptionSanitizer` / `DiagnosticsLogger` 均已落地。Phase 2 要解决的是 **"APP 不在前台"场景下的执行链路**：

- 无 WorkManager 接管周期调度，用户必须每次手动进 APP，违背 §1.1 "除了第一次授权不应再需要打开 APP" 的定位；
- 手动 Pull 对冲突是"报错原样透传"，后台链路必须**自动分类冲突**：可合并的继续，不可合并的暂停 + 通知；
- 无同步策略配置面、无审计表——后台"默默失败"时用户无从排查（总方案 §7 生死线）；
- 无变更防抖——若接入高频触发会把 Obsidian 每次自动保存都发成一个 commit。

未验证的技术风险：
- **Doze / App Standby 下 WorkManager 的实际唤醒节奏**与 §1.6 "日均耗电 ≤ 2%" 的兼容性；
- **JGit 在后台线程的资源生命周期**（`Repository.close()` 漏调即可让 pack mmap 泄漏累积 OOM）；
- **冲突分类决策**在 JGit API 层能否覆盖 §4.5 六类冲突且对用户可解释。

### 2.2 生态与行业调研

1. **现有方案**：
   - **WorkManager 官方**（`androidx.work:work-runtime-ktx`）：Android 后台周期任务的**唯一合规选择**（总方案 §3.2 已锁定），AndroidX 一方维护，Doze / App Standby / JobScheduler 三栈适配由 Google 承担；2.9.x `CoroutineWorker` 原生支持协程。
   - **Obsidian Git Mobile**（isomorphic-git + Capacitor）：事件触发 + 前台 Service 轮询，Doze 下差、耗电高、频繁被厂商 ROM 杀——是本项目**反面教材**而非参考。
   - **Syncthing Android**：`ForegroundService` + 长驻通知，违反 §3.2 "显式拒绝 Foreground Service" 红线，不可参考。
   - **MGit**：仅手动触发，不含后台调度。
   - **`FileObserver` / `ContentObserver`**：SAF 授权目录通常由 `DocumentsProvider` 暴露，`FileObserver` 监听绝对路径仅在"绝对路径直连"下可用；厂商 ROM 对 `FileObserver` 后台送达可靠性极低（EMUI / MIUI 离线 > 10 min 即丢事件）。
2. **行业实践**：
   - **iCloud / OneDrive / Google Drive Android** 均走 "WorkManager / JobScheduler 周期 + 服务端 push"，端侧无 `FileObserver`；离线变更聚合走"周期扫描 + 内容哈希"。
   - Jetpack 官方文档明确：**周期性后台同步首选 WorkManager `PeriodicWorkRequest`**；事件驱动仅适用于"前台 App 内 + 应用私有目录"。
   - 冲突处理业界通用：**Git 端用 `merge` 而非 `rebase`**（rebase 改写远端公开分支违背 §4.5 红线）；冲突分类基于 JGit `MergeResult.MergeStatus` 官方枚举，非自建。
3. **调研结论**：**复用 WorkManager + JGit MergeResult**，不自建。`FileObserver` 因 "SAF 不兼容 + 厂商可靠性差" 双重原因**不引入**（从总方案 §4.2 "Phase 2 评估" 升格为"Phase 2 明确不做"）。同步策略配置与审计表为应用私有业务逻辑，自建但遵循业界周期调度范式。

### 2.3 本次迭代目标

1. **G1** 新增 **Runtime 层** + `GitSyncWorker`（`CoroutineWorker`）按 `PeriodicWorkRequest` 调度，默认 15 min，遵循 `NetworkType.CONNECTED` / `requiresBatteryNotLow` / `requiresStorageNotLow`（总方案 §4.2）。
2. **G2** 新增 **同步策略配置** `SyncPolicy`：间隔（15/30/60/MANUAL_ONLY）、仅 Wi-Fi、仅充电、commit 模板；策略变更**下次周期生效**（不抢占当前运行 Worker）。
3. **G3** Worker 内落地 **防抖 + 空 commit 抑制 + 合并提交**：窗口内变更聚合为一次 commit；最后一次文件修改 < 2 min 则跳过（总方案 §4.2）。
4. **G4** 新增 **`ConflictClassifier`**（Domain 层）：把 `PullResult.mergeResult.mergeStatus` 映射到 §4.5 六类；可合并继续，不可合并把 `sync_state` 置 `PAUSED_CONFLICT` 并发通知。
5. **G5** 新增 **Room 审计** `SyncLog`（对齐 §6.1），Worker 每次结束落记录；新增"同步审计" UI 展示最近 30 条 + 详情，自动清理 500 条 / 7 天。
6. **G6** 新增 **通知分级**（§7）：`CONFLICT` / `AUTH_ERR` / `FS_PERM_LOST` 立即通知；`NETWORK_ERR` 连续失败 ≥ 3 次才通知；成功永不通知。申请 `POST_NOTIFICATIONS`（A13+）+ "拒绝"降级为首页 badge。
7. **G7** 落地 **失败降级矩阵**（§8）：`PAUSED_FS` / `PAUSED_AUTH` / `PAUSED_CONFLICT` / `BROKEN` 四态入库 + UI 可视 + 用户手动"恢复同步"入口。
8. **G8** 新增 **catch-up 同步**：APP 冷启动检测 `last_sync_at` 距今 > 间隔 × 2 时触发一次 `OneTimeWorkRequest`（§4.4）。
9. **G9** 新增 **日志一键导出**（`logs/` + `SyncLog`）：二次确认弹窗 → ZIP → `ACTION_SEND` 分享（§7 / §5.2 "数据不出端"，不自动上传）。
10. **G10** **NFR 达标**：日均耗电 ≤ 2%；无冲突场景同步成功率 ≥ 98%；冲突通知触达率 100%（在系统通知权限已授予前提下）。

### 2.4 非目标

1. **N1** 不做**冲突解决 UI**（行级 / 整文件二选一）：Phase 2 只"分类 + 暂停 + 通知"，用户路径为"进 APP 看错误 → 手动解除 → 回到桌面端处理"。整文件二选一 UI 归 Phase 3（总方案 §9 P3.3）。
2. **N2** 不做目录树 UI 与 Diff 视图（Phase 3）。
3. **N3** 不做 SSH Key（Phase 3+）；继续仅支持 PAT（HTTPS）。
4. **N4** 不做多仓并行（§1.4 非目标）；`SyncPolicy` 表结构为多仓预留但本迭代只有 1 条记录。
5. **N5** 不做 OkHttp `TransportHttp` 对接（总方案 §4.4 "Phase 2 对接"）：实测收益与改造成本失衡，推迟到 Phase 3；继续用 JGit 默认 HTTP。
6. **N6** 不做 `FileObserver` / `ContentObserver` 事件触发同步（总方案 §4.2 "Phase 2 评估"）：§2.2 调研显示 SAF 兼容性 + 厂商可靠性均不过关；"周期 15 min + 静默期 2 min" 已覆盖 Obsidian 典型节奏。事件触发彻底推迟到 Phase 3+ 二次评估。
7. **N7** 不做 Shallow Clone（总方案 §4.3 "Phase 2 评估"）：实测 JGit 6.10 对 `--depth=1` 的 `refs/pull/*` 仍有兼容性 bug。
8. **N8** 不做 `WindowCacheConfig` 分档优化，延后到 Phase 3 做大仓压测。
9. **N9** 不做 Root 检测 / 扩展屏幕录制保护（§5.4 "可选，Phase 3+"）；`FLAG_SECURE` 在迭代 1 已覆盖。
10. **N10** 不做崩溃堆栈的结构化全局捕获；Phase 2 只把 Worker 内部异常进 `SyncLog` + `DiagnosticsLogger`。
11. **N11** 不做通知的**仓库级静默策略**（"这个冲突不再提醒我" 等）；通知触发完全由状态机决定，Phase 3 再评估。

## 3. 方案决策

### 3.1 方案对比

#### 3.1.1 后台调度引擎

| 维度 | A：WorkManager `PeriodicWorkRequest` | B：`AlarmManager` + `JobIntentService` | C：`ForegroundService` 常驻 |
|---|---|---|---|
| Doze 兼容 | 官方保证；配额不足自动延后 | Android 6+ Doze 下 inexact 被延至 maintenance window，不可控 | Doze 下不被杀但 CPU 限速，耗电飙升 |
| 厂商 ROM 存活率 | 中～高（GMS 加持） | 低（EMUI/MIUI 深 Doze 可静默 1~2h 不唤醒） | 高（但用户手动清理概率高） |
| 相对耗电基线 | 1× | 1.2～3× | 2～5× |
| 实现复杂度 | 低（官方 API） | 中（需自管约束） | 高（需前台通知 + Android 14 FGS 类型限制） |
| **数据权威性** | AndroidX 一方维护 | Framework API | Framework API |
| **供应链安全** | Google 官方 | Google 官方 | Google 官方 |
| 与本项目红线冲突 | 无 | 与 §4.4 "只用 WorkManager" 冲突 | **违反 §3.2 "显式拒绝 Foreground Service"** |
| 优点 | 合规、Doze 友好、配额自管 | 控制力强 | 存活率高 |
| 缺点 | 最短周期 15 min（硬约束） | 不符合现代 Android 规范 | 违反红线 |

#### 3.1.2 冲突分类决策源

| 维度 | A：`MergeResult.MergeStatus` 原生映射 | B：自写 `TreeWalk` + `DirCache` 三方合并 |
|---|---|---|
| 代码量（预估） | ~80 行 | ~400 行 |
| JGit 覆盖度 | 官方枚举覆盖 §4.5 六类中的 5 类；二进制冲突需 3 行辅助判定 | 全可控但需重写合并算法 |
| **数据权威性** | Eclipse JGit 一手 API | 自建 |
| **供应链安全** | JGit 6.x 已运行多年 | 自建代码需自审 + 单测 |
| 优点 | 少代码、少 bug、随 JGit 升级自然获得新 case | 完全可控 |
| 缺点 | 二进制判定需辅助代码 | 工作量 5× 且无差异化价值 |

#### 3.1.3 同步策略存储

| 维度 | A：Room 表 `SyncPolicy` | B：DataStore Preferences 扁平键 |
|---|---|---|
| 代码量 | ~120 行（含 Dao） | ~40 行 |
| 多仓扩展成本 | 零（天然 `repoId` FK） | 高（需改 key 为 `<repoId>.<field>`） |
| 事务一致性 | 与 `Repository` / `SyncLog` 同库，可事务 | 跨存储无事务 |
| 与总方案一致性 | §6.1 已锁定 Room | 偏离 |
| 优点 | 后续扩展零返工 | 简单 |
| 缺点 | 代码量稍大 | 多仓扩展返工成本高 |

### 3.2 选型结论

- **后台调度**：A（WorkManager `PeriodicWorkRequest`），与总方案 §3.2 / §4.4 一致，也是唯一不违反红线的合规选择。
- **冲突分类**：A（`MergeResult.MergeStatus` 映射），80 行 vs 400 行、JGit 一手 vs 自建、枚举随版本演进 → 全面胜出。二进制补丁用 `attributesNodeProvider` + 前 8KB 字节 NUL 扫描兜底，计入 §4.2 实现。
- **策略存储**：A（Room 表），与总方案 §6.1 一致；本迭代虽只 1 条记录，多仓扩展时零返工的价值大于 +80 行代码成本。
- **事件触发**：不选（§2.4 N6），理由见 §2.2 调研。

对 §2.2 呼应：**调度 = WM 一方复用 / 冲突 = JGit 一手复用 / 策略 = Room 复用 / 事件 = 明确不做**，四条均符合"不重复造轮子 + 不违反红线"。

## 4. 详细设计

### 4.1 模块分层与目录结构

> 包名基线沿用 `com.example.simplygit`。本迭代新增 `runtime` / `notification` 子包，与迭代 1 的 `data` / `domain` / `ui` 同级；`data/sync/` 放 Room 实体与 Dao。

```
app/src/main/java/com/example/simplygit/
├── SimplyGitApp.kt                     // 扩展：实现 Configuration.Provider 注入 HiltWorkerFactory
├── di/
│   ├── DataModule.kt                   // 迭代 1 已有
│   ├── DispatcherModule.kt             // 迭代 1 已有
│   ├── WorkerModule.kt                 // 新增：WorkManager 单例 + HiltWorkerFactory
│   ├── DatabaseModule.kt               // 新增：Room Database / Dao
│   └── NotificationModule.kt           // 新增：NotificationManagerCompat / Channel
├── data/
│   ├── credential/ …                   // 迭代 1 已有；本迭代扩展 snapshotIdentity()（见 §6.2 / I-2）
│   ├── saf/ …                          // 迭代 1 已有
│   ├── git/                            // 迭代 1 已有；本迭代扩展
│   │   ├── (迭代 1 已有文件保留) …
│   │   ├── GitRepositoryImpl.kt        // 扩展：pullAndClassify / commitAllIfDirty（见 §6.2）
│   │   ├── JGitDataSource.kt           // 扩展：pullRaw 返回 PullResult；commitAllIfDirty 内部封装 status().isClean 判定
│   │   ├── ConflictClassifier.kt       // 新增（从 Domain 下沉到 Data）：消费 JGit MergeResult/Repository，仅在 Git.use{} 作用域内被调用；纯函数可单测（I-3 / I-9）
│   │   └── SyncErrorKind.kt            // 新增：sealed interface（Auth / Network / Unknown），由 JGitExceptionSanitizer 附带产出（I-1）
│   ├── binding/ …                      // 迭代 1 已有；本迭代迁移为 Room 后端（见 §4.6）
│   ├── sync/                           // 新增
│   │   ├── SimplygitDatabase.kt        // @Database(version=1, entities=[RepositoryEntity, SyncPolicyEntity, SyncLogEntity])
│   │   ├── RepositoryEntity.kt         // 迭代 1 的 RepoBinding 持久化升级为 Entity
│   │   ├── RepositoryDao.kt
│   │   ├── SyncPolicyEntity.kt
│   │   ├── SyncPolicyDao.kt
│   │   ├── SyncLogEntity.kt
│   │   ├── SyncLogDao.kt
│   │   ├── SyncPolicyRepositoryImpl.kt
│   │   └── SyncLogRepositoryImpl.kt
│   └── diagnostics/
│       └── DiagnosticsLogger.kt        // 迭代 1 已有；本迭代升级为按日滚动 diagnostics-YYYY-MM-DD.log（I-4，见 §4.9.1）
├── domain/
│   ├── model/
│   │   ├── Credential.kt               // 迭代 1 已有
│   │   ├── RepoBinding.kt              // 迭代 1 已有；新增 id 字段（Long，默认 0）
│   │   ├── GitOpResult.kt
│   │   ├── SyncPolicyModel.kt          // 新增
│   │   ├── SyncLogModel.kt             // 新增
│   │   ├── SyncState.kt                // 新增枚举
│   │   ├── SyncResult.kt               // 新增枚举
│   │   ├── ConflictClass.kt            // 新增枚举（纯数据分类值）
│   │   ├── PullOutcomeClassified.kt    // 新增：Data 层分类后返回的纯数据 DTO（I-3）
│   │   ├── SyncTrigger.kt              // 新增枚举：MANUAL | PERIODIC | CATCHUP
│   │   └── RunSyncOutcome.kt           // 新增 sealed
│   ├── usecase/
│   │   ├── (迭代 1 已有 5 个) …
│   │   ├── RunSyncUseCase.kt           // 新增：Phase 2 核心链路
│   │   ├── UpdateSyncPolicyUseCase.kt
│   │   ├── ResumeFromPauseUseCase.kt
│   │   ├── LoadSyncLogUseCase.kt
│   │   └── ExportLogsUseCase.kt
│   └── service/
│       ├── SyncScheduler.kt            // 接口
│       ├── SyncSchedulerImpl.kt        // WM 封装
│       ├── DebounceGuard.kt            // 扫描 vault 最后修改时间
│       └── NotificationPublisher.kt    // 接口
├── runtime/                            // 新增：Background Runtime（总方案 §3.1）
│   ├── GitSyncWorker.kt                // @HiltWorker CoroutineWorker
│   ├── GitSyncWorkerFactory.kt         // HiltWorkerFactory 供 Configuration.Provider
│   ├── CatchUpTrigger.kt               // 冷启动 stale 判定
│   └── WorkTags.kt                     // 常量：UNIQUE_NAME
├── notification/                       // 新增
│   ├── NotificationChannels.kt         // Channel ID / Importance 常量
│   ├── NotificationPublisherImpl.kt
│   └── NotificationPermissionHelper.kt // A13+ POST_NOTIFICATIONS 申请 + 拒绝降级
└── ui/
    ├── MainActivity.kt                 // 扩展：NavHost + catch-up 触发 + 通知 deep link 处理
    ├── theme/
    ├── home/                           // 扩展：顶部 SyncStateBanner + 齿轮 / 钟表入口 + 恢复同步按钮
    ├── policy/                         // 新增三件套
    │   ├── SyncPolicyScreen.kt
    │   ├── SyncPolicyViewModel.kt
    │   └── SyncPolicyUiState.kt
    └── audit/                          // 新增四件套
        ├── SyncAuditScreen.kt
        ├── SyncAuditDetailScreen.kt
        ├── SyncAuditViewModel.kt
        └── SyncAuditUiState.kt
```

### 4.1.1 依赖清单（R4）

**`gradle/libs.versions.toml` 增量**：

```toml
[versions]
# 迭代 1 已有版本保留
workmanager = "2.9.1"
hiltWork = "1.2.0"
room = "2.6.1"
navigationCompose = "2.8.0"

[libraries]
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workmanager" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "workmanager" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hiltWork" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hiltWork" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
```

**`app/build.gradle.kts` 增量**：

```kotlin
dependencies {
    // 迭代 1 已有依赖保留
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)

    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
}
```

> 注：迭代 1 已启用 KSP + Hilt，此处只加依赖；`androidx.hilt:hilt-work` + `@HiltWorker` 实现 WM ↔ Hilt 桥接。`coreLibraryDesugaring` 在迭代 1 已启用（R4 成对开关），新增 Room `java.time` 使用仍复用该配置，无需再次开启。

### 4.1.2 AndroidManifest 改动清单（R5）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- 迭代 1 已有 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- 新增：Android 13+ 通知权限；低版本系统自动忽略 -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".SimplyGitApp"
        android:allowBackup="false"
        …>

        <!-- 新增：关闭 WorkManager 默认 startup 初始化，由 SimplyGitApp 作为 Configuration.Provider 接管，
             以注入 HiltWorkerFactory。遗漏会导致 @HiltWorker 实例化抛 InstantiationException。-->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>

        <!-- 新增：导出日志走 FileProvider（不暴露 filesDir 绝对路径） -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <!-- 迭代 1 的 MainActivity 保留；新增通知 deep link 响应（通过 Intent Extras 传递 nav 参数，
             无需新增 intent-filter，沿用 MAIN/LAUNCHER）。-->
    </application>
</manifest>
```

**新增 `app/src/main/res/xml/file_paths.xml`**：

```xml
<paths>
    <files-path name="exports" path="exports/" />
</paths>
```

### 4.2 Data 层：`ConflictClassifier` + `SyncErrorKind`，Domain 层：`SyncState`

> **架构改动（I-3 / I-9）**：`ConflictClassifier` 从 Domain 层下沉到 `data/git/`，与 `GitRepositoryImpl` / `JGitDataSource` 同层。**只允许在 `Git.open(dir).use { }` 作用域内被调用**；Domain 层不再持有任何 JGit 原生类型（`Repository` / `PullResult` / `MergeResult` 均不透出）。对应黄金法则新增 P6 反模式。

**`ConflictClass` 枚举（对齐总方案 §4.5，放 Domain 层，纯数据分类值）**：

```kotlin
enum class ConflictClass {
    FAST_FORWARD,       // 本地无变更、远端有新 commit：auto-pull
    AUTO_MERGED,        // 不同文件 / 不同段落：auto-merge
    TEXT_LINE_CONFLICT, // 同文件同区域文本冲突：暂停 + 通知
    BINARY_CONFLICT,    // 二进制冲突：暂停 + 通知
    DELETE_MODIFY,      // 一端删、一端改：暂停 + 通知
    REMOTE_REWRITE,     // 远端 force-push / rebase：暂停 + 通知，禁止自动 reset
}

val UNRESOLVABLE_CONFLICTS = setOf(
    ConflictClass.TEXT_LINE_CONFLICT,
    ConflictClass.BINARY_CONFLICT,
    ConflictClass.DELETE_MODIFY,
    ConflictClass.REMOTE_REWRITE,
)
```

**`ConflictClassifier`（Data 层，包可见，由 `GitRepositoryImpl` 内部注入使用）**：

```kotlin
// 文件：data/git/ConflictClassifier.kt
internal class ConflictClassifier @Inject constructor() {

    /**
     * 仅在 Git.open(dir).use { git -> ... } 作用域内调用。
     * 入参 repo 为 git.repository，随 use 作用域结束自动释放；禁止缓存该引用。
     */
    fun classify(pullResult: PullResult, repo: Repository): ConflictClass {
        val merge = pullResult.mergeResult
            ?: return ConflictClass.FAST_FORWARD        // fetch-only 语义
        return when (merge.mergeStatus) {
            MergeResult.MergeStatus.FAST_FORWARD,
            MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> ConflictClass.FAST_FORWARD
            MergeResult.MergeStatus.MERGED,
            MergeResult.MergeStatus.MERGED_NOT_COMMITTED -> ConflictClass.AUTO_MERGED
            MergeResult.MergeStatus.CONFLICTING -> resolveConflictKind(merge, repo)
            MergeResult.MergeStatus.FAILED -> detectDeleteModifyOrRemoteRewrite(pullResult, repo)
            MergeResult.MergeStatus.NOT_SUPPORTED,
            MergeResult.MergeStatus.ABORTED -> ConflictClass.REMOTE_REWRITE
            else -> ConflictClass.TEXT_LINE_CONFLICT
        }
    }

    /** 冲突路径集合（供 `GitRepositoryImpl` 在同一 Git.use 作用域内落 SyncLog 用）。 */
    fun conflictPaths(pullResult: PullResult): List<String> =
        pullResult.mergeResult?.conflicts?.keys.orEmpty().toList()

    private fun resolveConflictKind(merge: MergeResult, repo: Repository): ConflictClass {
        val hasBinary = merge.conflicts?.keys.orEmpty().any { path ->
            isBinaryByAttribute(repo, path) || isBinaryByFirst8KB(repo, path)
        }
        return if (hasBinary) ConflictClass.BINARY_CONFLICT else ConflictClass.TEXT_LINE_CONFLICT
    }

    private fun detectDeleteModifyOrRemoteRewrite(pullResult: PullResult, repo: Repository): ConflictClass {
        val fetchHead = repo.resolve("FETCH_HEAD") ?: return ConflictClass.REMOTE_REWRITE
        val localHead = repo.resolve("HEAD") ?: return ConflictClass.REMOTE_REWRITE
        // 分叉历史（双向都不是祖先）→ REMOTE_REWRITE；否则判为 DELETE_MODIFY
        return if (!isAncestor(repo, localHead, fetchHead) && !isAncestor(repo, fetchHead, localHead))
            ConflictClass.REMOTE_REWRITE
        else ConflictClass.DELETE_MODIFY
    }

    /**
     * 通过 .gitattributes 判定二进制：读 HEAD tree 下该路径的 attributes，
     * 若 "binary" 或 "-text" 被 set 则返回 true。
     * 实现要点：
     *   val head = repo.resolve("HEAD") ?: return false
     *   RevWalk(repo).use { rw ->
     *     val tree = rw.parseCommit(head).tree
     *     TreeWalk.forPath(repo, path, tree)?.use { tw ->
     *       val attrs = tw.attributesNodeProvider?.workingTreeAttributesNode?.getAttributes(path, tw)
     *       return attrs?.isSet("binary") == true || attrs?.isUnset("text") == true
     *     }
     *   }
     *   return false
     */
    private fun isBinaryByAttribute(repo: Repository, path: String): Boolean

    /**
     * 兜底二进制判定：读 HEAD tree 下该路径的 blob 前 8KB，遇到 NUL 字节即判二进制。
     * 实现要点：
     *   val head = repo.resolve("HEAD") ?: return false
     *   RevWalk(repo).use { rw ->
     *     val tree = rw.parseCommit(head).tree
     *     TreeWalk.forPath(repo, path, tree)?.use { tw ->
     *       val id = tw.getObjectId(0)
     *       repo.newObjectReader().use { reader ->
     *         reader.open(id).openStream().use { input ->
     *           val buf = ByteArray(8192)
     *           val n = input.read(buf)
     *           return (0 until n).any { buf[it] == 0.toByte() }
     *         }
     *       }
     *     }
     *   }
     *   return false
     */
    private fun isBinaryByFirst8KB(repo: Repository, path: String): Boolean

    /** 用 RevWalk.isMergedInto 判定 base 是否为 tip 的祖先。实现：RevWalk(repo).use { rw -> rw.isMergedInto(rw.parseCommit(base), rw.parseCommit(tip)) }。 */
    private fun isAncestor(repo: Repository, base: ObjectId, tip: ObjectId): Boolean
}
```

- 设计原则：**classifier 不执行任何 Git 写操作**，只读 `PullResult` / `Repository`；返回值为**纯数据**（`ConflictClass` + `List<String>` 冲突路径），便于六种 `MergeStatus` 的 mock 单测全覆盖。
- 二进制判定兜底：`isBinaryByFirst8KB` 读 blob 前 8KB 扫 `\0` 字节（业界通用启发式），避免 `.gitattributes` 未配置时漏判。
- 两个 helper 的完整实现放 `GitRepositoryImpl` 内测试对应 Fixture；验收见 A4。

**`SyncErrorKind` + `JGitExceptionSanitizer` 扩展（I-1 P0 修复）**：

现有 `JGitExceptionSanitizer`（迭代 1）将所有异常包装为 `SanitizedGitException(message, originalType)` 单一类型，无法为 Worker 状态机提供 Auth / Network / Unknown 分流依据。本迭代扩展如下：

```kotlin
// 文件：data/git/SyncErrorKind.kt
sealed interface SyncErrorKind {
    data object Auth : SyncErrorKind       // HTTP 401 / 403 / PAT 过期
    data object Network : SyncErrorKind    // UnknownHost / NoRouteToHost / SocketException / ConnectTimeoutException
    data object Unknown : SyncErrorKind    // 其余（含 JGit 内部异常、OOM、IO）
}

// 文件：data/git/SanitizedGitException.kt（修改：新增 kind 字段，不破坏迭代 1 的 message / originalType 语义）
class SanitizedGitException(
    message: String,
    val originalType: String,
    val kind: SyncErrorKind,
) : RuntimeException(message)

// 文件：data/git/JGitExceptionSanitizer.kt（修改：sanitize 产出 kind）
@Singleton
class JGitExceptionSanitizer @Inject constructor() {
    fun sanitize(t: Throwable): SanitizedGitException {
        val chain = buildString { /* 迭代 1 逻辑不变 */ }
        val kind = classifyKind(t)
        return SanitizedGitException(chain, t.javaClass.simpleName, kind)
    }

    private fun classifyKind(t: Throwable): SyncErrorKind {
        // 沿 cause 链扫，命中第一个匹配即返回
        var cur: Throwable? = t
        val seen = HashSet<Throwable>()
        while (cur != null && seen.add(cur)) {
            when {
                // JGit 的 TransportException message 含 "not authorized" / "401" / "403"
                cur is org.eclipse.jgit.errors.TransportException -> {
                    val msg = cur.message.orEmpty()
                    if (msg.contains("not authorized", ignoreCase = true) ||
                        msg.contains("401") || msg.contains("403")) return SyncErrorKind.Auth
                }
                cur is java.net.UnknownHostException,
                cur is java.net.NoRouteToHostException,
                cur is java.net.ConnectException,
                cur is java.net.SocketException,
                cur is java.net.SocketTimeoutException -> return SyncErrorKind.Network
            }
            cur = cur.cause
        }
        return SyncErrorKind.Unknown
    }
}
```

- **契约**：迭代 1 所有已消费 `SanitizedGitException.message` / `originalType` 的调用点（Home 错误展示、`DiagnosticsLogger.logGitOpFailure`）保持不变；`kind` 为新增字段，旧调用无感知。
- **兼容性**：本次为**原地扩展**，不破坏迭代 1 契约；无需新建异常类。
- **R9 对齐**：`RunSyncUseCase` catch 时使用 `when (e.kind)` 分派，不新增 `AuthFailedException` / `NetworkException` 子类（避免 sealed type 爆炸）。

**`SyncState`**：

```kotlin
enum class SyncState {
    IDLE,
    RUNNING,
    PAUSED_CONFLICT,      // §4.5 四类不可合并冲突
    PAUSED_AUTH,          // HTTP 401
    PAUSED_FS,            // SAF 权限回收 / canRead false
    BROKEN,               // 连续 3 次未分类失败（OOM / 未知异常）
}

val PAUSED_STATES = setOf(
    SyncState.PAUSED_CONFLICT, SyncState.PAUSED_AUTH, SyncState.PAUSED_FS, SyncState.BROKEN
)
```

**状态机规则（禁止越级跳转）**：
- `IDLE → RUNNING`：Worker 启动，由 `RunSyncUseCase` 在 Room 事务中置位。
- `RUNNING → IDLE`：正常结束。
- `RUNNING → PAUSED_* / BROKEN`：按失败类型分类（见 §4.5）。
- `PAUSED_* / BROKEN → IDLE`：**仅由 `ResumeFromPauseUseCase`（用户"恢复同步"按钮）触发**；Worker 启动时若检测到 `syncState in PAUSED_STATES`，**立即返回 `Result.success()` 不做任何 Git 操作**（§4.5 "状态未由用户手动解除前不尝试任何 push"）。
  - **CR P3-01 澄清**：`BROKEN` 状态下 Banner 同时显示"查看日志"与"恢复同步"两个按钮。"查看日志"跳转审计页做诊断，"恢复同步"触发 `ResumeFromPauseUseCase` 回到 `IDLE`。`PAUSED_CONFLICT` / `PAUSED_AUTH` / `PAUSED_FS` 只显示"恢复同步"。

### 4.3 Domain 层：`RunSyncUseCase`（Phase 2 核心）

```kotlin
class RunSyncUseCase @Inject constructor(
    private val bindingRepo: RepoBindingRepository,
    private val credRepo: CredentialRepository,
    private val syncPolicyRepo: SyncPolicyRepository,
    private val syncLogRepo: SyncLogRepository,
    private val safPathResolver: SafPathResolver,
    private val gitRepo: GitRepository,
    private val debounce: DebounceGuard,
    private val notifier: NotificationPublisher,
    private val clock: Clock,
) {
    suspend operator fun invoke(trigger: SyncTrigger): RunSyncOutcome {
        val binding = bindingRepo.currentOrNull() ?: return RunSyncOutcome.NoBinding
        val snapshot = syncLogRepo.loadRepoState(binding.id)

        // 1. 暂停态短路（§4.5）
        if (snapshot.syncState in PAUSED_STATES)
            return RunSyncOutcome.SkippedPaused(snapshot.syncState)

        val logId = syncLogRepo.startLog(binding.id, trigger, clock.now())
        syncLogRepo.updateSyncState(binding.id, SyncState.RUNNING)
        return try {
            // 2. SAF 权限自检
            if (!safPathResolver.hasPersistedPermission(android.net.Uri.parse(binding.treeUri))) {
                pauseAndNotify(binding.id, SyncState.PAUSED_FS); notifier.publishFsPermissionLost(binding.id)
                syncLogRepo.finishLog(logId, SyncResult.FS_ERR, clock.now())
                return RunSyncOutcome.PausedFs
            }
            // 3. 变更防抖（§4.2 静默期）
            if (debounce.withinQuietWindow(binding.localAbsPath, QUIET_WINDOW)) {
                syncLogRepo.finishLog(logId, SyncResult.SKIPPED_DEBOUNCE, clock.now())
                syncLogRepo.updateSyncState(binding.id, SyncState.IDLE)
                return RunSyncOutcome.SkippedDebounce
            }
            // 4. 取 identity（username / email）与 pat（I-2 P0 修复：identity 改由 CredentialRepository.snapshotIdentity() 显式提供）
            val identity = credRepo.snapshotIdentity() ?: run {
                syncLogRepo.finishLog(logId, SyncResult.AUTH_ERR, clock.now())
                pauseAndNotify(binding.id, SyncState.PAUSED_AUTH); notifier.publishAuthFailed(binding.id)
                return RunSyncOutcome.MissingCredential
            }
            val pat = credRepo.loadPatOnce() ?: run {
                syncLogRepo.finishLog(logId, SyncResult.AUTH_ERR, clock.now())
                pauseAndNotify(binding.id, SyncState.PAUSED_AUTH); notifier.publishAuthFailed(binding.id)
                return RunSyncOutcome.MissingCredential
            }
            try {
                // 5. Pull 并在 Data 层内部完成分类（I-3：classifier 下沉，Domain 层只拿纯数据）
                val pullResult = gitRepo.pullAndClassify(binding, identity.username, pat)
                //    pullResult: PullOutcomeClassified(classification, commitsPulled, conflictPaths, mergeStatusName)
                if (pullResult.classification in UNRESOLVABLE_CONFLICTS) {
                    pauseAndNotify(binding.id, SyncState.PAUSED_CONFLICT)
                    notifier.publishConflict(binding.id, pullResult.classification)
                    syncLogRepo.finishLog(
                        logId, SyncResult.CONFLICT, clock.now(),
                        conflictClass = pullResult.classification,
                    )
                    return RunSyncOutcome.PausedConflict(pullResult.classification)
                }
                // 6. Commit（空则跳过）
                val commit = gitRepo.commitAllIfDirty(
                    binding,
                    message = buildMsg(syncPolicyRepo.current(), clock),
                    authorName = identity.username,
                    authorEmail = identity.email,
                )
                // 7. Push
                gitRepo.push(binding, identity.username, pat)
                syncLogRepo.finishLog(
                    logId, SyncResult.OK, clock.now(),
                    commitsPulled = pullResult.commitsPulled,
                    commitsPushed = if (commit != null) 1 else 0,
                    filesChanged = commit?.filesChanged ?: 0,
                )
                syncLogRepo.updateSyncState(binding.id, SyncState.IDLE)
                syncLogRepo.pruneExpired(clock.now())    // 尾部清理
                RunSyncOutcome.Ok
            } finally {
                java.util.Arrays.fill(pat, '\u0000')     // R3 强制
            }
        } catch (e: SanitizedGitException) {
            // I-1 P0 修复：按 SanitizedGitException.kind 分派，不再捕获不存在的 AuthFailedException / NetworkException
            when (e.kind) {
                SyncErrorKind.Auth -> {
                    pauseAndNotify(binding.id, SyncState.PAUSED_AUTH); notifier.publishAuthFailed(binding.id)
                    syncLogRepo.finishLog(logId, SyncResult.AUTH_ERR, clock.now(), errorMsg = e.message)
                    RunSyncOutcome.PausedAuth
                }
                SyncErrorKind.Network -> {
                    syncLogRepo.finishLog(logId, SyncResult.NETWORK_ERR, clock.now(), errorMsg = e.message)
                    if (syncLogRepo.recentConsecutiveFailures(binding.id) >= 3) {
                        pauseAndNotify(binding.id, SyncState.BROKEN); notifier.publishNetworkBroken(binding.id)
                    } else {
                        syncLogRepo.updateSyncState(binding.id, SyncState.IDLE)   // 单次失败不改状态，交给 WM backoff
                    }
                    RunSyncOutcome.NetworkErr
                }
                SyncErrorKind.Unknown -> {
                    syncLogRepo.finishLog(logId, SyncResult.ABORTED, clock.now(), errorMsg = e.message)
                    if (syncLogRepo.recentConsecutiveFailures(binding.id) >= 3) {
                        pauseAndNotify(binding.id, SyncState.BROKEN); notifier.publishNetworkBroken(binding.id)
                    } else {
                        syncLogRepo.updateSyncState(binding.id, SyncState.IDLE)
                    }
                    RunSyncOutcome.UnknownErr
                }
            }
        } catch (e: Throwable) {
            // 兜底：理论上 Data 层已统一走 JGitExceptionSanitizer；若仍有漏网之鱼，强制再过 sanitizer
            // （对齐 R8：UI/日志前的异常必须走显式分派 + sanitizer 兜底）
            val sanitized = jgitExceptionSanitizer.sanitize(e)
            syncLogRepo.finishLog(logId, SyncResult.ABORTED, clock.now(), errorMsg = sanitized.message)
            if (syncLogRepo.recentConsecutiveFailures(binding.id) >= 3) {
                pauseAndNotify(binding.id, SyncState.BROKEN); notifier.publishNetworkBroken(binding.id)
            } else {
                syncLogRepo.updateSyncState(binding.id, SyncState.IDLE)
            }
            RunSyncOutcome.UnknownErr
        }
    }

    companion object {
        val QUIET_WINDOW: Duration = Duration.ofMinutes(2)
    }
}
```

> **注**：上述兜底分支中的 `jgitExceptionSanitizer` 为 `RunSyncUseCase` 额外注入的 `JGitExceptionSanitizer` 实例，供"非 Data 层抛出的异常"兜底脱敏用（理论上极少触发）。正常路径的异常在 `JGitDataSource.*.mapException(sanitizer)` 处已完成脱敏。

**幂等性保证**：
- Worker 重试由 WM 触发时，`RunSyncUseCase` 每次独立执行。
- 已 commit 未 push 的场景下，第 6 步 `commitAllIfDirty` 返回 `null`（工作区已 clean），第 7 步 push 自动重推该 commit —— 不会产生重复 commit。

**事务边界**：
- `startLog` 与 `finishLog` **不在同一事务**（中间跨多秒 Git I/O）。
- **`updateSyncState` + `finishLog` 必须同事务**（Room `withTransaction { }`），避免"状态仍 RUNNING 而 log 已 finish"的不一致。
- `pauseAndNotify` 内部将"改 syncState + 写 finishLog" 合并为一个 `withTransaction`。

### 4.4 Runtime 层：`GitSyncWorker` + `SyncScheduler`

```kotlin
@HiltWorker
class GitSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runSync: RunSyncUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trigger = runCatching {
            SyncTrigger.valueOf(inputData.getString(KEY_TRIGGER) ?: SyncTrigger.PERIODIC.name)
        }.getOrDefault(SyncTrigger.PERIODIC)
        when (runSync(trigger)) {
            RunSyncOutcome.Ok,
            RunSyncOutcome.SkippedDebounce,
            is RunSyncOutcome.SkippedPaused,
            RunSyncOutcome.NoBinding -> Result.success()
            RunSyncOutcome.NetworkErr -> Result.retry()                    // 走 backoff
            RunSyncOutcome.PausedFs,
            RunSyncOutcome.PausedAuth,
            is RunSyncOutcome.PausedConflict,
            RunSyncOutcome.MissingCredential,
            RunSyncOutcome.UnknownErr -> Result.success()                  // 已自写 SyncLog + 暂停；不交 WM 无限重试
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "simplygit.sync.periodic"
        const val UNIQUE_CATCHUP  = "simplygit.sync.catchup"
        const val KEY_TRIGGER = "trigger"
    }
}

class SyncSchedulerImpl @Inject constructor(private val wm: WorkManager) : SyncScheduler {
    override fun schedulePeriodic(policy: SyncPolicyModel) {
        if (policy.intervalMinutes == SyncPolicyModel.MANUAL_ONLY) {
            wm.cancelUniqueWork(GitSyncWorker.UNIQUE_PERIODIC); return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (policy.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .apply { if (policy.requireCharging) setRequiresCharging(true) }
            .build()
        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(
            policy.intervalMinutes.toLong(), TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)   // backoff 上限 WM 默认 5h
            .setInputData(workDataOf(GitSyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .build()
        wm.enqueueUniquePeriodicWork(
            GitSyncWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,                                     // 下次周期生效
            request,
        )
    }

    override fun triggerCatchUpOnce() {
        val request = OneTimeWorkRequestBuilder<GitSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(GitSyncWorker.KEY_TRIGGER to SyncTrigger.CATCHUP.name))
            .build()
        wm.enqueueUniqueWork(GitSyncWorker.UNIQUE_CATCHUP, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        wm.cancelUniqueWork(GitSyncWorker.UNIQUE_PERIODIC)
        wm.cancelUniqueWork(GitSyncWorker.UNIQUE_CATCHUP)
    }
}
```

- `ExistingPeriodicWorkPolicy.UPDATE`（WM 2.8+）：策略变更后 WM 保留当前约束至本周期结束，下次重排 → 对齐 G2 "下次周期生效，不抢占当前运行"。
- **Doze 降级策略**：周期任务**不申请 `setExpedited`**（总方案 §4.4 "配额不足时退化为普通任务" 的更保守版：直接不占用应用 expedited 配额，让系统完全按 Doze 窗口安排），避免因 expedited 配额耗尽反而被拒；catch-up 同理。
- **catch-up 触发**（`CatchUpTrigger.triggerIfStale`）：`MainActivity.onCreate` 调用；`staleThreshold = policy.intervalMinutes * 2`；只在 `lastSyncAt != null && now - lastSyncAt > threshold` 时触发。

**`SimplyGitApp` 的 `Configuration.Provider`**：

```kotlin
@HiltAndroidApp
class SimplyGitApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)                    // 不在默认 DEBUG 档位，避免 Log 过细
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)                     // 通道前置创建
    }
}
```

### 4.5 Domain 层：失败分类 → 状态机 → 通知

| 失败类型 | 触发源 | `SyncState` 转移 | 通知 |
|---|---|---|---|
| SAF 权限回收 | `safPathResolver.hasPersistedPermission == false` | → `PAUSED_FS` | 立即：`"Vault 目录授权已失效，请重新授权"` |
| HTTP 401 / PAT 过期 | `SanitizedGitException.kind == SyncErrorKind.Auth`（由 `JGitExceptionSanitizer.classifyKind` 基于 JGit `TransportException` 的 "401/403/not authorized" 关键字分类） | → `PAUSED_AUTH` | 立即：`"GitHub 凭证失效，请更新 PAT"` |
| 冲突（不可合并 4 类） | `PullOutcomeClassified.classification ∈ {TEXT_LINE / BINARY / DELETE_MODIFY / REMOTE_REWRITE}` | → `PAUSED_CONFLICT` | 立即：`"检测到同步冲突（{class}），点击查看"` |
| 网络错误 | `SanitizedGitException.kind == SyncErrorKind.Network`（`UnknownHostException` / `NoRouteToHostException` / `SocketException` / `SocketTimeoutException` / `ConnectException`） | 单次不转移；连续 ≥ 3 次 → `BROKEN` | 连续 ≥ 3 次后一次：`"连续同步失败，请检查网络"` |
| 未知异常 | `SanitizedGitException.kind == SyncErrorKind.Unknown` 或任何 `Throwable` 兜底 | 单次不转移；连续 ≥ 3 次 → `BROKEN` | 同上 |
| 磁盘空间不足 | `StatFs.availableBytes < 2 × localRepoSize` | 本次 skip，不转移 | 低优：`"磁盘空间不足，本次同步跳过"` |

**Worker Result 映射（CR P2-06 明确）**：`RunSyncUseCase` 返回 `RunSyncOutcome` 后，`GitSyncWorker` 按以下规则映射到 WM 结果：
- `NetworkErr` → `Result.retry()`：交给 WM 指数 backoff（30s → 1m → 2m … 5h 封顶）重试。
- `UnknownErr` → `Result.success()`：**不走 WM backoff**。理由：OOM / 取消 / 未知本地 IO 这类异常，WM 隔几十秒重试大概率命中同一条件；失败计数已经由 `RunSyncUseCase.finishTransient` 累加到 3-strike `BROKEN` 预算，BROKEN 的通知与 UI 路径足以让用户介入——"隐形 backoff 重试"既无收益又浪费电。
- 其余（`Ok` / `SkippedDebounce` / `SkippedPaused` / `NoBinding` / `PausedFs` / `PausedAuth` / `PausedConflict` / `MissingCredential`）→ `Result.success()`：状态机已入库，不需要 WM 再触发一次。

**通知通道**：

```kotlin
object NotificationChannels {
    const val CHANNEL_SYNC_ALERT = "simplygit.channel.sync_alert"   // IMPORTANCE_HIGH
    const val CHANNEL_SYNC_LOW   = "simplygit.channel.sync_low"     // IMPORTANCE_LOW
    fun createAll(ctx: Context) { /* NotificationManagerCompat.from(ctx).createNotificationChannel(...) */ }
}
```

- 通知 `PendingIntent` 指向 `MainActivity`，通过 `Intent.putExtra("nav", "audit" | "resume")` 在 `MainActivity.onCreate / onNewIntent` 中定位到审计页或"恢复同步"对话框。
- **A13+ 通知权限降级**（`NotificationPermissionHelper`）：首次触发通知前检查 `POST_NOTIFICATIONS`；用户拒绝后 **永久降级为首页 badge**（`HomeUiState.pendingAlertCount = repoSyncStateRepository.countInPaused()`），不再弹系统授权；审计页仍可主动查阅，确保"失败可查"底线。

### 4.6 Data 层：Room Schema（对齐总方案 §6.1）

```kotlin
@Entity(
    tableName = "repository",
    foreignKeys = [ForeignKey(
        entity = SyncPolicyEntity::class,
        parentColumns = ["id"], childColumns = ["syncPolicyId"],
        onDelete = ForeignKey.RESTRICT,                  // 策略不可被误删；N4 本迭代只有 1 条
    )],
    indices = [Index("syncPolicyId")],
)
data class RepositoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val remoteUrl: String,
    val authRef: String,                 // "github_pat"（本迭代固定）
    val localTreeUri: String,
    val localAbsPath: String?,           // 可空：SAF ResolveResult.NotPrimary / NotReadable 时未必能拿到绝对路径；Worker 场景下仅当 localAbsPath != null 才能运行同步（§4.3 binding 为 null 时 RunSyncOutcome.NoBinding）
    val defaultBranch: String,           // "main"
    val syncPolicyId: Long,              // FK → sync_policy.id
    val syncState: String,               // SyncState.name
    val lastSyncAt: Long?,
    val lastSyncResult: String?,         // SyncResult.name
    val createdAt: Long,
)

@Entity(tableName = "sync_policy")
data class SyncPolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intervalMinutes: Int,            // 15 | 30 | 60 | -1(MANUAL_ONLY)
    val requireUnmetered: Boolean,
    val requireCharging: Boolean,
    val commitMessageTemplate: String,   // 默认 "chore(sync): auto-commit at %ISO%"
)

@Entity(
    tableName = "sync_log",
    foreignKeys = [ForeignKey(
        entity = RepositoryEntity::class,
        parentColumns = ["id"], childColumns = ["repoId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("repoId"), Index("startedAt")],
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repoId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val trigger: String,                 // SyncTrigger.name
    val result: String?,                 // SyncResult.name
    val commitsPulled: Int = 0,
    val commitsPushed: Int = 0,
    val filesChanged: Int = 0,
    val conflictClass: String? = null,   // ConflictClass.name
    val errorCode: String? = null,
    val errorMsg: String? = null,        // 必须经 JGitExceptionSanitizer 脱敏
    val errorType: String? = null,       // SanitizedGitException.originalType（CR P3-02），v2 Migration 新增
)
```

**`RepoBinding ↔ RepositoryEntity` 映射规则（I-2 配套）**：

- `RepoBinding.id`（新增，Long，默认 0 表示未持久化）↔ `RepositoryEntity.id`
- `RepoBinding.treeUri`（非空）↔ `RepositoryEntity.localTreeUri`（非空）
- `RepoBinding.localAbsPath`（**保持非空**）↔ `RepositoryEntity.localAbsPath`（可空）：**只有 `localAbsPath != null` 的 `RepositoryEntity` 行才会被映射为 `RepoBinding`**；`RepoBindingRepositoryImpl.observe()` 过滤 `localAbsPath == null` 的行（视为"未完成 SAF 解析"），返回 `null`，`RunSyncUseCase.invoke()` 短路为 `RunSyncOutcome.NoBinding`。
- `RepoBinding.remoteUrl`（非空）↔ `RepositoryEntity.remoteUrl`（非空）

**清理策略**：

- `SyncLogDao.pruneExpired(cutoff: Long, maxRows: Int)` 在每次 `finishLog` 后调用（`@Transaction`）：
  ```sql
  DELETE FROM sync_log WHERE startedAt < :cutoff;
  DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY startedAt DESC LIMIT :maxRows);
  ```
  `cutoff = now - 7 days`，`maxRows = 500`。

**迁移策略（DataStore → Room，I-5 / I-8 / CR P2-01 / P2-02 修复）**：

本迭代 `@Database(version = 2)`（v1 为 Iteration 2 初始 schema；v2 为 CR P3-02 新增 `sync_log.errorType` 后的升级，Migration 在 `SimplygitDatabase.MIGRATION_1_2` 提供并在 `DatabaseModule` 显式注册）；迭代 1 的 `RepoBinding` 仅存于 DataStore Preferences（key：`vault_tree_uri` / `remote_url` / `local_abs_path`）。升级路径：

1. **首次启动 Iteration 2**，`SimplyGitApp.onCreate` 在 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 上异步执行 `RepoBindingRepository.migrateFromDataStoreIfNeeded()`（**不阻塞 UI 首帧**）。迁移接口定义在 `domain/repository/RepoBindingRepository.kt`，由 `SimplyGitApp` 构造注入。
2. 迁移内部走 `SimplygitDatabase.withTransaction { }` 原子化：
   - (a) 若 `sync_policy` 表为空，先 `INSERT` 一条默认 `SyncPolicyEntity(DEFAULT)`（§6.1），拿到 `policyId`。
   - (b) 读 DataStore 的 `vault_tree_uri` + `remote_url` + `local_abs_path`；三者缺一则跳过本次迁移（用户尚未在迭代 1 完成绑定），**不**扣除 retry 配额。
   - (c) `INSERT RepositoryEntity(syncPolicyId = policyId, localAbsPath = ..., syncState = IDLE, ...)`。
   - (d) 事务提交成功后写 DataStore key `migration_v1_done = true` 并 `resetMigrationRetry()`。
3. **迁移成功前不清 DataStore 源数据**（R-7）；**成功后下次冷启动**，`SimplyGitApp.onCreate` 重新调用 `migrateFromDataStoreIfNeeded()`，检测到 `migration_v1_done == true` + 源 key 仍存在 → 调用 `SafUriStore.clearLegacyBindingKeys()` 一次性删除三把源 key（保留 `migration_v1_done` 永久标记）。崩溃间歇的最坏情况是多做一次 no-op 清理，不会数据丢失。
4. **迁移后 `SafUriStore`（迭代 1 的 DataStore Preferences 封装）deprecate**：`RepoBindingRepositoryImpl.observe()` 切换为读 `RepositoryDao.observeFirst()`；`SafUriStore` 保留 `@Deprecated` 标记**仅作为迁移源 + 迁移 bookkeeping 存储**（`migration_v1_done` / `migration_v1_retry_count`），预计迭代 3 删除。本迭代代码库**不支持"DataStore + Room 双源并存写"**——所有新写入只落 Room。
5. **失败降级路径（CR P2-01）**：迁移事务抛异常 → 源数据保留 → `legacyStore.incrementMigrationRetry()` 把计数 +1 并 `DiagnosticsLogger.logInfo("migration_failed", "attempt=N type=...")`；下一次冷启动自动重试。连续失败达到 `RepoBindingRepository.MAX_MIGRATION_RETRIES = 3` 次后，`isMigrationDisabled()` 返回 `true`；`HomeViewModel.init` 把该值并入 `HomeUiState.Bound.migrationDisabled`，`SyncStateBanner` 切换为红色横幅"数据迁移多次失败，请重新绑定 Vault"，屏蔽常规 `syncState` Banner——用户点"选择 Vault 目录"即走正常 Iteration 2 绑定路径，产生新的 `RepositoryEntity` 后 `count > 0`，下次启动自动翻转 `migration_v1_done = true`。

### 4.7 UI 层：三屏新增 + Home 扩展

**导航（`MainActivity` NavHost）**：

- `home`（默认）
- `policy`
- `audit`
- `audit/{logId}`

**`HomeScreen` 扩展**：
- 顶部 **`SyncStateBanner`**：`IDLE`（绿，显示"下次同步：约 N 分钟后"）/ `RUNNING`（蓝，不可交互）/ `PAUSED_CONFLICT` / `PAUSED_AUTH` / `PAUSED_FS`（橙 + "恢复同步"按钮 → `ResumeFromPauseUseCase`，弹二次确认）/ `BROKEN`（红 + "查看日志"按钮 → audit **+ "恢复同步"按钮** → `ResumeFromPauseUseCase`，见 §4.5 状态机规则，CR P3-01）。当 `migrationDisabled == true`（§4.6 步骤 5）时，Banner 切换为红色横幅"数据迁移多次失败，请重新绑定 Vault"，**屏蔽常规 state Banner**。
- 右上角 **⚙ 策略** + **🕒 审计（带 badge）**。
- **迭代 1 原四操作按钮（Clone / Pull / Commit / Push）保留，语义独立于状态机**（I-7 澄清）：
  - 手动按钮调用迭代 1 既有 UseCase 链路（`PullUseCase` / `PushUseCase` 等），**不经过 `RunSyncUseCase`**，**不读写 `syncState`**；其语义是"故障诊断 / 手动验证"，与 Worker 自动链路独立。
  - 在 `PAUSED_*` 下手动按钮仍可点击，用户据此验证"凭证/网络/冲突"是否已修复。**手动成功不自动解除 `PAUSED_*`**——解除必须走 "恢复同步" 按钮触发 `ResumeFromPauseUseCase`（避免"一次手动 push 成功"误判为"状态已稳定"）。
  - 手动按钮产生的操作**不落 `SyncLog`**（`SyncLog` 只记录自动链路），避免审计数据与自动同步语义混淆。
- **零打扰约束**：Home 首帧**不触发任何 Git I/O**（仅读 Room `observeRepoState`）；catch-up 由 `MainActivity` 异步触发，不阻塞首帧。

**`SyncPolicyScreen`**：
- RadioGroup：间隔（15 / 30 / 60 min / 仅手动）
- Switch：仅 Wi-Fi / 仅充电
- TextField：commit 模板（带预览；`%ISO%` 占位）
- 保存按钮 → `UpdateSyncPolicyUseCase` → `SyncScheduler.schedulePeriodic`
- 若当前系统未授 `POST_NOTIFICATIONS` 且策略非 `MANUAL_ONLY`，顶部显示"通知已关闭，失败仅首页 badge 提示"黄条（不拦截保存）。

**`SyncAuditScreen`**：
- `LazyColumn` 加载 `observeRecent(30)`（考虑 pruning 上限 500，一次性加载 500 行可接受；**本迭代不引入 Paging**）。
- 每行：起止时间、触发来源、结果徽标（OK/冲突/认证/网络/SAF/跳过）、commits pulled+pushed、`ConflictClass`。
- 点击 → `SyncAuditDetailScreen`：完整字段 + 错误消息（已脱敏）+ **`errorType`（CR P3-02 持久化，来自 `SanitizedGitException.originalType`，展示 JGit `TransportException` / `UnknownHostException` 等原始类型名，便于工程侧排查）**。
- 底部 **"导出日志"** → `ExportLogsUseCase`：二次确认弹窗 → 打包 `SyncLog.recent(500)` JSON（含 `errorType` 字段）+ `diagnostics-YYYY-MM-DD.log`（最近 7 天的日滚动文件，详见 §4.9.1）→ 写 `filesDir/exports/simplygit-<ts>.zip` → `ACTION_SEND` + `FileProvider`。**不自动上传**（§5.2 数据不出端）。

### 4.8 依赖注入骨架（新增模块）

- **`DatabaseModule`**：`@Provides @Singleton SimplygitDatabase`（`Room.databaseBuilder(ctx, SimplygitDatabase::class.java, "simplygit.db").fallbackToDestructiveMigration(false).build()`）+ 各 Dao。
- **`WorkerModule`**：`@Provides @Singleton WorkManager`（`WorkManager.getInstance(ctx)`）；`@Binds SyncScheduler → SyncSchedulerImpl`。
- **`NotificationModule`**：`@Provides @Singleton NotificationManagerCompat`；`@Binds NotificationPublisher → NotificationPublisherImpl`。
- 迭代 1 的 `DataModule` 扩展：新增 `@Binds SyncPolicyRepository / SyncLogRepository`；`ConflictClassifier` 走 Hilt 默认构造注入（无需 `@Binds`，`internal class` 对 `GitRepositoryImpl` 包内可见）。

### 4.9 性能与资源

- **JGit `Repository` 生命周期**：`JGitDataSource` 的每个方法均 `Git.open(localDir).use { }`（迭代 1 已落地），Worker 内每次同步构造、释放一次；**禁止**跨 Worker 运行复用 `Repository` 实例，避免 pack mmap 泄漏（总方案 §4.3 对应优化点延到 Phase 3 做分档调优）。
- **Classifier 作用域**（I-3 配套）：`ConflictClassifier.classify(PullResult, Repository)` 仅在 `GitRepositoryImpl.pullAndClassify` 内部的 `Git.open(dir).use { git -> ... }` 作用域内被调用；Data 层出口只返回 `PullOutcomeClassified` 纯数据——Domain 层不持有 JGit 原生引用。CI 静态 grep 规则：`ConflictClassifier` 不应出现在 `domain/` 下任何文件。
- **CPU 档位**：`Dispatchers.IO` 承担 JGit + Room；`ConflictClassifier` 虽标注 "CPU 密集" 但实际开销很低（仅读 `MergeResult` 字段 + 冲突路径前 8KB），不单独切 `Default`。
- **启动**：`SimplyGitApp.onCreate` 只做通道创建 + `HiltWorkerFactory` 就绪 + 异步触发 `migrateFromDataStoreIfNeeded()`；**JGit 类延到 Worker 或手动按钮第一次调用**（迭代 1 已满足）。
- **Worker 端到端预算**：见 NF2（10 MB 仓库无冲突 < 15s，内存峰值 < 150 MB）；不再单独设"200ms 冷启动预算"——冷启动耗时包含在端到端里，NF2 已覆盖（I-10 精简）。

### 4.9.1 `DiagnosticsLogger` 按日滚动升级（I-4 修复）

**背景**：迭代 1 的 `DiagnosticsLogger` 是单文件 + 256 KB 一次性 `writeText("")` 截断，**无时间轴语义**。§4.7 / A9b 要求"导出最近 7 天日志"——单文件截断模式下该验收不可执行。

**升级设计（本迭代落地）**：

```kotlin
@Singleton
class DiagnosticsLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: Clock,
) {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    // 新文件名：logs/diagnostics-YYYY-MM-DD.log

    suspend fun logGitOpFailure(op: String, cause: Throwable) { append("GIT_OP_FAILURE op=$op ...") }
    suspend fun logInfo(tag: String, message: String)          { append("INFO tag=$tag msg=$message") }

    private suspend fun append(line: String) = withContext(io) {
        runCatching {
            val dir = File(context.filesDir, LOG_DIR).apply { if (!exists()) mkdirs() }
            val today = dateFmt.format(Date(clock.now().toEpochMilli()))
            val file = File(dir, "diagnostics-$today.log")
            // 单日文件单独 cap 64KB；超出才截断当日文件，不影响历史文件
            if (file.length() > DAY_MAX_BYTES) file.writeText("")
            FileWriter(file, /* append = */ true).use { w -> /* 同迭代 1 */ }
            pruneOldLogs(dir)
        }
    }

    /** 删除 7 天前的 diagnostics-*.log。每次 append 时触发（成本 O(dir 列表 + 时间比较)）。 */
    private fun pruneOldLogs(dir: File) {
        val cutoffMillis = clock.now().minus(Duration.ofDays(7)).toEpochMilli()
        dir.listFiles { f -> f.name.startsWith("diagnostics-") && f.name.endsWith(".log") }
            ?.filter { it.lastModified() < cutoffMillis }
            ?.forEach { it.delete() }
    }

    suspend fun snapshotRecentLogFiles(): List<File> = withContext(io) {
        val dir = File(context.filesDir, LOG_DIR)
        val cutoffMillis = clock.now().minus(Duration.ofDays(7)).toEpochMilli()
        dir.listFiles { f -> f.name.startsWith("diagnostics-") && f.name.endsWith(".log") }
            ?.filter { it.lastModified() >= cutoffMillis }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private companion object {
        const val LOG_DIR = "logs"
        const val DAY_MAX_BYTES: Long = 64L * 1024L
    }
}
```

- **兼容迭代 1 日志**：升级首日运行时，旧文件 `diagnostics.log`（单文件）若存在 → `append` 先重命名为 `diagnostics-<首日日期>.log`（一次性迁移，见 `pruneOldLogs` 前的 `migrateLegacyLogIfNeeded()`，实现 5 行代码）。
- **`ExportLogsUseCase`**：从 `snapshotRecentLogFiles()` 取最多 7 个文件打包进 ZIP，取代原"单个 diagnostics.log"。
- **验收（配套 A9b 更新）**：`filesDir/logs/` 下至多 7 个 `diagnostics-YYYY-MM-DD.log`；ZIP 内包含 `sync_log.json` + `diagnostics-*.log`（≤ 7 文件）。

## 5. 信息架构与交互

### 5.1 UI 结构

```
MainActivity (NavHost)
 ├─ home → HomeScreen
 │         ├─ SyncStateBanner（IDLE / RUNNING / PAUSED_* / BROKEN）
 │         ├─ 凭证 / 目录 / 远程 / 操作四区（沿用迭代 1）
 │         └─ TopBar: [⚙ policy] [🕒 audit + badge]
 ├─ policy → SyncPolicyScreen
 └─ audit  → SyncAuditScreen
             └─ SyncAuditDetailScreen
```

### 5.2 用户流程

**策略首配**：
```
进入 Home（已绑定 via 迭代 1）
 → banner=IDLE，顶部提示"尚未配置自动同步"
 → 点 ⚙ → SyncPolicyScreen
 → 选 15min + 仅 Wi-Fi → 保存
 → POST_NOTIFICATIONS 权限申请（A13+）→ 授予/拒绝
 → 回 Home，banner="下次同步：约 15 min 后"
```

**后台静默同步**（零打扰）：
```
APP 退后台 → 15 min 后 Worker 自动运行
 → SAF 自检 OK → debounce 检测：过去 2 min 无变更 → 继续
 → pull FF → commit（无变更跳过）→ push → SyncLog.OK
 → 用户完全无感
```

**冲突介入**：
```
Worker → pull → ConflictClassifier = TEXT_LINE_CONFLICT
 → SyncState = PAUSED_CONFLICT + NotificationPublisher.publishConflict
 → 用户点通知 → MainActivity deep link → banner = PAUSED_CONFLICT（橙）
 → 用户到桌面端解决冲突并 push
 → 回 APP 点"恢复同步" → ResumeFromPauseUseCase → IDLE
 → 下个周期正常
```

**网络熔断**：
```
3 次连续 SanitizedGitException(kind = Network)
 → 第 3 次 SyncLog.NETWORK_ERR + SyncState = BROKEN + 一次性通知
 → WM exponential backoff（30s → 1m → 2m ... 5h 封顶）继续尝试
 → 任一成功自动解除 BROKEN → IDLE
```

**冷启动 catch-up**：
```
用户上次同步距今 > 30 min（interval=15）→ CatchUpTrigger.triggerIfStale
 → enqueueUniqueWork(UNIQUE_CATCHUP, KEEP)
 → Worker 立即补偿一次（受 CONNECTED 约束）
```


## 6. 技术实现

### 6.1 数据模型

**完整存储面清单（迭代 1 + 迭代 2）**：

| 存储 | 技术 | Key / Entity | 类型 | 敏感 |
|------|------|--------------|------|------|
| `encrypted_prefs.xml` | ESP | `github_pat` / `github_username` / `github_email` | String（`pat` 由 ESP 加密） | `pat` 敏感 |
| `repo.preferences_pb` | DataStore Preferences | `vault_tree_uri` / `remote_url` + `migration_v1_done` 标记 | String / Bool | 否（迁移完成后清空业务 key） |
| `simplygit.db` | Room | `RepositoryEntity` / `SyncPolicyEntity` / `SyncLogEntity` | 结构化 | 否（`errorMsg` 已脱敏） |
| `filesDir/logs/diagnostics-YYYY-MM-DD.log` | 文件 | 按日滚动日志（最多 7 个；单日 ≤ 64 KB） | 文本 | 否（`JGitExceptionSanitizer` 拦截） |
| `filesDir/exports/simplygit-<ts>.zip` | 文件 | 用户显式导出（含最近 7 个日滚动文件 + sync_log.json） | 二进制 | 用户授权后可共享 |

**Domain 模型（新增）**：

```kotlin
data class SyncPolicyModel(
    val intervalMinutes: Int,
    val requireUnmetered: Boolean,
    val requireCharging: Boolean,
    val commitMessageTemplate: String,
) {
    companion object {
        const val MANUAL_ONLY = -1
        val DEFAULT = SyncPolicyModel(15, false, false, "chore(sync): auto-commit at %ISO%")
    }
}

data class SyncLogModel(
    val id: Long,
    val repoId: Long,
    val startedAt: Instant,
    val endedAt: Instant?,
    val trigger: SyncTrigger,
    val result: SyncResult?,
    val commitsPulled: Int,
    val commitsPushed: Int,
    val filesChanged: Int,
    val conflictClass: ConflictClass?,
    val errorMsg: String?,
    val errorType: String? = null,   // SanitizedGitException.originalType（CR P3-02）
)

enum class SyncResult {
    OK, CONFLICT, NETWORK_ERR, AUTH_ERR, FS_ERR, ABORTED, SKIPPED_DEBOUNCE, SKIPPED_PAUSED
}

sealed interface RunSyncOutcome {
    data object Ok : RunSyncOutcome
    data object SkippedDebounce : RunSyncOutcome
    data class SkippedPaused(val state: SyncState) : RunSyncOutcome
    data object NoBinding : RunSyncOutcome
    data object MissingCredential : RunSyncOutcome
    data object NetworkErr : RunSyncOutcome
    data object PausedFs : RunSyncOutcome
    data object PausedAuth : RunSyncOutcome
    data class PausedConflict(val classification: ConflictClass) : RunSyncOutcome
    data object UnknownErr : RunSyncOutcome
}

data class RepositoryStateSnapshot(
    val repoId: Long,
    val syncState: SyncState,
    val lastSyncAt: Instant?,
    val lastSyncResult: SyncResult?,
)

/**
 * Data 层分类后返回的纯数据 DTO（I-3 修复）。
 * 不含任何 JGit 原生引用（Repository / PullResult / MergeResult），
 * 由 GitRepositoryImpl 在 Git.open(dir).use{} 作用域内完成分类后产出。
 */
data class PullOutcomeClassified(
    val classification: ConflictClass,
    val commitsPulled: Int,
    val conflictPaths: List<String>,     // 供审计日志 / 后续冲突 UI 使用
    val mergeStatusName: String?,        // JGit MergeStatus.name()，便于诊断（非 enum 引用）
)

data class CommitOutcome(val objectId: String, val filesChanged: Int)
```

**禁用项**（延续迭代 1 约束 + 本迭代新增）：
- **禁止** `SyncLogEntity.errorMsg` 直接写原始异常 message，必须先过 `JGitExceptionSanitizer`（R3 迭代扩展）。
- **禁止** 在 Worker / UseCase 内 `Log.*(pat)` / `Log.*(Credential)`（迭代 1 已禁，本迭代 CI 静态 grep 复用）。
- **禁止** `SyncPolicyEntity` 持有任何引用凭证的字段（`authRef` 在 `RepositoryEntity` 独立存储）。
- **禁止** UI 直接读 `SyncLogDao`（必须经 `SyncLogRepository` + `SyncLogModel`，避免 Room 实体泄漏到 Compose）。

### 6.2 接口定义

> **接口演化原则（黄金法则 R9 对齐）**：本节每个新增/修改接口均显式标注"新增 / 替换 / 并存"，且删减参数有对应新来源、新抛异常类型在代码中存在。

#### 6.2.1 迭代 1 接口扩展

```kotlin
// === RepoBindingRepository（迭代 1）新增 3 个方法 ===
interface RepoBindingRepository {
    fun observe(): Flow<RepoBinding?>                          // 迭代 1 已有
    suspend fun requireCurrent(): RepoBinding                  // 迭代 1 已有（无 binding 时抛 IllegalStateException）
    suspend fun saveVault(treeUri: String, absPath: String)    // 迭代 1 已有
    suspend fun saveRemote(url: String)                        // 迭代 1 已有
    suspend fun clear()                                        // 迭代 1 已有

    /**
     * 【新增，I-2 P0 修复】返回当前绑定；无 binding 或仍未完成 SAF 路径解析时返回 null。
     * 语义等价于 `observe().first()`，但避免 Worker/UseCase 引入 Flow 订阅开销。
     * 实现要点：读 RepositoryDao.findFirst()，若 localAbsPath == null 返回 null（§4.6 映射规则）。
     */
    suspend fun currentOrNull(): RepoBinding?

    /**
     * 【新增，CR P2-01 修复】由 `SimplyGitApp.onCreate` 在应用级 `SupervisorJob` 作用域内
     * 异步调用；幂等，`migration_v1_done == true` 或 `repository` 表已有行时直接返回。
     * 迁移事务失败 → 递增 `migration_v1_retry_count`，达到 [MAX_MIGRATION_RETRIES] = 3
     * 后 [isMigrationDisabled] 翻转为 true。
     */
    suspend fun migrateFromDataStoreIfNeeded()

    /** 【新增，CR P2-01 修复】连续 3 次迁移失败后返回 true；驱动 Home 重绑 Banner。 */
    suspend fun isMigrationDisabled(): Boolean

    companion object {
        const val MAX_MIGRATION_RETRIES: Int = 3
    }
}

// === CredentialRepository（迭代 1）新增一个方法 ===
data class CredentialIdentity(val username: String, val email: String)

interface CredentialRepository {
    fun observe(): Flow<CredentialPublicView?>                                  // 迭代 1 已有
    suspend fun save(username: String, email: String, pat: CharArray)           // 迭代 1 已有
    suspend fun loadPatOnce(): CharArray?                                       // 迭代 1 已有
    suspend fun clear()                                                         // 迭代 1 已有

    /**
     * 【新增，I-2 P0 修复】返回 identity 快照（username + email）。
     * 语义等价于 `observe().first()?.let { CredentialIdentity(it.username, it.email) }`，
     * 但以 suspend 一次性读取，避免 Worker 订阅 Flow。
     * 实现要点：包装 CredentialDataSource 的非流式读取 API；不返回 pat。
     */
    suspend fun snapshotIdentity(): CredentialIdentity?
}

// === GitRepository（迭代 1）扩展 2 个新方法（与原方法并存）===
interface GitRepository {
    // --- 迭代 1 原有方法保留（供 Home 手动按钮使用，I-7 澄清）---
    suspend fun clone(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun pull(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun commitAll(
        binding: RepoBinding, message: String,
        authorName: String, authorEmail: String,
    ): GitOpResult
    suspend fun push(binding: RepoBinding, username: String, pat: CharArray): GitOpResult

    /**
     * 【新增，I-2 + I-3 P0/P1 修复】pull 并在 Data 层内部完成冲突分类。
     * 内部 `Git.open(dir).use { git -> git.pull()...call(); classifier.classify(raw, git.repository) }`
     * 使 ConflictClassifier 不透出到 Domain 层。
     * 失败走 JGitDataSource.mapException(sanitizer)，抛出 SanitizedGitException（含 kind）。
     */
    suspend fun pullAndClassify(
        binding: RepoBinding,
        username: String,
        pat: CharArray,
    ): PullOutcomeClassified

    /**
     * 【新增，I-2 P0 修复】若工作区 dirty 则 commit 并返回 CommitOutcome，clean 则返回 null。
     * 与迭代 1 `commitAll`（dirty 为空时抛 NoChangesException）的差异是"空 commit 静默跳过"，
     * 供自动同步的幂等重试使用；手动按钮保持走 commitAll。
     */
    suspend fun commitAllIfDirty(
        binding: RepoBinding,
        message: String,
        authorName: String,
        authorEmail: String,
    ): CommitOutcome?
}
```

**关键澄清**：
- `pullAndClassify` / `commitAllIfDirty` **不替换** `pull` / `commitAll`，两者并存：迭代 1 Home 手动按钮继续走 `pull` / `commitAll`（抛异常供 UI 展示），`RunSyncUseCase` 走 `pullAndClassify` / `commitAllIfDirty`（返回 `PullOutcomeClassified` 供状态机分派）。
- `pullAndClassify` 的 `username` 来源：`RunSyncUseCase` 通过 `CredentialRepository.snapshotIdentity()?.username` 获取（见 §4.3 第 4 步）。
- `commitAllIfDirty` 的 `authorName` / `authorEmail` 来源：同上，`snapshotIdentity()?.email`。

#### 6.2.2 本迭代新增接口

```kotlin
interface SyncPolicyRepository {
    fun observe(): Flow<SyncPolicyModel>
    suspend fun current(): SyncPolicyModel
    suspend fun update(policy: SyncPolicyModel)
}

interface SyncLogRepository {
    fun observeRecent(limit: Int = 30): Flow<List<SyncLogModel>>

    /**
     * N4 单仓约束下（§2.4 / §4.6），实现 **可忽略** [repoId]，直接读 `repository`
     * 表首行（§4.6 Iteration 2，CR P2-05）；参数保留供 Phase 3+ 多仓扩展直接升级。
     */
    fun observeRepoState(repoId: Long): Flow<RepositoryStateSnapshot>
    suspend fun loadById(id: Long): SyncLogModel?
    suspend fun loadRepoState(repoId: Long): RepositoryStateSnapshot
    suspend fun startLog(repoId: Long, trigger: SyncTrigger, now: Instant): Long
    suspend fun finishLog(
        logId: Long,
        result: SyncResult,
        endedAt: Instant,
        commitsPulled: Int = 0,
        commitsPushed: Int = 0,
        filesChanged: Int = 0,
        conflictClass: ConflictClass? = null,
        errorMsg: String? = null,
        errorType: String? = null,         // CR P3-02：原始 Throwable 类型名
    )
    suspend fun updateSyncState(repoId: Long, state: SyncState)
    /**
     * CR P3-02：`pauseAndFinish` 在实际实现中合并 `updateSyncState + finishLog` 为
     * 单一 `withTransaction`（见 §4.3 事务边界注释）；同样接受 `errorType`。
     */
    suspend fun pauseAndFinish(
        repoId: Long,
        logId: Long,
        state: SyncState,
        result: SyncResult,
        endedAt: Instant,
        conflictClass: ConflictClass? = null,
        errorMsg: String? = null,
        errorType: String? = null,
    )
    suspend fun recentConsecutiveFailures(repoId: Long): Int
    suspend fun pruneExpired(now: Instant)
    suspend fun loadRecentForExport(limit: Int = 500): List<SyncLogModel>
}

interface SyncScheduler {
    fun schedulePeriodic(policy: SyncPolicyModel)
    fun triggerCatchUpOnce()
    fun cancelAll()
}

interface NotificationPublisher {
    fun publishConflict(repoId: Long, kind: ConflictClass)
    fun publishAuthFailed(repoId: Long)
    fun publishFsPermissionLost(repoId: Long)
    fun publishNetworkBroken(repoId: Long)
    fun publishLowPriority(msg: String)
}
```

#### 6.2.3 架构边界声明（I-3 P1 修复）

- **Domain 层不引用任何 JGit 原生类型**：`GitRepository` 接口、`RunSyncUseCase` / `ResumeFromPauseUseCase` / `UpdateSyncPolicyUseCase` / `LoadSyncLogUseCase` / `ExportLogsUseCase` 及 `domain/model/*.kt` 均禁止 `import org.eclipse.jgit.*`（CI 静态 grep 规则）。
- **`ConflictClassifier` 包可见性**：`internal class`，包位置 `data/git/`，仅 `GitRepositoryImpl` / 同包单测可见；构造注入走 Hilt `@Inject` 默认。
- **异常通道统一**：所有 Data 层抛出到 Domain 的异常均为 `SanitizedGitException`（`kind` 字段携带 Auth / Network / Unknown 分类）；Domain 层禁止 `catch (e: TransportException)` / `catch (e: IOException)` 等 Data 层原生异常类型。

### 6.3 跨层字段传递矩阵

| 字段 | UI (Compose) | ViewModel | UseCase | Repository | JGit / SAF / WM |
|------|-------------|-----------|---------|-----------|-----------------|
| `intervalMinutes` | Radio 选中值 | `ChangeInterval(n)` 写 VM State | `UpdateSyncPolicyUseCase` → `SyncPolicyRepository.update` + `SyncScheduler.schedulePeriodic` | `SyncPolicyEntity.intervalMinutes` | `PeriodicWorkRequestBuilder(interval, MINUTES)` |
| `requireUnmetered` | Switch | 同上 | 同上 | `SyncPolicyEntity.requireUnmetered` | `Constraints.setRequiredNetworkType(UNMETERED/CONNECTED)` |
| `requireCharging` | Switch | 同上 | 同上 | `SyncPolicyEntity.requireCharging` | `Constraints.setRequiresCharging(true)` |
| `commitMessageTemplate` | TextField + 预览 | `ChangeTemplate(s)` | 同上 | `SyncPolicyEntity.commitMessageTemplate` | 运行时 `replace("%ISO%", now)` → `git.commit().setMessage()` |
| `syncState` | `SyncStateBanner` + "恢复同步"按钮显隐 | `StateFlow<SyncState>` 来自 `SyncLogRepository.observeRepoState` | `RunSyncUseCase` 写 / `ResumeFromPauseUseCase` 写 | `RepositoryEntity.syncState`（Room 事务） | — |
| `conflictClass` | 详情页展示 + 通知文案 | Audit Detail VM 直读 `SyncLogModel.conflictClass` | `RunSyncUseCase` 读 `PullOutcomeClassified.classification` 并写入 log | `SyncLogEntity.conflictClass` | `ConflictClassifier.classify` 在 `GitRepositoryImpl.pullAndClassify` 的 `Git.use{}` 作用域内执行（I-3：Domain 层不持 JGit 引用） |
| `trigger` | Audit 行展示（周期/手动/补偿） | 从 `SyncLogModel.trigger` | `RunSyncUseCase` 入参 | `SyncLogEntity.trigger` | `WorkerParameters.inputData.getString(KEY_TRIGGER)` |
| `errorMsg` | Audit Detail 展示（脱敏） | `SyncLogModel.errorMsg` 直读 | `RunSyncUseCase` catch `SanitizedGitException` → 写入 `e.message`（已脱敏） | `SyncLogEntity.errorMsg` | `JGitExceptionSanitizer`（迭代 1 已有，本迭代扩展 `kind`） |
| `errorKind` | — | — | `RunSyncUseCase` 按 `when (e.kind)` 分派到 `PAUSED_AUTH` / 网络熔断 / Unknown 路径 | — | `SanitizedGitException.kind: SyncErrorKind`（I-1 新增） |
| `lastSyncAt` | Home banner "X 分钟前" | Home VM 相对时间计算 | `RunSyncUseCase` 成功后写；`CatchUpTrigger` 读 | `RepositoryEntity.lastSyncAt` | — |
| `pat` | 迭代 1 凭证输入页 | 不持有 | `RunSyncUseCase`：`loadPatOnce()` → try/finally `fill('\u0000')` | `CredentialRepository.loadPatOnce` | `UsernamePasswordCredentialsProvider(username, pat)` |
| `username / email` | 迭代 1 凭证输入页；Audit 展示 commit 作者 | 不持有 | `RunSyncUseCase`：`credRepo.snapshotIdentity()` 一次性取 | `CredentialRepository.snapshotIdentity`（I-2 新增） → ESP `github_username` / `github_email` | `UsernamePasswordCredentialsProvider(username, pat)` / `PersonIdent(name, email)` |
| `pendingAlertCount` | 首页 badge 数字 | `combine(repoStates, notificationGranted)` | — | 查询 `repository.syncState in PAUSED_*` | `NotificationManagerCompat.areNotificationsEnabled` |
| `deep link nav` | — | `MainActivity.onNewIntent` 读 `Intent.getStringExtra("nav")` 后 `navController.navigate` | — | — | `PendingIntent + Intent.putExtra("nav", "audit")` |
| `logs zip path` | "导出日志"按钮触发 `ACTION_SEND` | `ExportLogsUseCase.invoke()` 返回 `Uri`；VM 发 `ExportReady(uri)` | 打包 `SyncLog.recent(500)` + `DiagnosticsLogger.snapshotRecentLogFiles()`（最近 7 个 `diagnostics-YYYY-MM-DD.log`）→ ZIP | `SyncLogRepository.loadRecentForExport` / `DiagnosticsLogger.snapshotRecentLogFiles` | `FileProvider.getUriForFile(ctx, "${applicationId}.fileprovider", file)` |

**边界过滤点（G2 / G7 关键）**：
- `pat` 边界与迭代 1 一致（绝不进 `HomeUiState` / 日志 / `Intent.Extra` / `SyncLogEntity`）；本迭代 **`SyncLogEntity.errorMsg` 必须过 sanitizer**，检测到 `ghp_` / `github_pat_` 残留视为 R3 违规。
- `syncState` UI 层仅决定 banner 颜色与按钮显隐；**禁止** UI 直接调用 `SyncLogRepository.updateSyncState`，必须走 `ResumeFromPauseUseCase`（避免跳过状态机规则）。
- `intervalMinutes` 从 UI 到 WM 必须经 `SyncScheduler`；**禁止** ViewModel / UseCase 直接 new `PeriodicWorkRequest`（保证 Backoff / Constraints / UNIQUE_NAME 一致）。
- **JGit 原生类型边界（I-3 P1 修复）**：`PullResult` / `MergeResult` / `Repository` / `ObjectId` 等 JGit 原生引用**仅允许存在于 `data/git/` 包内**；`GitRepositoryImpl.pullAndClassify` 的返回值 `PullOutcomeClassified` 为纯数据 DTO，不持有任何原生引用。CI 静态规则：`grep -rE "import org\.eclipse\.jgit" app/src/main/java/com/example/simplygit/domain/ app/src/main/java/com/example/simplygit/ui/` 应零命中。
- **异常类型边界（I-1 P0 修复）**：Domain/UI 层 `catch` 的异常类型仅允许 `SanitizedGitException` 或 Kotlin 标准异常（`IllegalStateException` 等）；禁止 `catch (e: TransportException)` / `catch (e: IOException)`。错误路径分派统一通过 `when (sanitized.kind)` 进行。

## 7. 验收标准

### 7.1 功能验收

1. **A1 周期调度（G1）**：完成策略配置后 APP 退后台，`adb shell cmd jobscheduler get-job-state <pkg> <jobid>` 可见 `scheduled`；Gradle `connectedDebugAndroidTest` 用 `WorkManagerTestInitHelper + TestDriver.setPeriodDelayMet` 单测覆盖"周期 + 约束满足 → Worker.run → SyncLog 落一条"路径；结果 `SyncLog.result ∈ {OK, SKIPPED_DEBOUNCE}`。
2. **A2 策略生效（G2）**：
   - (a) 选"仅 Wi-Fi"后断 Wi-Fi 仅开蜂窝，`TestDriver` 触发周期后 **Worker 不执行**（`observeRecent` 30s 内无新记录）；恢复 Wi-Fi 下个周期执行。
   - (b) 选"仅充电"后模拟拔电（`BatteryManager.ACTION_POWER_DISCONNECTED`），Worker 不执行；插电后执行。
   - (c) 将 15 min 改为 60 min 后，**当前已排期的周期按原 15 min 继续一次**（`ExistingPeriodicWorkPolicy.UPDATE` 语义），下次周期按 60 min（通过 `WorkManager.getWorkInfosForUniqueWork(UNIQUE_PERIODIC)` 查 `periodStartTime` 验证）。
3. **A3 防抖 + 空 commit（G3）**：
   - (a) 手动修改 Vault 文件后立即触发 Worker，`SyncLog.result = SKIPPED_DEBOUNCE`。
   - (b) 2 min 后再触发：若有新变更则 `commitsPushed = 1`；无变更则 `commitsPushed = 0` 且本地 `git log` 头部 commit hash 不变。
   - (c) 连续 10 次保存 Vault 内同一文件，**等待 2 min 后一次同步**，`git log --oneline -5` 仅产出 1 个新 commit。
4. **A4 冲突分类（G4）**：使用 JGit 测试仓库（src 提供 mock），构造以下场景并触发 Worker，`SyncLog.conflictClass` 与 `SyncState` 正确：
   - 同文件同行冲突 → `TEXT_LINE_CONFLICT` / `PAUSED_CONFLICT`
   - 二进制文件冲突（PNG 覆盖）→ `BINARY_CONFLICT` / `PAUSED_CONFLICT`
   - 一端删一端改 → `DELETE_MODIFY` / `PAUSED_CONFLICT`
   - 远端 force-push 改写 → `REMOTE_REWRITE` / `PAUSED_CONFLICT`（且**不发生** reset）
   - 可合并场景（不同文件）→ `AUTO_MERGED` / 成功；快进 → `FAST_FORWARD` / 成功。
5. **A5 审计表（G5）**：
   - (a) 完成 10 次周期后，`SyncAuditScreen` 顶部 30 行按 `startedAt DESC` 排序，各字段与 `git log` / 实际执行一致。
   - (b) 注入 600 条旧记录（`startedAt = now - 1min`）后运行一次 Worker，`pruneExpired` 后 `sync_log` 行数 ≤ 500。
   - (c) 注入 10 条 `startedAt = now - 8day` 的记录，运行一次后这 10 条全部被删除。
6. **A6 通知分级（G6）**：
   - (a) 触发冲突场景，`adb shell dumpsys notification` 出现高优通知，`Channel = simplygit.channel.sync_alert`，文案含"冲突"。点击通知 → 跳转 `HomeScreen` 且 banner = `PAUSED_CONFLICT`。
   - (b) 触发 401 场景，通知文案"GitHub 凭证失效"；banner = `PAUSED_AUTH`。
   - (c) 连续 2 次离线触发**不弹通知**；第 3 次触发后弹一次网络通知；随后再失败不再重复通知（同一 `syncState=BROKEN` 内去重）。
   - (d) Android 13+ 拒绝 `POST_NOTIFICATIONS`：冲突后不弹系统通知，但 `HomeScreen` 右上角 🕒 按钮出现红点 badge，badge 数字 = 处于 `PAUSED_*` 的仓库数。
7. **A7 状态机（G7）**：
   - (a) `PAUSED_CONFLICT` 状态下 Worker 下一周期启动即返回 `Result.success()` 且产生 `SKIPPED_PAUSED` 记录。
   - (b) 用户点"恢复同步" → 弹二次确认 → 确认后 `SyncState = IDLE`，下一周期正常执行。
   - (c) 不同状态的 banner 颜色（绿/蓝/橙/红）与文案与 §4.7 定义一致。
8. **A8 catch-up（G8）**：模拟 `lastSyncAt = now - 45 min` + `intervalMinutes = 15`（阈值 30 min），冷启动 APP 后 30s 内 `UNIQUE_CATCHUP` 任务被 enqueue；执行结果为 `OK` 或对应失败分类。
9. **A9 日志导出（G9）**：
   - (a) 点"导出日志" → 弹二次确认窗展示"将包含仓库名、文件路径（不含文件内容）"；取消则不落文件。
   - (b) 确认后 `filesDir/exports/` 出现 `simplygit-*.zip`；解压内含 `sync_log.json`（最近 500 条）+ 最多 7 个 `diagnostics-YYYY-MM-DD.log`（由 `DiagnosticsLogger.snapshotRecentLogFiles()` 过滤 7 天内的日滚动文件）。
   - (c) `ACTION_SEND` 分享面板弹出且 URI 为 `content://${applicationId}.fileprovider/...`；外部 APP（如邮件）能读取；关闭分享后 URI 权限回收。
   - (d) ZIP 内 `sync_log.json` 的 `errorMsg` 字段 `grep -E "ghp_|github_pat_|Authorization:"` 无命中。
   - (e) `filesDir/logs/` 下无 `diagnostics-{早于 7 天}.log` 遗留；迭代 1 的单文件 `diagnostics.log`（若存在）已被一次性迁移为 `diagnostics-<首日>.log`。
10. **A10 NFR（G10）**：
    - (a) 真机一周采样（Battery Historian）：日均 SimplyGit 耗电 ≤ 2%（默认 15 min 周期 + 仅 Wi-Fi + 非充电约束 + Vault 平均每日 5 次修改）。
    - (b) 无冲突周期 100 次：`SyncLog.result = OK` 占比 ≥ 98%（允许偶发网络失败）。
    - (c) 冲突通知触达率：强制触发冲突 10 次（`POST_NOTIFICATIONS` 已授予），`NotificationManagerCompat.activeNotifications` 每次均包含对应通知（100%）。
11. **A11 异常分派与架构边界（I-1 / I-2 / I-3 配套）**：
    - (a) 单测 `JGitExceptionSanitizerTest`：构造 `TransportException("not authorized")` → `sanitize().kind == Auth`；构造 `UnknownHostException` → `kind == Network`；构造 `OutOfMemoryError` → `kind == Unknown`；均覆盖 cause 链深度 ≥ 2 的场景。
    - (b) 单测 `RunSyncUseCaseTest`：mock `GitRepository.pullAndClassify` 抛 `SanitizedGitException(kind = Auth)` → `SyncState` 置 `PAUSED_AUTH` + `publishAuthFailed` 被调用；抛 `kind = Network` 且 `recentConsecutiveFailures < 3` → `SyncState` 保持 `IDLE` + 无通知；`recentConsecutiveFailures == 3` → `BROKEN` + 一次性通知。
    - (c) 单测 `RepoBindingRepositoryImplTest.currentOrNull_returnsNullWhenLocalAbsPathMissing`：插入 `RepositoryEntity(localAbsPath = null)` → `currentOrNull() == null`。
    - (d) **架构边界 CI 检查**：在 `./gradlew :app:detekt` 流水线中追加 shell 步骤 `! grep -rE "import org\.eclipse\.jgit" app/src/main/java/com/example/simplygit/{domain,ui}/` 应返回零命中（若命中则 CI 失败）；并追加 `! grep -rE "catch\s*\(\s*\w+\s*:\s*(Transport|IO)Exception" app/src/main/java/com/example/simplygit/{domain,ui}/`。

### 7.2 非功能验收

1. **NF1** 冷启动到 Home 首帧 < 1.5s（Pixel 6a / Android 14 基准）；catch-up 判定与 enqueue 完全异步，不阻塞首帧。
2. **NF2** Worker 端到端（pull → commit → push，10 MB 仓库无冲突）< 15s（Wi-Fi）；单次 Worker 内存峰值 < 150 MB（`Debug.MemoryInfo`）。
3. **NF3** Release APK 体积增量（相对迭代 1）≤ 2.5 MB（WM + Room + Navigation + Hilt-Work 合计）。
4. **NF4** `./gradlew :app:lintDebug` 无 ERROR；Lint 规则新增 `WorkerHasAPublicModifier` / `ExportedReceiver` 保持 error 级；保留迭代 1 的 `HardcodedText` / `NewApi`。
5. **NF5** `./gradlew :app:detekt` 通过，baseline 在迭代 1 基础上允许新增，**不扩大 baseline** 去掩盖新代码问题。
6. **NF6** CI 脚本对 `logs/` 与 `SyncLogEntity.errorMsg` 导出 JSON 抽样扫描 `ghp_` / `github_pat_` / `Authorization:` 零命中。
7. **NF7** **耗电回归门**：若 NF2 Worker 端到端耗时超过 30s 或单 Worker CPU > 10s，视为 R-1 触发，本迭代整体回归失败，必须优化或回退至上一迭代。

## 8. 风险与缓解

1. **R-1 Doze 配额不足导致 15 min 周期被系统延长至 30~60 min**：
   - **现象**：Android 14+ App Standby Bucket 为 Rare/Restricted 时，WM 周期实际执行频率显著降低。
   - **缓解**：①不申请 expedited，避免配额耗尽；②catch-up 机制（G8）兜底"离线超过 2× interval"的补偿；③审计页展示"上次同步：N 分钟前"，用户可主观感知偏差；④Phase 3 评估是否提供"厂商电池优化白名单"引导深链（本迭代不做）。
2. **R-2 JGit 在后台线程的资源泄漏**：严格 `Git.open(dir).use { }`；`ConflictClassifier` 下沉到 Data 层 `GitRepositoryImpl.pullAndClassify` 内部，**仅在同一 `Git.use{}` 作用域内调用**（I-3），Domain 层只拿 `PullOutcomeClassified` 纯数据 DTO（无 JGit 原生引用）；CI 静态 grep `Git\.open\(.*\)[^u]` 无命中 + `import org\.eclipse\.jgit` 在 `domain/` / `ui/` 零命中（A11d）。
3. **R-3 Room 数据库迁移失败**：`@Database(version = 1)` + `fallbackToDestructiveMigration(false)`；若迭代 3 修改 schema 必须提供 `Migration`；迭代 2 DataStore→Room 一次性迁移走 `migrateFromDataStoreIfNeeded`（§4.6），迁移失败不删 DataStore 源数据。
4. **R-4 通知权限被拒后失败感知消失**：首页 badge + 审计页主动查看 + `SyncStateBanner` 在 `PAUSED_*` 下置顶横条。
5. **R-5 冲突分类误判（`FAILED` 状态语义不明）**：`detectDeleteModifyOrRemoteRewrite` 用 `RevWalk.isMergedInto` 双向判定；未知 `MergeStatus` 兜底为 `REMOTE_REWRITE`（最保守分支）；单测覆盖 `MergeStatus` 所有枚举值 + `FAILED + null fetchHead` 边缘场景（A11a / A4）。
6. **R-6 `FileProvider` authority 冲突**：authority 使用 `${applicationId}.fileprovider`；`exported=false` 仅经 `ACTION_SEND` 授权流转；集成测试验证退出分享后 URI 权限回收。
7. **R-7 DataStore → Room 迁移中 APP 崩溃导致数据丢失**：迁移在 `Room.withTransaction { }` 内原子化；`migration_v1_done = true` 写 DataStore；**迁移成功前不清源数据**，崩溃可下次启动重试；连续 3 次失败降级为"引导用户重新绑定"（§4.6）。
8. **R-8 `ConflictClassifier` 对 `MergeResult.conflicts` 可能返回 null**：`merge.conflicts?.keys.orEmpty()` 空安全；为空时默认判 `TEXT_LINE_CONFLICT`（而非 `AUTO_MERGED`），保守暂停。
9. **R-9 剪贴板 60s 自动清空（迭代 1 遗留）与 Worker 冲突**：无；Worker 不访问剪贴板。
10. **R-10 `POST_NOTIFICATIONS` 申请时机**：首次进 `SyncPolicyScreen` 点"保存"时触发——既不太早（安装后立刻弹会被直觉拒绝），也不太晚（后台同步前已经有机会授权）。
11. **R-11 `SyncErrorKind` 分类启发式误判（I-1 配套）**：`classifyKind` 依赖 JGit `TransportException` 的 message 关键字（"401" / "403" / "not authorized"）判定 Auth；JGit 未来版本若修改文案将导致 Auth → Unknown 退化。缓解：①单测锁定 JGit 6.10 的典型错误文案（A11a）；②升级 JGit 版本前在 CI 做回归；③Unknown → `BROKEN`（连续 3 次）的兜底路径保证用户仍能收到"连续失败"通知，不会完全静默。

## 9. 设计自检

> 提交评审前，逐项确认。与本迭代无关的项标注 "N/A"。
> 为避免与目标编号（G1-G10）冲突，自检项以 SC 前缀编号。

| # | 检查项 | 状态 |
|---|--------|------|
| SC1 | 生态调研已完成，确认无可直接复用的成熟方案（或已说明为何不复用） | ✅（§2.2 / §3.2：调度 WM、冲突 JGit、存储 Room，均复用） |
| SC2 | 方案对比的关键差异有量化数字 | ✅（§3.1.1 耗电 1×/1.2~3×/2~5×；§3.1.2 80 行 vs 400 行；§3.1.3 多仓扩展成本） |
| SC3 | 详细设计的每个子任务都能回溯到 §2.3 的某个目标（无冗余设计） | ✅（§4.1 目录结构注明对应模块；G1~G10 全覆盖） |
| SC4 | §2.4 非目标未在详细设计中出现（无范围蔓延） | ✅（冲突解决 UI / SSH / OkHttp Transport / FileObserver / Shallow / WindowCacheConfig / Root 检测均未出现在 §4） |
| SC5 | 新增实体的分类逻辑在数据源自描述，非消费端硬编码 | ✅（`ConflictClass` 由 JGit `MergeResult.MergeStatus` + `Repository.attributesNodeProvider` 自描述；UI 不硬编码冲突集合；classifier 下沉到 Data 层 `data/git/`，I-3 修复） |
| SC6 | 新增字段单一语义（分类字段 ≠ 展示字段） | ✅（`syncState` 状态机 / `lastSyncResult` 末次结果 / `conflictClass` 冲突分类 / `SyncErrorKind` 异常分类 相互独立） |
| SC7 | 新增用户可见功能已填写跨层字段传递矩阵（§6.3） | ✅（§6.3 覆盖 14 条字段含 5 个边界过滤点；本次评审追加 `username/email` 行 + `errorKind` 行 + JGit 原生类型边界 + 异常类型边界，I-1/I-2/I-3 修复） |
| SC8 | 验收标准覆盖"可执行性"，非仅"UI 可见" | ✅（A1 `adb shell cmd jobscheduler`；A2 `getWorkInfosForUniqueWork.periodStartTime`；A4 conflictClass 实值断言；A9 ZIP 解压 JSON grep；A10 Battery Historian 采样；A11 异常分派 + 架构边界 CI 静态 grep） |
| SC9 | 【安全】敏感凭证建模三件套（非 data class / CharArray / 禁止进 UiState/日志/toString） | ✅（迭代 1 基线 + 本迭代 §6.1 "禁用项" 扩展 `SyncLogEntity.errorMsg` 必过 sanitizer） |
| SC10 | 【安全】含凭证输入的 UI 启用 `FLAG_SECURE` | ✅（迭代 1 已覆盖 `MainActivity`） |
| SC11 | 【安全】异常 message 经脱敏层处理再进入 UI / 日志 / DB | ✅（§6.3 边界：`SyncLogEntity.errorMsg` 必经 `JGitExceptionSanitizer`；`SanitizedGitException.kind` 分派机制对齐 R8 / R9；R-8 + R-11 单测覆盖） |
| SC12 | 【工程基线】依赖四件套完整（TOML / gradle / exclude / 成对 compileOptions 开关） | ✅（§4.1.1：TOML + gradle 增量；coreLibraryDesugaring 沿用迭代 1；WM 无需 exclude） |
| SC13 | 【工程基线】AndroidManifest 改动清单独立成节 | ✅（§4.1.2：`POST_NOTIFICATIONS` + `WorkManagerInitializer` `tools:node="remove"` + `FileProvider`） |
| SC14 | 【对齐总方案】与总方案 §9 Phase 2 范围一致，偏离已显式声明 | ✅（§1 对齐声明：OkHttp Transport 推迟 / FileObserver 不做均属条件路径，非总方案变更） |
| SC15 | 【治理】技术负债（如 OkHttp Transport 推迟、FileObserver 彻底不做）已登记 | ✅（N5/N6 显式声明；首轮评审 P6 反模式已沉淀至 `docs/retro/patterns.md`：JGit 原生类型不透出 Domain） |
| SC16 | 【架构边界】Domain 层不持有任何 Data 层原生类型；异常通道统一走 sanitizer | ✅（§6.2.3 显式声明；A11d CI 静态 grep 兜底；黄金法则 R9 对齐） |
| H1-H5 | 【Harness】Prompt / Skill / Agent 相关 | N/A（非 LLM 迭代） |
| S1 | 【选型】对比表含"数据权威性"和"供应链安全"列 | ✅（§3.1.1、§3.1.2、§3.1.3 均含） |
| S2 | 【选型】已检查 MCP 注册表 / SkillHub / GitHub 是否有成熟集成 | ✅（§2.2：WM / JGit / Room / Navigation-Compose 均为 Jetpack / Eclipse 一手；无第三方集成需 MCP/Skill） |

## 10. 变更记录

| 日期 | 版本 | 变更人 | 变更说明 |
|---|---|---|---|
| 2026-05-01 | v1.0 | alexjhwen | 初版：对齐总方案 §9 Phase 2（P2.1~P2.6）落地自动化静默同步：①新增 Runtime 层 `GitSyncWorker` + `PeriodicWorkRequest`（15/30/60/MANUAL_ONLY + Constraints + Backoff）；②`SyncPolicy` 配置面 + 下周期生效；③Worker 内防抖（2min 静默期）+ 空 commit 抑制 + 幂等；④`ConflictClassifier` 纯函数映射 `MergeResult.MergeStatus` → 六类冲突；⑤Room 审计表 + 500 条/7 天滚动清理；⑥通知分级 + A13 权限拒绝降级为首页 badge；⑦`PAUSED_FS/AUTH/CONFLICT/BROKEN` 四态 + 用户"恢复同步"；⑧catch-up `OneTimeWorkRequest` 冷启动补偿；⑨日志 ZIP 导出（二次确认 + FileProvider + ACTION_SEND，不自动上传）；⑩NFR 验收：日均耗电 ≤ 2%、成功率 ≥ 98%、通知触达 100%。显式不做：冲突解决 UI / SSH / OkHttp Transport / FileObserver / Shallow Clone / WindowCacheConfig 分档（均下沉到 Phase 3+）。 |
| 2026-05-02 | v1.1 | alexjhwen | 首轮评审修订（闭环 2 P0 + 6 P1 + 2 P2，评审报告见 `docs/version/review/Iteration2_Auto_Silent_Sync_REVIEW.md`）：①I-1：异常分派改由 `SanitizedGitException.kind: SyncErrorKind { Auth / Network / Unknown }` 承载，`JGitExceptionSanitizer.classifyKind` 基于 JGit `TransportException` 关键字 + JDK 网络异常类型启发式分类；`RunSyncUseCase` catch 改为 `when (e.kind)` 分派，不再引用不存在的 `AuthFailedException` / `NetworkException`。②I-2：`RepoBindingRepository` 新增 `currentOrNull()`、`CredentialRepository` 新增 `snapshotIdentity()`、`GitRepository` 新增 `pullAndClassify` / `commitAllIfDirty`（与原 `pull` / `commitAll` **并存**，手动按钮继续走原方法）；`RunSyncUseCase` 通过 `snapshotIdentity()` 获取 username / email。③I-3 + I-9：`ConflictClassifier` 从 Domain 下沉到 `data/git/`，仅在 `Git.open(dir).use{}` 作用域内调用；`GitRepository.pullAndClassify` 返回纯数据 `PullOutcomeClassified`（无 JGit 原生引用）；新增 §6.2.3 架构边界声明 + A11d CI 静态 grep 兜底。④I-4：`DiagnosticsLogger` 升级为按日滚动 `diagnostics-YYYY-MM-DD.log`（单日 64 KB 上限 + 7 天 prune），对应 A9b/A9e 断言。⑤I-5：`RepositoryEntity.syncPolicyId` 补 `@ForeignKey(entity = SyncPolicyEntity::class, onDelete = RESTRICT)` + Index；迁移内置"先插 policy 再插 repository"顺序。⑥I-6：补 `isBinaryByAttribute` / `isBinaryByFirst8KB` / `isAncestor` 实现要点伪代码。⑦I-7：澄清 Home 四手动按钮"调用迭代 1 原 UseCase、不读写 `syncState`、不落 `SyncLog`、手动成功不自动解除 `PAUSED_*`"。⑧I-8：DataStore→Room 迁移后 `SafUriStore` 标 `@Deprecated` 仅作迁移源，新写入只落 Room；连续 3 次迁移失败降级为"引导重新绑定"。⑨I-10：删除"Worker 200ms 冷启动预算"，NF2 端到端预算已覆盖。⑩新增 A11 异常分派 + 架构边界验收；新增 R-11 `SyncErrorKind` 误判风险缓解；§9 SC15 转 ✅，新增 SC16 架构边界自检项。 |
| 2026-05-02 | v1.2 | alexjhwen | 二轮 CR 修订（1 中 + 6 低全部闭环）：①CR-P2-01：`RepoBindingRepository` 新增 `migrateFromDataStoreIfNeeded()` / `isMigrationDisabled()` 公开接口 + `MAX_MIGRATION_RETRIES = 3` 常量；`SimplyGitApp.onCreate` 显式以 `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch` 异步挂接迁移；`SafUriStore` 新增 `migration_v1_done` / `migration_v1_retry_count` 双标记 + `markMigrationDone` / `incrementMigrationRetry` / `resetMigrationRetry` / `clearLegacyBindingKeys`；连续 3 次失败翻转 `isMigrationDisabled`，`HomeUiState.Bound.migrationDisabled` 驱动 `SyncStateBanner` 切红色重绑横幅。②CR-P2-02：迁移成功后**下一轮冷启动** `SafUriStore.clearLegacyBindingKeys` 一次性删除源 key，`migration_v1_done` 永久保留（R-7 "migrate-then-clear"）。③CR-P2-03：`ConflictClassifier` 显式改 `internal class`，缩紧包可见性边界。④CR-P2-05：`SyncLogRepository.observeRepoState(repoId)` KDoc 明确 N4 单仓前提忽略入参，为 Phase 3+ 多仓升级保留 source-compatible 签名。⑤CR-P2-06：§4.5 Worker Result 映射表显式声明 `UnknownErr → Result.success()` 不走 WM backoff（避免 OOM 类异常隐形重试，3-strike `BROKEN` 仍保障用户可感知）。⑥CR-P3-01：`SyncStateBanner` 在 `BROKEN` 状态下**同时提供"查看日志"+"恢复同步"**，对齐 §4.5 状态机"仅 ResumeFromPauseUseCase 可清除 PAUSED_* / BROKEN"。⑦CR-P3-02：`SyncLogEntity` / `SyncLogModel` 新增 `errorType: String?` 字段（承载 `SanitizedGitException.originalType`），`SyncLogRepository.finishLog` / `pauseAndFinish` 新增 `errorType` 参数；`ExportLogsUseCase` 的 JSON 导出同步加字段；`SyncAuditDetailScreen` 展示"异常类型"行；Room `@Database(version = 2)` + `SimplygitDatabase.MIGRATION_1_2`（`ALTER TABLE sync_log ADD COLUMN errorType TEXT`）+ `DatabaseModule.addMigrations`。⑧文档状态更新为 `CR通过`。 |
