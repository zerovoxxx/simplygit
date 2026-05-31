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
