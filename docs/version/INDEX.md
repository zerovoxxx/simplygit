# 迭代状态总表

> 所有迭代的状态追踪。由 `/spec` 和 `/mr` 命令自动维护。

| 迭代 | 标题 | 状态 | 文档 | 创建日期 |
|------|------|------|------|---------|
| 1 | MVP 核心链路 | ✅ 已完成 | [Iteration1_MVP_Core_Link_SPEC.md](./Iteration1_MVP_Core_Link_SPEC.md) | 2026-05-01 |
| 2 | 自动化静默同步 | ✅ 已完成 | [Iteration2_Auto_Silent_Sync_SPEC.md](./Iteration2_Auto_Silent_Sync_SPEC.md) | 2026-05-01 |
| 3 | 冲突可视化 | ✅ 已完成 | [Iteration3_Conflict_Visualization_SPEC.md](./Iteration3_Conflict_Visualization_SPEC.md) | 2026-05-02 |

## 变更记录

| 日期 | 版本 | 变更人 | 变更说明 |
|---|---|---|---|
| 2026-05-01 | v1.0 | alexjhwen | 新增迭代 1：MVP 核心链路（PAT + SAF + JGit 手动链路）。 |
| 2026-05-01 | v1.1 | alexjhwen | 迭代 1 Spec 完成评审修订，状态流转为"评审完成（待开发）"；评审报告见 `docs/version/review/Iteration1_MVP_Core_Link_REVIEW.md`。 |
| 2026-05-01 | v1.2 | alexjhwen | feat(iter1)：MVP 核心链路落地 + CR 修复（37 files / +2028），Spec v1.3 → ✅ 已完成；详见 SPEC §10。 |
| 2026-05-01 | v1.3 | alexjhwen | 新增迭代 2：自动化静默同步（WorkManager 周期 + 防抖 + 冲突分类 + Room 审计 + 通知分级），对齐总方案 §9 Phase 2。 |
| 2026-05-02 | v1.4 | alexjhwen | 迭代 2 Spec 首轮评审完成，发现 2 P0 + 6 P1 问题，状态保持"评审中"待修订；评审报告见 `docs/version/review/Iteration2_Auto_Silent_Sync_REVIEW.md`。 |
| 2026-05-02 | v1.5 | alexjhwen | 迭代 2 Spec v1.1 修订完成，闭环 2 P0 + 6 P1 + 2 P2 共 10 个问题（核心改动：`SyncErrorKind` 异常分派 + `ConflictClassifier` 下沉 Data 层 + `DiagnosticsLogger` 按日滚动 + Room `@ForeignKey` + 接口扩展对齐 R9），状态流转为"评审完成（待开发）"。 |
| 2026-05-02 | v1.6 | alexjhwen | feat(iter2)：自动化静默同步全量交付（G1-G9 + CR 1 中/6 低，Spec v1.2 → ✅），78 Kt 文件 + Room v1→v2 Migration；详见 SPEC §10。 |
| 2026-05-02 | v1.7 | alexjhwen | 新增迭代 3：冲突可视化（目录树 + Diff + 整文件二选一 + SSH ed25519），对齐总方案 §9 Phase 3，Room v2→v3；详见 SPEC §10。 |
| 2026-05-03 | v1.8 | alexjhwen | 迭代 3 Spec 首轮评审完成，发现 **5 P0 + 7 P1 + 1 P2** 共 13 个问题（核心：PullRepository/PushRepository/CloneRepository/SyncStateRepository/PushOutcome/SyncWorker/RepoDetailScreen/BindRepoScreen/Credential.Pat 等 9 处代码实体虚构，以及 `Repository.auth_type` Migration 未落 + 通知跳转链路未设计），状态保持"评审中"待修订；评审报告见 `docs/version/review/Iteration3_Conflict_Visualization_REVIEW.md`。 |
| 2026-05-03 | v1.9 | alexjhwen | 迭代 3 Spec v1.1 修订完成，闭环 5 P0 + 7 P1 + 1 P2 共 13 个问题（核心：挂载点全部回归现有 `GitRepository/SyncLogRepository/HomeScreen/MainActivity`；新增 `ClearConflictPauseUseCase + SyncResult.CONFLICT_RESOLVED` 表达冲突解决语义；Migration v2→v3 追加 `ALTER repository ADD COLUMN auth_type`；通知新增 `NAV_CONFLICT + EXTRA_REPO_ID` 路由；`CredentialRepository` 不扩展，SSH 走独立 `SshKeyRepository`；Skip × Push 6 行真值表；`SshPassphraseCache` `@ApplicationScope`；删除 `DiffSource.COMMIT_VS_COMMIT` 冗余），状态流转为"评审完成（待开发）"。 |
| 2026-05-03 | v2.0 | alexjhwen | feat(iter3)：冲突可视化全量交付（4 Phase / 72 文件 / +5750），SSH 链路 CR 11 问题闭环后 Spec v1.3 → ✅；详见 SPEC §10。 |
| 2026-05-03 | v2.1 | alexjhwen | fix(app)：同步链路与首页交互缺陷修复（39 文件 / +1543），新增 BUG 审查报告。 |
| 2026-05-03 | v2.2 | AI | fix(sync)：后台同步/SSH 缺陷修复（8 BUG），补审查报告；详见 bug_report_y7b0。 |
| 2026-05-03 | v2.3 | AI | fix(app)：闭环 p16x 扫描 5 条 BUG（HomeVM 异常兜底 / FileTree 优先级 / ResolveConflict TOFU / Auth 空密钥 / Export retention），10 文件 +630/-49；详见 bug_report_p16x。 |
| 2026-05-03 | v2.4 | AI | fix(app)：发布签名与认证选择修复（5 文件 / +55/-24），v1.0.0 arm64；详见 git log。 |
| 2026-05-03 | v2.5 | AI | fix(app)：首页 Clone 时绑定只读区清空（HomeVM boundSnapshot）+ 关于版块 + FLAG_SECURE 解除 + Push 按钮换行修复，发布 v1.0.3（6 文件 +115/-20）。 |
| 2026-05-03 | v2.6 | AI | fix(app)：修复同步审计统计恒为 0（8 文件 +152/-34），commitAll/push 返回 DTO 带真实 filesChanged/commitsPushed，发布 v1.0.4；新增反模式 P14。 |
| 2026-05-31 | v2.7 | AI | fix(sync)：补齐默认自动同步策略的 WorkManager 入队路径，避免未进策略页保存时压后台后周期同步不运行；详见 `docs/bugreport/20260531/bug_report_20260531_background_auto_sync.md`。 |
