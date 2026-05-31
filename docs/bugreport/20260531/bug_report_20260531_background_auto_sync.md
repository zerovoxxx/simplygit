# BUG-20260531：默认策略未入队导致压后台后自动同步不运行

## 背景

用户完成 Vault / Remote / Credential 配置后，首页会显示默认自动同步间隔（15 分钟），但如果用户没有进入“同步策略”页面并点击保存，`WorkManager` 的周期任务可能从未被创建。此时把 App 压后台后不会按默认策略自动同步，看起来像“后台自动同步挂掉”。

## 根因

- `SyncPolicyRepositoryImpl.current()` 会懒加载并持久化 `SyncPolicyModel.DEFAULT`。
- 真正调用 `SyncScheduler.schedulePeriodic(policy)` 的路径只有 `UpdateSyncPolicyUseCase`，也就是策略页保存。
- 绑定 Vault / Remote、保存认证模式、手动 Clone/Pull/Commit/Push 成功、冷启动恢复这些路径都没有补齐 `enqueueUniquePeriodicWork`。

因此“默认策略存在”与“周期任务已入队”之间缺少显式桥接。

## 修复

- 新增 `EnsureAutoSyncScheduledUseCase`：当 `RepoBindingRepository.currentOrNull()` 返回完整绑定时，读取当前策略并尽力调用 `SyncScheduler.schedulePeriodic(policy)`，不阻断绑定 / 手动 Git 操作主流程。
- 在以下路径调用该用例：
  - `BindVaultUseCase` / `BindRemoteUseCase`：绑定步骤完成后尝试补齐排期；
  - `SaveAuthTypeUseCase`：PAT/SSH 模式切换后尝试补齐排期；
  - `HomeViewModel.runOp`：手动 Git 操作成功后尝试补齐排期；
  - `MainActivity.onCreate`：冷启动时重新确保周期任务存在，再做 catch-up stale 判定。
- 新增 `EnsureAutoSyncScheduledUseCaseTest` 覆盖：
  - 完整绑定时按当前策略调度；
  - 绑定不完整时不调度；
  - Remote 填写使绑定变完整时触发调度。

## 风险与边界

- 仍然只通过 `WorkManager` 调度后台任务，没有引入 Service / AlarmManager。
- `schedulePeriodic` 使用唯一任务名和 `ExistingPeriodicWorkPolicy.UPDATE`，重复调用是幂等更新，不会堆叠多个周期任务。
- 本修复不改变同步策略语义；`MANUAL_ONLY` 仍由 `SyncSchedulerImpl.schedulePeriodic` 取消周期任务。

---

# BUG-20260531-B：后台 Worker 中断后 `RUNNING` 与审计行不收口

## 背景

用户截图显示首页长期停在"正在同步"，同步审计页存在一条 `05-30 19:28:05 (进行中) / 补偿` 记录，后续手动同步成功但该补偿记录仍未结束。这说明问题不只是"周期任务未入队"，还存在旧 Worker 被系统中断后业务状态无法恢复的问题。

## 根因

- `RunSyncUseCase` 通过 `SyncLogRepository.tryStartRun()` 原子执行 `IDLE -> RUNNING` 并插入 `sync_log(endedAt = null, result = null)`。
- 正常路径会在 Git 操作结束后 `finishLog(...)` 并把仓库状态改回 `IDLE`。
- 但 Android 后台场景下，进程死亡、WorkManager stop、厂商后台清理都可能发生在 `tryStartRun()` 之后、正常 finish 之前。
- 旧代码在下一次 Worker 启动时看到 `syncState == RUNNING` 直接返回 `SkippedRunning`，而 `GitSyncWorker` 又把它映射成 `Result.success()`，因此 WorkManager 不会重试，也不会自动修复旧审计行。
- `CancellationException` 被 `catch (Throwable)` 包住后走普通异常分派，取消语义和状态收口都不稳定。

## 修复

- 为 `RUNNING` 增加 30 分钟租约：每次 `RunSyncUseCase` 启动先调用 `SyncLogRepository.recoverStaleRunning(...)`。
- `recoverStaleRunning(...)` 在 Room 事务中：
  - 将超出租约的 `PERIODIC` / `CATCHUP` open log 标记为 `ABORTED`；
  - 若仓库仍为 `RUNNING` 且没有任何新鲜 worker open log，则 CAS 恢复为 `IDLE`；
  - 若仓库本来已是 `IDLE` / `PAUSED_*`，只关闭孤儿审计行，不覆盖 `repository.lastSyncAt / lastSyncResult`。
- 新增 `SyncLogRepository.abortRun(...)`：Worker 协程被取消时，在 `NonCancellable` 中把当前 log 标记为 `ABORTED` 并释放 `RUNNING`。
- `RunSyncUseCase` 单独捕获 `CancellationException`，完成最小收口后重抛，保留 WorkManager / 协程取消语义。
- 新增 Robolectric + Room 回归测试：
  - stale worker log 会变成 `ABORTED`，仓库恢复 `IDLE`；
  - fresh worker log 不会被误恢复；
  - `RUNNING` 但没有 open worker log 时恢复 `IDLE`；
  - `IDLE` 下的孤儿 log 会关闭但不覆盖最近一次成功摘要；
  - 取消路径 `abortRun(...)` 能原子收口。

## 风险与边界

- 30 分钟远高于 Spec NF2 的 15 秒端到端预算，也高于 WorkManager 对普通 Worker 的实际运行窗口，避免误杀正常同步。
- 不引入 Foreground Service / AlarmManager，仍只通过 WorkManager 调度。
- 不改变 PAUSED 状态机语义；`PAUSED_* / BROKEN` 仍只能由用户恢复同步清除。
