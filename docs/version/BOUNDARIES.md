# 迭代边界规则

> 本文件是 `/spec_review` 的低成本边界索引，只登记当前仓库实际存在的 SPEC。
> 每条摘要必须从 SPEC 的目标 / 非目标 / 严禁项中提炼成一句话，不写任务流水账；详细任务、严禁项与验收标准以对应 SPEC 为唯一权威。

## 总体设计锚点（非迭代 Spec，仅作不变约束）

- **总方案文档**：[`docs/Android 原生 Git 后台同步工具方案文档.md`](../Android%20原生%20Git%20后台同步工具方案文档.md)
  - 范围固定为"静默同步 + 轻量管理"，**不演进**为全功能 Git IDE
  - 架构固定为 MVVM 单向数据流，分层为 UI / Presentation / Domain / Data
  - Git 引擎固定为 **JGit**，文件访问固定走 **SAF**，后台任务固定走 **WorkManager**
  - 任何迭代 Spec 若突破以上约束，必须在 Spec 中显式标注"总方案变更"并同步更新总方案文档

## 迭代 SPEC 边界

| 迭代 | 文档 | 边界摘要 |
|------|------|---------|
| 1 | [Iteration1_MVP_Core_Link_SPEC.md](./Iteration1_MVP_Core_Link_SPEC.md) | 只做 PAT 录入（ESP 加密） + SAF 单目录授权（含 canRead/canWrite 探测） + 手动 Clone/Pull(仅无冲突场景)/Commit/Push 端到端验证；不做 WorkManager 自动同步、目录树/Diff UI、冲突分级/解决、SSH、多仓、JGit 自定义 FS 适配。 |
| 2 | [Iteration2_Auto_Silent_Sync_SPEC.md](./Iteration2_Auto_Silent_Sync_SPEC.md) | 只做 WorkManager 周期同步（15/30/60/MANUAL_ONLY + Constraints）、策略配置面、2min 防抖+空 commit 抑制、`ConflictClassifier` 六类分类、`PAUSED_FS/AUTH/CONFLICT/BROKEN` 状态机+"恢复同步"、Room 审计表（500 条/7 天滚动）、通知分级（A13 拒绝降级为首页 badge）、catch-up 冷启动补偿、日志 ZIP 导出（FileProvider，不自动上传）；不做冲突解决 UI、整文件二选一、SSH、OkHttp Transport 对接、FileObserver 事件触发、Shallow Clone、WindowCacheConfig 分档、多仓并行。 |
| 3 | [Iteration3_Conflict_Visualization_SPEC.md](./Iteration3_Conflict_Visualization_SPEC.md) | 只做 `FileTreeCache` 扁平化目录树（10k 文件）、JGit `DiffFormatter` 单栏 Diff（> 10k 行降级前 5k）、整文件二选一冲突解决（列表批量 + 自动 commit/push + 清除 `PAUSED_CONFLICT`）、SSH ed25519 生成/导入（MINA SSHD + TOFU known_hosts + EncryptedFile）；不做行级 chunk 选择、Diff 编辑、多分支/rebase UI、SSH Agent/Hardware Key、`.gitignore` 可视化、Blame/History。 |
