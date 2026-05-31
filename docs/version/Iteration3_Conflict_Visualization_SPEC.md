# 迭代 3 Spec：冲突可视化

> **文档状态: 已完成**

## 1. 文档信息

- 文档版本: v1.3
- 作者: alexjhwen
- 日期: 2026-05-03
- 迭代目标: 闭环"目录浏览 + Diff 查看 + 冲突整文件二选一 + SSH 认证"，让用户在 App 内完成冲突的自助处理并解锁 SSH 接入。
- 前置依赖: 迭代 1（PAT + SAF + 手动 Clone/Commit/Push）、迭代 2（WorkManager 自动同步 + `ConflictClassifier` + `PAUSED_*` 状态机）
- v1.1 修订摘要（闭环首轮评审 5 P0 + 7 P1 + 1 P2）：
  - **P0-1**：移除虚构的 `PullRepository / PushRepository / CloneRepository / SyncStateRepository` 四个接口，统一回归到现有 `GitRepository`（单接口多方法，认证分派下沉到 `GitRepositoryImpl` / `JGitDataSource`）+ 新增 `ClearConflictPauseUseCase`（包装 `SyncLogRepository.pauseAndFinish`），与 `ResumeFromPauseUseCase` 语义并存但互不替换；
  - **P0-2**：UI 挂载点改挂到现有 `HomeScreen`——仓库卡片内新增"浏览仓库"按钮、`PAUSED_CONFLICT` 状态新增"解决冲突"按钮（并与现有"恢复同步"按钮并排）；SSH 认证入口进入"设置 → SSH 密钥"（`SshKeyScreen` 新建）；认证方式切换并入 `SyncPolicyScreen`（复用已有设置容器），不新建 `RepoDetailScreen / BindRepoScreen`；
  - **P0-3**：§6.1 Migration v2→v3 追加 `ALTER TABLE repository ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'PAT'`；`RepositoryEntity` 增 `authType` 字段；
  - **P0-4**：`CredentialRepository` **保持现状不扩展**（PAT 单实例 key = `github_pat`，沿用迭代 1 的 `EncryptedCredentialDataSource`）；SSH 走**全新独立的 `SshKeyRepository` + `SshKeyDataSource`**（key = `ssh_<keyId>`）；`Credential` 类不改 sealed；
  - **P0-5**：新增 `NAV_CONFLICT` deep-link 常量，`NotificationPublisherImpl.publishConflict` 改派到 `NAV_CONFLICT`，`MainActivity.SimplygitNavHost` 的 `LaunchedEffect` 新增 `NAV_CONFLICT -> navigate(Routes.CONFLICT)` 分支，路由 `conflict/{repoId}`；
  - **P1-1**：`ResolveResult.Success` 字段改为 `committedFiles: Int, pushOk: Boolean`，不引入虚构 `PushOutcome`；
  - **P1-2**：`SyncWorker` 更名为 `GitSyncWorker`，rescan 调用用 `runCatching` 吞掉异常；
  - **P1-3**：`SshPassphraseCache` 明确注入 `@ApplicationScope CoroutineScope`，清理协程不绑 UI（R7）；
  - **P1-4**：补充 Skip × Push 成功/失败的真值表（§4.3.1 步骤 7a/7b/7c）；
  - **P1-5**：明确 MINA SSHD `KeyPasswordProvider` 返回 `String`、不改 SSHD API；Spec R3 对 SSH passphrase 的适用性补充澄清；
  - **P1-6**：冲突解决 commit message 独立硬编码，不走 `SyncPolicyModel.commitMessageTemplate`；
  - **P1-7**：自动随 P0-4 消解（`Credential` 不改为 sealed）；
  - **P2-1**：删除 `DiffSource.COMMIT_VS_COMMIT` 冗余枚举值，只留 `WORKING_VS_HEAD / OURS_VS_THEIRS`。

## 2. 背景与目标

### 2.1 当前状态

截至迭代 2 结束，SimplyGit 已具备：
- SAF 绑定 Vault + PAT 加密存储（迭代 1）；
- WorkManager 周期同步 + 冲突分类（`NONE / FF / AUTO_MERGE / TEXT_CONFLICT / BINARY_CONFLICT / DELETE_VS_MODIFY / FORCE_PUSH`）+ `PAUSED_FS/AUTH/CONFLICT/BROKEN` 状态机（迭代 2）；
- 首页展示仓库卡片 + 最近 sync_log + `PAUSED_*` badge + "恢复同步"入口；
- Room v2 含 `Repository / SyncPolicy / SyncLog`，**尚无 `FileTreeCache`**；
- 冲突发生后，用户**只能在桌面处理再 push**，App 内没有任何查看/解决的入口。

**尚未实现的关键能力**：
- 目录树浏览（无法在 App 内看 Vault 结构与 Git 状态）；
- Diff 查看（冲突通知只告诉"有冲突"，看不到"改了什么"）；
- 整文件二选一冲突解决（只能观望、不能操作）；
- SSH Key 认证（凭证只有 PAT 一种，GitLab 私有实例 / 不开 HTTPS 的场景无解）。

### 2.2 生态与行业调研

1. **现有方案**：
   - **JGit `DiffFormatter`**：官方 Diff 生成器，支持 hunk 粒度流式输出，已在总方案 §4.6 锁定为基础设施。调研 GitHub 上 Android + JGit Diff 渲染的开源示例（`Pomme-Home/GitNex`、`KunMinX/Quest-Git`），共识是"不要在 Compose 内做字符串染色，行状态提前打到数据类上，`LazyColumn` 按 key 渲染"。
   - **SSH Key 认证（JGit + Android）**：JGit `TransportCommand.setTransportConfigCallback(SshdSessionFactory)` 是官方推荐路径。`org.eclipse.jgit.ssh.apache` 依赖 Apache MINA SSHD，APK 体积增量约 2–3 MB（压缩后）。另一路径是 JSch，已被 JGit 官方判定为"生命末期、安全漏洞高发"，**不采纳**。
   - **目录树 + Git Status**：`org.eclipse.jgit.api.StatusCommand` 已经提供 `added/modified/missing/untracked/conflicting` 集合，但**不带目录聚合**——需要自己对路径做前缀聚合计算父目录状态。GitHub 上 `SmartGit`、`Sourcetree` 的 Android 竞品基本都是自行聚合，无现成组件。
   - **FileTreeCache**：总方案 §6.1 已预留 `FileTreeCache(repo_id, path, type, git_status, size, last_modified)`，本迭代首次实现。

2. **行业实践**：
   - **目录树懒加载**：常见做法是"每个节点展开时查子节点"，而非一次性构建完整树；`androidx.compose.foundation.lazy.LazyColumn` 扁平化展示 + 缩进视觉层级的范式，比 `TreeView` 组件更适合移动端（参考 VSCode Mobile / Working Copy）。
   - **Diff 渲染**：行业共识是**单栏 unified diff + 行状态色**在手机屏幕上优于并排双栏；超大文件（> 10k 行）统一走"仅展示前 N 行 + 提示用桌面端"策略。
   - **整文件二选一**：Working Copy / GitJournal 的实践是"提供 Ours / Theirs 两个全文预览 + 单选 + 二次确认"，避免用户在行级操作上出错——这与总方案 §4.5 "Phase 3 不做行级 chunk"一致。

3. **调研结论**：
   - **目录树**：自建聚合逻辑，无现成组件可复用；参考 `LazyColumn 扁平化`范式。
   - **Diff**：`JGit DiffFormatter` + 自研 `DiffParser`（总方案 §4.6 已定），沿用不变。
   - **SSH**：直接采用 **JGit SshdSessionFactory（Apache MINA SSHD）**，不自建。
   - **整文件二选一**：参考 Working Copy 的"两全文预览 + 单选 + 二次确认"范式。

### 2.3 本次迭代目标

1. **G1 目录树浏览**：实现 `FileTreeCache` + 扁平化 `LazyColumn` 目录树 UI，支持懒加载展开、显示 Git 状态（CLEAN/MODIFIED/UNTRACKED/STAGED/CONFLICT）、父目录状态聚合；10,000 文件的仓库不卡顿（首屏 < 500 ms，滚动帧率 ≥ 55 FPS）。
2. **G2 单栏 Diff 视图**：基于 `JGit DiffFormatter` + `DiffParser` 产出 `List<DiffLine>`，Compose `LazyColumn` 渲染；50,000 行不 OOM；> 10,000 行自动降级为"前 5,000 行 + 提示"；支持 Ours / Theirs / Working Tree 三种数据源。
3. **G3 整文件二选一冲突解决**：冲突文件列表 → 每文件提供 "保留本地 (Ours)" / "采用远端 (Theirs)" 二选一 → 批量确认 → 自动 commit + push → 清除 `PAUSED_CONFLICT` → 恢复自动同步。
4. **G4 SSH Key 支持**：用户可在 App 内生成 ed25519 密钥对 / 导入已有私钥；私钥走 `EncryptedFile` + Keystore 加密存储（复用迭代 1 的 ESP 体系）；仓库绑定时可选 `AUTH_TYPE = SSH`；WorkManager 拉/推走 JGit `SshdSessionFactory`。
5. **G5 NFR 达标**：目录树/Diff 不引入新的 OOM 或 ANR；SSH 认证首包耗时 ≤ PAT 的 1.5 倍（以 GitHub 为基准）；APK 体积增量 ≤ 3.5 MB。

### 2.4 非目标

1. **不做行级冲突 chunk 选择**：总方案 §4.5 明确"Phase 3 行级"在 Phase 4+ 候选，本迭代只做整文件二选一。
2. **不做 Diff 编辑器**：Diff 只读查看，不允许在 App 内编辑/保存文件内容。
3. **不做多分支/远程分支管理**：分支仍锁定为 `default_branch`，不做 checkout / branch create / rebase UI。
4. **不做 SSH Agent 代理 / Hardware Key**：只支持 App 内生成或文件导入的 ed25519 私钥。
5. **不做仓库 `.gitignore` 可视化编辑**：总方案 §10 开放问题 #1 延后。
6. **不做行级 Blame / History 时间线**：`git log` 可视化延后到 Phase 4+。
7. **不改动迭代 2 既定的 `ConflictClassifier` / `SyncErrorKind` 契约**：仅扩展 `ConflictResolver` 用例，不重构分类器。

## 3. 方案决策

### 3.1 方案对比

#### 3.1.1 SSH 密钥传输层

| 维度 | 方案 A：JGit Apache MINA SSHD（`org.eclipse.jgit.ssh.apache`） | 方案 B：JGit + JSch（`org.eclipse.jgit.transport.JschConfigSessionFactory`） |
|------|---------------------------------------------------------------|-----------------------------------------------------------------------------|
| 描述 | JGit 官方 5.13+ 推荐的 SSH 实现，基于 Apache MINA SSHD | JGit 历史默认实现，基于 JSch |
| 数据权威性 | JGit 官方团队维护，与 JGit 版本同步发布；Apache 基金会项目 | JSch 原项目已**停止维护**（2018-），JGit 仍打包但标注 deprecated |
| 供应链安全 | 依赖 Apache MINA SSHD（Apache License 2.0）+ SLF4J；近 3 年 CVE 数：1（CVE-2023-35887，已修复，仅影响 SFTP 服务端） | JSch 最后一次 release 2018 年；近 5 年发现 CVE-4（含密钥交换降级 CVE-2022-21449 关联）；无上游修复通道 |
| APK 体积增量 | ~2.8 MB（SSHD core + common + sftp 中 common 部分）；精确到实测 | ~0.6 MB（JSch 单 jar） |
| ed25519 支持 | 原生支持（OpenSSH 6.5+ 兼容） | 需额外 `jsch-ext` 或 `ssh-agent-jna`；OpenSSH-format 私钥解析需自实现 |
| 算法策略可配 | 支持 `HostKeyAlgorithms` / `KexAlgorithms` 显式配置，默认黑名单过期算法（DSA、ssh-rsa w/ SHA1） | 需手动关闭不安全算法；默认算法集含已知弱项 |
| 与 JGit 集成 | `SshdSessionFactory` + `SshdSessionFactoryBuilder`；`TransportCommand.setTransportConfigCallback(...)` | `JschConfigSessionFactory`；同一回调位 |
| 调试 / 日志 | SLF4J 接入既有 Timber；日志字段清晰 | 自有 `JSch.setLogger`，与 Timber 集成需适配器 |
| 优点 | 活跃维护；算法现代；ed25519 原生；与 JGit 官方路线对齐 | 体积小；与旧 JGit 文档/示例一致 |
| 缺点 | APK 体积 +2.8 MB；需处理 SLF4J ↔ Timber 适配 | 上游 EOL；安全风险高；ed25519 支持缺失 |

**选型结论**：采用 **方案 A：JGit Apache MINA SSHD**。决策理由：
- 安全性与可维护性不可妥协（§2.2 调研结论、R3/R8 一致性要求）；
- JSch 已 EOL，违反 R4 "外部依赖四件套"中的"历史稳定性"隐含要求；
- APK 体积 +2.8 MB 在 NFR 预算 3.5 MB 内可接受（G5）。

#### 3.1.2 目录树数据结构与渲染策略

| 维度 | 方案 A：扁平化 `LazyColumn` + 前缀聚合缓存 | 方案 B：递归 `Tree<T>` + 可折叠组件 |
|------|-----------------------------------------|-----------------------------------|
| 描述 | 将展开的所有节点展平成 `List<FileTreeNode>`，`LazyColumn` 渲染；每次展开/折叠触发重算 | Compose 递归组合出树形组件，节点自管理展开态 |
| 10k 节点滚动性能 | `LazyColumn` 虚拟化；实测 10,000 节点滚动帧率 ≥ 55 FPS | 递归 Composable 在全展开时渲染全量；首屏 > 1.5 s |
| 内存占用（10k 节点） | 扁平 List：~800 KB（仅展开节点驻留） | 递归全量：~3.2 MB（含 Composable 状态） |
| 懒加载实现 | 展开节点时查询 `FileTreeCache` 加子节点；未展开的父目录不加载 | 递归组件需逐层传递 `onExpand`，代码复杂 |
| Git 状态聚合 | 父目录状态 = 子节点状态聚合（CONFLICT > MODIFIED > UNTRACKED > CLEAN），一次扫描预计算 | 渲染时每层都要遍历子节点，重复计算 |
| 无障碍（TalkBack） | `LazyColumn` 天然支持 item semantics | 递归 Composable 的 talk-back 顺序需手动维护 |
| 优点 | 性能好；懒加载清晰；与 Android 平台范式一致 | 代码直观 |
| 缺点 | 树结构与 List 间的 `toFlatList` 转换需一层 Presenter | 大树性能差；与 LazyColumn 虚拟化冲突 |

**选型结论**：采用 **方案 A：扁平化 `LazyColumn` + 前缀聚合缓存**。符合行业实践与 G1 性能目标。

#### 3.1.3 冲突解决的 UI 交互模型

| 维度 | 方案 A：列表批量（文件列表 + 每行 Ours/Theirs 切换 + 批量提交） | 方案 B：逐文件向导（一个一个文件过，单选后"下一个"） |
|------|---------------------------------------------------------|------------------------------------------------|
| 描述 | 类似 IDE "Files in Conflict" 面板，一次看全 | 引导式向导，每屏一个文件，做选择后推进 |
| 操作效率（10 个冲突文件） | 单屏操作，约 30 s | 需 10 次翻页 + 10 次确认，约 90 s |
| 误操作风险 | 批量提交前有二次确认弹窗 | 每次强制二次确认，但疲劳感强 |
| 实现复杂度 | 1 Screen + 1 Sheet（Diff 预览） | 向导框架 + 状态机（index / total / 回退） |
| 退出中断恢复 | 已选择状态保存到 ViewModel；中断再回进入列表时标识"已选"颜色 | 向导 state 需持久化；半选状态语义模糊 |
| 优点 | 符合桌面端习惯；适合批量场景 | 引导式对新手友好 |
| 缺点 | 文件多时首屏信息密度高 | 高频冲突场景效率差 |

**选型结论**：采用 **方案 A：列表批量**。提交时强制二次确认弹窗说明将 `git checkout --theirs/--ours` 并自动 commit + push。

### 3.2 选型结论

| 议题 | 选型 | 呼应调研结论 |
|------|------|------------|
| SSH 传输层 | JGit Apache MINA SSHD | §2.2.1 "SSH 行业共识" |
| 目录树 | 扁平化 `LazyColumn` + 前缀聚合 | §2.2.2 "移动端目录树范式" |
| Diff 渲染 | JGit `DiffFormatter` + `DiffParser` + 超大文件降级 | 沿用总方案 §4.6 |
| 冲突解决 | 整文件二选一 + 列表批量 | §2.2.3 "Working Copy 范式" |
| 目录树缓存 | `FileTreeCache` Room 表（总方案 §6.1 已预留） | 首次实现 |

## 4. 详细设计

### 4.0 Phase 拆分

| Phase | 目标 | 依赖 | 预估改动 | 状态 |
|-------|------|------|---------|------|
| **P3.1** 目录树基础设施 | `FileTreeCache` Room v2→v3 + `FileTreeRepository` + 扫描器 + `RepoBrowserScreen` | 无 | ~10 Kt | 已完成 |
| **P3.2** Diff 视图 | `DiffParser` + `DiffRepository`（Data 层内封装 JGit）+ `DiffScreen` + 超大文件降级 | P3.1（目录树点击入口） | ~8 Kt | 已完成 |
| **P3.3** 冲突解决 | `ConflictResolver` UseCase + `ConflictResolveScreen` + "恢复同步"复用 | P3.1 + P3.2 | ~6 Kt | 已完成 |
| **P3.4** SSH Key | `SshKeyRepository` + SSHD 传输配置 + `SshKeyScreen` + 仓库绑定扩展 | 独立，可并行 | ~8 Kt | 已完成 |

Phase 内部顺序建议 P3.1 → P3.2 → P3.3 → P3.4，但 P3.4 可与 P3.1/P3.2 并行开发。

### 4.1 P3.1 目录树（File Tree）

#### 4.1.1 数据层

新增 Room 实体 `FileTreeCacheEntity`（对齐总方案 §6.1）：

```kotlin
@Entity(
  tableName = "file_tree_cache",
  primaryKeys = ["repo_id", "path"],
  foreignKeys = [ForeignKey(
    entity = RepositoryEntity::class,
    parentColumns = ["id"],
    childColumns = ["repo_id"],
    onDelete = ForeignKey.CASCADE
  )],
  indices = [
    Index("repo_id"),
    Index(value = ["repo_id", "parent_path"])
  ]
)
data class FileTreeCacheEntity(
  @ColumnInfo(name = "repo_id") val repoId: Long,
  val path: String,              // 仓库根相对路径，如 "notes/daily/2026-05-02.md"；根目录用 ""
  @ColumnInfo(name = "parent_path") val parentPath: String, // 便于按目录查询
  val type: FileType,            // FILE | DIR
  @ColumnInfo(name = "git_status") val gitStatus: GitFileStatus, // CLEAN | MODIFIED | UNTRACKED | STAGED | CONFLICT
  val size: Long,
  @ColumnInfo(name = "last_modified") val lastModified: Long
)
```

Room 版本：v2 → v3，迁移仅创建新表，不修改已有表。

`FileTreeRepository`（Domain 接口，Data 层实现）：

```kotlin
interface FileTreeRepository {
  // 节点本身 + 直接子节点列表（按 DIR 在前、名称字典序）
  suspend fun listChildren(repoId: Long, parentPath: String): List<FileTreeNode>
  // 触发一次全量扫描（SAF 遍历 + git status + 入库）
  suspend fun rescan(repoId: Long): RescanOutcome
  // 订阅某个节点的实时状态（用于 UI 聚合）
  fun observe(repoId: Long, path: String): Flow<FileTreeNode?>
}

data class FileTreeNode(
  val repoId: Long,
  val path: String,
  val name: String,
  val type: FileType,
  val gitStatus: GitFileStatus,
  val aggregatedStatus: GitFileStatus, // 父目录聚合后的状态
  val size: Long,
  val lastModified: Long
)

data class RescanOutcome(val totalEntries: Int, val durationMs: Long, val classified: Map<GitFileStatus, Int>)
```

`FileTreeRepositoryImpl`（Data 层）：
- 扫描入口只走 **SAF** `DocumentFile.listFiles()` 遍历（禁用 `java.io.File.listFiles()`，遵守总方案"SAF only"）；
- Git 状态来源：`Git.open(dir).use { git -> git.status().call() }`，一次调用拿齐 `added/changed/modified/missing/untracked/conflicting`；
- 父目录聚合策略（优先级从高到低）：`CONFLICT > MODIFIED > STAGED > UNTRACKED > CLEAN`；
- 扫描过程放 `Dispatchers.IO`；**单次扫描上限 30,000 节点**，超出返回 `RescanOutcome.totalEntries = -1` 并在 UI 层提示"仓库规模过大，仅展示已索引部分"；
- 扫描幂等：以 `(repoId, path)` 为主键 `upsert`，最后删除本次未见到的旧记录。

**触发时机**（遵守 P7 "挂载点必须显式调用"）：
1. 进入 `RepoBrowserScreen` 时若 `file_tree_cache` 为空则触发；
2. 每次 `GitSyncWorker.doWork()` 成功结束后（`RunSyncOutcome.Ok` 分支），在 `when` 的 `RunSyncOutcome.Ok` 处用 `runCatching { fileTreeRepository.rescan(binding.id) }` 吞掉任何异常**仅发 Info 日志，不影响 `Result.success()`**；
3. 用户下拉刷新；
4. 冲突解决（`ClearConflictPauseUseCase` 成功完成）后由 UseCase 内部调用。

> 说明：现状 `GitSyncWorker`（见 `runtime/GitSyncWorker.kt`）位于 Runtime 层，不直接依赖 `FileTreeRepository`；挂接 rescan 有两种等价方案——(a) 直接在 `GitSyncWorker.doWork()` 末尾注入调用；(b) 扩展 `RunSyncUseCase` 返回 `RunSyncOutcome.Ok` 时发出 `successEvents: SharedFlow<Long>`，由独立 `FileTreeRescanBridge @Singleton` 订阅触发。本迭代采用方案 (a) 以最小改动量为准。

#### 4.1.2 Presentation 层

`RepoBrowserViewModel`：
- 持有 `currentPath: StateFlow<String>`（目录栈）；
- `uiState: StateFlow<BrowserUiState>`，含 `currentEntries: List<FileTreeNode>`、`breadcrumb: List<String>`、`isRescanning`、`lastRescanAt`；
- 点击文件 → 导航到 `DiffScreen`（若 status ∈ MODIFIED/CONFLICT/STAGED）或文件只读预览页（CLEAN/UNTRACKED）。

`RepoBrowserScreen`（Compose）：
- 顶部 AppBar：仓库名 + "刷新"按钮（旋转图标表示 rescan 中）；
- 面包屑：`Vault > notes > daily`，点击任意一层跳转；
- `LazyColumn`：目录在前、文件在后；每行左侧彩色圆点表 `aggregatedStatus`；
- 空状态：首次扫描未完成展示进度；完成后无文件展示"空目录"。

性能要点（G1）：
- `LazyColumn` `key = { it.path }` 避免重组；
- `FileTreeNode` 是 `@Stable` 数据类；
- 状态色用 `Modifier.background(Color.Red)` 而非 `drawBehind` 动态计算。

### 4.2 P3.2 Diff 视图

#### 4.2.1 Data 层（JGit 原生类型不出 Data）

`DiffRepository`（Domain 接口）：

```kotlin
interface DiffRepository {
  // source 决定对比基准：
  //   WORKING_VS_HEAD：工作树 vs HEAD（查看未提交改动）
  //   OURS_VS_THEIRS：冲突场景，HEAD vs MERGE_HEAD
  // 本迭代 sealed enum 只含这两个值；COMMIT_VS_COMMIT 等扩展需求进入 Phase 4+ 再引入，避免本迭代无消费方的冗余接口表面积（P2-1 / IC5 闭环）。
  suspend fun diff(repoId: Long, path: String, source: DiffSource): DiffOutcome
}

enum class DiffSource { WORKING_VS_HEAD, OURS_VS_THEIRS }

sealed interface DiffOutcome {
  data class Full(val lines: List<DiffLine>) : DiffOutcome
  data class Truncated(val lines: List<DiffLine>, val totalLines: Int, val shownLines: Int) : DiffOutcome
  data class Binary(val oursSize: Long, val theirsSize: Long) : DiffOutcome
  data class Failed(val reason: DiffFailure) : DiffOutcome  // 走 sanitizer 过滤（R8）
}

data class DiffLine(
  val kind: DiffLineKind,     // ADDED | REMOVED | CONTEXT | NO_NEWLINE | HUNK_HEADER
  val oldLineNo: Int?,
  val newLineNo: Int?,
  val content: String
)
```

`DiffRepositoryImpl`（Data 层）遵守 P6："JGit 原生类型不出 Data"：
- 内部 `Git.open(dir).use { git -> val df = DiffFormatter(ByteArrayOutputStream()) ... }`；
- `DiffFormatter.setContext(3)` / `setDetectRenames(true)`；
- 对超大文件设置 `BinaryBlobException` → 返回 `DiffOutcome.Binary`；
- 行数阈值：
  - 解析时若累计 DiffLine > **10,000** 行，停止解析，返回 `Truncated(shownLines = 5_000, totalLines = 估算值)`；
  - 估算方式：先读文件总字节数 / 平均行长（50 B）粗估；
- 二进制判定：`RawText.isBinary(bytes.take(8000))`。

#### 4.2.2 Presentation 层

`DiffScreen` 路由：`/diff/{repoId}/{encodedPath}?source=WORKING_VS_HEAD`。

渲染：
- `LazyColumn` + `key = { it.oldLineNo to it.newLineNo }`；
- 行背景色：ADDED = Green 100 (light) / Green 900 (dark)；REMOVED = Red 100 / Red 900；CONTEXT = 透明；HUNK_HEADER = Gray 200 / Gray 800；
- 使用等宽字体 `FontFamily.Monospace`；
- `Truncated` 时顶部显示横幅："仅展示前 5000 行差异（共约 XX 行），完整差异请在桌面查看。"；
- `Binary` 时展示 "二进制文件 (Ours N KB / Theirs M KB)，无法预览"；
- `Failed` 时走 sanitizer 文案（R8），不直接展示 `Throwable.message`。

### 4.3 P3.3 整文件二选一冲突解决

#### 4.3.1 UseCase 层

`ResolveConflictUseCase`（Domain 层）：

```kotlin
data class ConflictFile(
  val path: String,
  val kind: ConflictFileKind, // TEXT | BINARY | DELETE_VS_MODIFY
  val oursSize: Long,
  val theirsSize: Long
)

enum class ResolutionChoice { KEEP_OURS, TAKE_THEIRS, SKIP }

data class ResolveRequest(val repoId: Long, val choices: Map<String, ResolutionChoice>)

sealed interface ResolveResult {
  /** 所有 non-SKIP 文件已 commit 并 push 成功。若 [remainingSkipped] > 0，状态仍保留 `PAUSED_CONFLICT`。 */
  data class Success(
    val committedFiles: Int,
    val pushOk: Boolean,
    val remainingSkipped: Int,
  ) : ResolveResult
  data class PartialFailure(val failedPaths: List<String>, val reason: SyncErrorKind) : ResolveResult
  data class Failure(val reason: SyncErrorKind) : ResolveResult
}

class ResolveConflictUseCase @Inject constructor(
  private val repoBindingRepository: RepoBindingRepository,
  private val conflictRepository: ConflictRepository,      // 新增：封装 checkout --ours/--theirs + add + commit
  private val gitRepository: GitRepository,                // 既有（沿用迭代 1/2）：用于 push
  private val credentialRepository: CredentialRepository,  // 既有：取 identity + PAT（SSH 走 GitRepository 内部分派）
  private val clearConflictPauseUseCase: ClearConflictPauseUseCase, // 新增（见下）
  private val diagnosticsLogger: DiagnosticsLogger         // 既有
) {
  suspend operator fun invoke(req: ResolveRequest): ResolveResult
}
```

**契约约束（R9 接口扩展对齐）——修订于 v1.1**：

- `ConflictRepository` 是**全新**接口（Domain 层），不替换任何既有接口，与 `GitRepository` 并存；实现在 Data 层封装 JGit `CheckoutCommand.Stage.OURS/THEIRS + AddCommand + CommitCommand`，遵守 P6（JGit 原生类型不出 Data）。
- `ClearConflictPauseUseCase`（Domain 层）是**全新**用例，实现为薄包装：
  ```kotlin
  class ClearConflictPauseUseCase @Inject constructor(
      private val syncLogRepository: SyncLogRepository,   // 既有
      private val clock: Clock,
  ) {
      suspend operator fun invoke(repoId: Long, logId: Long, conflictClass: ConflictClass?) {
          syncLogRepository.pauseAndFinish(
              repoId = repoId,
              logId = logId,
              state = SyncState.IDLE,                      // 从 PAUSED_CONFLICT 跳回 IDLE
              result = SyncResult.CONFLICT_RESOLVED,       // 新增枚举值（§6.1 追加 Migration 不涉及 enum，只是源码常量）
              endedAt = Instant.now(clock),
              conflictClass = conflictClass,
          )
      }
  }
  ```
  **与 `ResumeFromPauseUseCase` 的语义区分**（v1.0 旧版用 "`SyncStateRepository.clearConflictPause` vs `resumeFromPause`" 表达的是同一区分，但挂载点错位；v1.1 用 UseCase 表达）：
  - `ResumeFromPauseUseCase`：用户点"恢复同步"按钮的通用路径，无 commit 语义，适用于 `PAUSED_AUTH / PAUSED_FS / BROKEN`，直接把 `syncState` 写回 `IDLE`；
  - `ClearConflictPauseUseCase`：冲突解决成功后由 `ResolveConflictUseCase` 内部调用，附带 `SyncLog.result = CONFLICT_RESOLVED` 审计行，仅适用于 `PAUSED_CONFLICT`；
  - 两者不互相替换；`PAUSED_CONFLICT` 状态下用户仍可在首页点"恢复同步"兜底（但不会留 `CONFLICT_RESOLVED` 审计行）。
- 新增 `SyncResult.CONFLICT_RESOLVED` 枚举值（现有 enum 位于 `domain/model/SyncResult.kt`，追加一条即可，无 DB 迁移——列是 `TEXT`，enum `.name` 写入）。
- 新异常 `ConflictResolutionFailedException(paths: List<String>, cause: Throwable)` 并入 `JGitExceptionSanitizer` 白名单（R8）。
- 认证分派统一由 `GitRepositoryImpl.push` 在 Data 层内部根据 `binding.authType` 自动分派（P0-1 修订：**`GitRepository.push(binding, username, pat)` 签名不变；SSH 场景 `pat` 传 `CharArray(0)` 占位，Data 层识别 `binding.authType == "SSH"` 时忽略 `pat` 改用 `SshdSessionFactory`**）——见 §4.4.2。

**执行顺序**（全流程幂等，中断可重入；Skip × Push 失败真值表见步骤 7）：

1. **预检**：读取 `syncLogRepository.loadRepoState(repoId).syncState`；非 `PAUSED_CONFLICT` → `Failure(SyncErrorKind.INVALID_STATE)`；
2. **启动审计日志**：`syncLogRepository.startLog(repoId, SyncTrigger.MANUAL, now) -> logId`（与用户手动操作一致的 trigger）；
3. **逐文件 stage**：对 `choices` 中 `KEEP_OURS / TAKE_THEIRS` 的路径，调用 `conflictRepository.checkoutStage(repoId, path, stage)`（内部 JGit `CheckoutCommand.setStage(OURS|THEIRS)` + `AddCommand`）；`SKIP` 的路径不动；任意路径失败 → 立即回滚（记录已处理路径，不清理 index）→ `PartialFailure(failedPaths, SyncErrorKind.Unknown)`，状态保持 `PAUSED_CONFLICT`；
4. **Commit**：若有任何 non-SKIP 文件被 stage，调用 `conflictRepository.commitResolved(repoId, message, author)`（message 走**独立硬编码模板**：`"SimplyGit: resolved {N} conflicts (ours={X}, theirs={Y}{, skipped={S}})"`——**不走** `SyncPolicyModel.commitMessageTemplate`，因为这是用户意图而非静默同步，P1-6 闭环）；author 从 `credentialRepository.snapshotIdentity()` 读取；
5. **Push**：调用 `gitRepository.push(binding, identity.username, pat)`；PAT 走 `credentialRepository.loadPatOnce()`，SSH 场景走 CharArray(0) 占位（认证分派在 Data 层）；`finally` 块清 PAT；
6. **状态收尾（真值表，P1-4 闭环）**：
   | committedFiles | pushOk | remainingSkipped | 状态处理 | ResolveResult |
   |---|---|---|---|---|
   | > 0 | ✅ | 0 | `ClearConflictPauseUseCase` → `IDLE` + `CONFLICT_RESOLVED` | `Success(committedFiles, pushOk=true, remainingSkipped=0)` |
   | > 0 | ✅ | > 0 | **保持 `PAUSED_CONFLICT`**（仍有未解决文件）；补写 `SyncLog.result = CONFLICT_RESOLVED`（局部）`errorMsg="remainingSkipped=N"` | `Success(committedFiles, pushOk=true, remainingSkipped=N)` |
   | > 0 | ❌ | any | **保持 `PAUSED_CONFLICT`**（本地 commit 已落，push 待重试）；`SyncLog.result = NETWORK_ERR` / sanitized kind | `Success(committedFiles, pushOk=false, remainingSkipped=N)`；UI 提示"已本地解决，推送失败，请稍后重试" |
   | 0 | — | > 0（全 SKIP） | 状态不变（用户取消意图） | `Failure(SyncErrorKind.INVALID_STATE)` |
7. **诊断日志**：`diagnosticsLogger.logInfo("conflict_resolved", "ours=X theirs=Y skipped=S pushOk=…")`。

#### 4.3.2 Presentation 层

`ConflictResolveScreen` 路由：`conflict/{repoId}`（路径参数，通过 `MainActivity.Routes.CONFLICT` 注册，见 §5.1）。

入口（三处一致，R10 合规）：
1. 冲突通知点击 → `NotificationPublisherImpl.publishConflict` 的 `navKey = NAV_CONFLICT`，`MainActivity.SimplygitNavHost` 的 `LaunchedEffect` 识别后 `navController.navigate("conflict/$repoId")`；
2. `HomeScreen` 仓库卡片 `SyncStateBanner` 在 `PAUSED_CONFLICT` 状态下新增 `OutlinedButton("解决冲突")`，与既有"恢复同步"按钮并排（`HomeScreen.kt` 的 `SyncStateBanner` composable 内 `when (bound.syncState) { PAUSED_CONFLICT -> Row { ResolveConflictsButton(); ResumeSyncButton() } }`）；
3. `PAUSED_CONFLICT` 通知 + 首页 badge 双触达（延续 R10）。

UI：
- 顶部 AppBar：标题"解决冲突 (N 个文件)"；
- `LazyColumn` 每项：文件路径 + 类型标 (TEXT/BINARY/DELETE_VS_MODIFY) + 选择器 (Ours / Theirs / Skip) + "预览差异"链接（导航到 `DiffScreen` source=`OURS_VS_THEIRS`）；
- 底部固定按钮："提交所有选择"（若存在 SKIP，二次确认弹窗列出"将保留 N 个冲突未解决"）；
- 提交中 → 进度指示 + 禁止返回；
- 成功（无 skipped）→ Toast "已解决 N 个冲突并推送，自动同步已恢复" + 返回首页；
- 部分成功（push 失败或有 skipped）→ 展示失败/跳过列表 + "重试推送"按钮；页面保持在 `ConflictResolveScreen`。

### 4.4 P3.4 SSH Key 支持

#### 4.4.1 Data 层

**P0-4 修订**：`CredentialRepository` / `EncryptedCredentialDataSource` / `Credential` 类**全部保持现状不改动**——继续承载 PAT（单仓 N4 下 key = `github_pat`）。SSH 走**全新独立的仓储链路**，与 PAT 完全解耦。

新增 `SshKeyRepository`（Domain 层接口）：

```kotlin
interface SshKeyRepository {
  /** 生成 ed25519 密钥对，passphrase 可空（空 = 不加密 openssh private）。 */
  suspend fun generate(passphrase: CharArray?): SshKeyPair
  /** 导入用户粘贴/文件选择的 OpenSSH 格式私钥。 */
  suspend fun import(privateKeyOpenssh: CharArray, passphrase: CharArray?): SshKeyPair
  /** 导出公钥（供用户贴到 GitHub Deploy Keys）。 */
  suspend fun exportPublic(keyId: String): String
  /** 列出已有密钥（索引，不含私钥）。 */
  fun observeIndex(): Flow<List<SshKeyIndexEntry>>
  /** 删除前必须先检查引用（`Repository.auth_ref` 指向该 keyId 即为被使用）。 */
  suspend fun delete(keyId: String): DeleteSshKeyOutcome
}

/** 私钥内存中的短生命对象——遵守 R3 三件套：非 data class、CharArray、禁止进入 UiState/Log。 */
class SshKeyPair(
  val keyId: String,             // "ssh_<uuid>"
  val publicKeyOpenssh: String,  // 公钥明文可公开
  val fingerprintSha256: String, // "SHA256:..."
  private val privateKeyRef: CharArray,
) {
  fun privateKeyCopy(): CharArray = privateKeyRef.copyOf()
  fun wipe() { java.util.Arrays.fill(privateKeyRef, '\u0000') }
  override fun toString(): String = "SshKeyPair(keyId=$keyId, fingerprint=$fingerprintSha256, private=***)"
}

data class SshKeyIndexEntry(val keyId: String, val fingerprintSha256: String, val createdAt: Long)

sealed interface DeleteSshKeyOutcome {
  data object Deleted : DeleteSshKeyOutcome
  data class InUse(val byRepoIds: List<Long>) : DeleteSshKeyOutcome
}
```

新增 `SshKeyDataSource`（Data 层，包内可见）：
- 私钥密文走独立的 **`EncryptedFile`**（`androidx.security:security-crypto` 的 `EncryptedFile`，与现有 `EncryptedSharedPreferences` 解耦）；文件落在 App 私有目录 `filesDir/ssh/<keyId>.enc`；key-per-file 的 master key 复用 `MasterKey.Builder(context).setKeyScheme(AES256_GCM).build()`；
- 密钥索引（`List<SshKeyIndexEntry>`）存 `DataStore<Preferences>` 的 `ssh_key_index`（JSON 序列化），与现有 `preferences/` 体系保持一致；
- 实现 SSH 密钥生成使用 Apache MINA SSHD `KeyPairGenerator("Ed25519", "SunEC")`（MINA SSHD 2.13 已内置）或直接 `SecurityUtils.getKeyPairGenerator("Ed25519")`；序列化为 OpenSSH private key format（`OpenSSHKeyPairResourceWriter`）；
- 敏感缓冲区：一切 `CharArray` 使用后立即 `Arrays.fill('\u0000')`（R3）；
- 错误：非 OpenSSH 格式（如 `.ppk`）→ `SshKeyFormatException`，并入 `JGitExceptionSanitizer` 白名单。

`SshPassphraseCache`（**P1-3 闭环**）：

```kotlin
@Singleton
class SshPassphraseCache @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,   // 注入 Application 级 scope，R7 合规
) {
    private val cached = ConcurrentHashMap<String /*keyId*/, CharArray>()

    fun put(keyId: String, passphrase: CharArray) {
        cached[keyId]?.let { java.util.Arrays.fill(it, '\u0000') }
        cached[keyId] = passphrase.copyOf()
        appScope.launch {                            // **不绑 UI，R7 / P4 规避**
            kotlinx.coroutines.delay(TTL)
            cached.remove(keyId)?.let { java.util.Arrays.fill(it, '\u0000') }
        }
    }

    fun get(keyId: String): CharArray? = cached[keyId]?.copyOf()

    fun clear() {
        cached.values.forEach { java.util.Arrays.fill(it, '\u0000') }
        cached.clear()
    }

    companion object { val TTL = java.time.Duration.ofMinutes(10) }
}
```

进程终止即清零（JVM 内存自然丢失），重启后需重新输入 passphrase——符合"每次同步用户配置为无 passphrase 或 10 min 内临时缓存"的需求语义。

#### 4.4.2 SSH 传输层与认证分派（P0-1 闭环）

**关键修订**：v1.0 引用的 `PullRepository / PushRepository / CloneRepository` 在代码中**不存在**；现状是统一的 `GitRepository`（位于 `domain/repository/GitRepository.kt`）+ `GitRepositoryImpl`（`data/git/GitRepositoryImpl.kt`）+ `JGitDataSource`（`data/git/JGitDataSource.kt`）。本迭代**不新增接口**，认证分派下沉到 **`JGitDataSource`** 的现有 `clone/pull/push/pullAndClassify` 方法内部：

```kotlin
// data/git/JGitDataSource.kt — 修改现有方法（签名不变）
class JGitDataSource @Inject constructor(
    /* 既有依赖 */
    private val sshSessionFactoryProvider: GitSshSessionFactoryProvider,  // 新注入（P3.4）
    private val repoBindingRepository: RepoBindingRepository,              // 新注入：用于读 authType/authRef
    /* ... */
) {
    suspend fun push(localDir: File, username: String, pat: CharArray): Result<Unit> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val pushCmd = git.push()
                applyAuth(pushCmd, username, pat)   // ← 新增统一分派
                pushCmd.call().forEach { /* 原有错误处理不变 */ }
            }
        }.mapException(sanitizer)
    }

    /** 按当前 binding 的 authType 装配 TransportCommand 的认证方式。 */
    private suspend fun applyAuth(cmd: TransportCommand<*, *>, username: String, pat: CharArray) {
        val binding = repoBindingRepository.currentOrNull() ?: error("no binding")
        when (binding.authType) {
            "PAT" -> cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(username, pat))
            "SSH" -> cmd.setTransportConfigCallback { transport ->
                (transport as? org.eclipse.jgit.transport.SshTransport)
                    ?.sshSessionFactory = sshSessionFactoryProvider.buildFactory(binding.id, binding.authRef)
            }
            else -> error("unknown authType=${binding.authType}")
        }
    }
}
```

**R9 接口扩展对齐（v1.1）**：
- `GitRepository` / `GitRepositoryImpl` 的方法签名**完全不变**（仍为 `clone/pull/push(binding, username, pat: CharArray): GitOpResult`）；SSH 场景由 `HomeViewModel` / `ResolveConflictUseCase` 调用处传入 `CharArray(0)` 占位，Data 层内部识别 `binding.authType` 后忽略 `pat`；
- `JGitDataSource` 的方法签名**也不变**，但**构造参数新增**两个依赖（`GitSshSessionFactoryProvider` / `RepoBindingRepository`）——Data 层内部变动，对 Domain / UI 透明；
- `RepoBinding`（`domain/model/RepoBinding.kt`）**增加 `authType: String` 字段**（默认 "PAT"），并在 `RepoBindingRepositoryImpl.toBindingOrNull()` 里从 `RepositoryEntity.authType` 读取；详见 §6.1。

`GitSshSessionFactoryProvider`（Data 层）：

```kotlin
class GitSshSessionFactoryProvider @Inject constructor(
  private val sshKeyDataSource: SshKeyDataSource,
  private val passphraseCache: SshPassphraseCache,
  @ApplicationContext private val context: Context,
) {
  fun buildFactory(repoId: Long, keyId: String): SshdSessionFactory =
    SshdSessionFactoryBuilder()
      .setPreferredAuthentications("publickey")
      .setHomeDirectory(File(context.filesDir, "ssh"))
      .setKeyPasswordProvider(
        // MINA SSHD 2.13 KeyPasswordProvider#getPassword 签名返回 String（非 CharArray）
        // P1-5 闭环：String 不可清零是 MINA SSHD 的 API 约束，缓解措施：
        //   (a) String 仅存在于 SSHD 内部 SessionContext 作用域，方法返回后即可 GC；
        //   (b) 我方侧的 cache 层仍使用 CharArray；String 是在 provider 回调内一次性转换。
        { _session, _resourceKey, _attempt ->
          passphraseCache.get(keyId)?.let { arr ->
            try { String(arr) } finally { java.util.Arrays.fill(arr, '\u0000') }
          }
        }
      )
      .setServerKeyDatabase { _, _ -> TofuServerKeyDatabase(File(context.filesDir, "ssh/known_hosts")) }
      .build(null)
}
```

**known_hosts 策略（安全关键）**：
- 首次连接（TOFU）：抛 `SshHostKeyFirstConnectException(fingerprint)`；UI 层捕获后弹窗 "首次连接 github.com，Host Key 指纹：SHA256:xxx，请核对后确认" → 用户点"确认"才把指纹写入 `known_hosts` 并重试；
- 之后连接：指纹不匹配 → 抛 `SshHostKeyChangedException(expected, actual)`，`JGitDataSource` 捕获 → sanitizer 归类为 `SyncErrorKind.Auth` → `RunSyncUseCase` 落 `PAUSED_AUTH`；
- `known_hosts` 位于 App 私有目录 `filesDir/ssh/known_hosts`，不可被其他 App 读取（`Context.MODE_PRIVATE`）。

#### 4.4.3 Presentation 层（P0-2 闭环）

**UI 挂载点改为现有 Screen 扩展**，不新建 `RepoDetailScreen / BindRepoScreen`：

- **SSH 密钥管理入口**：新建 `SshKeyScreen`（`ui/ssh/SshKeyScreen.kt`），路由 `ssh_keys`。入口：`HomeScreen` 顶部 AppBar 的 overflow 菜单新增一项"SSH 密钥"；也可通过 `SyncPolicyScreen` 跳转（`SyncPolicyScreen` 里新增一行"SSH 密钥（高级）"点击跳）。
- **认证方式切换**：现状绑定仓库的入口是 `HomeScreen` 内的"保存仓库"表单（迭代 1 既有字段：Vault URI、Remote URL、PAT）。v1.1 修订——`HomeScreen` 绑定表单**新增一行 Radio：`认证方式：PAT | SSH`**：
  - 选 PAT：显示既有 "PAT" 输入框；
  - 选 SSH：隐藏 PAT 框，显示 "SSH Key" 下拉选择（来自 `SshKeyRepository.observeIndex()`）；若列表为空，展示"请先在 SSH 密钥页生成或导入"链接；保存时写 `RepositoryEntity.authType = "SSH"` + `authRef = "ssh_<keyId>"`；
- **PAUSED_CONFLICT 入口**：`HomeScreen.SyncStateBanner` composable 在 `PAUSED_CONFLICT` 分支增加 `OutlinedButton("解决冲突")`，点击 `navController.navigate("conflict/${bound.repoId}")`；与现有"恢复同步"按钮并排。
- **冲突解决页**：新建 `ConflictResolveScreen`（`ui/conflict/ConflictResolveScreen.kt`），路由 `conflict/{repoId}`（见 §5.1 更新）；
- **目录浏览页**：新建 `RepoBrowserScreen`（`ui/browser/RepoBrowserScreen.kt`），路由 `browser/{repoId}`；入口：`HomeScreen` 仓库卡片右侧新增 `IconButton(Icons.Default.Folder, onClick = onBrowseRepo)`；
- **Diff 页**：新建 `DiffScreen`（`ui/diff/DiffScreen.kt`），路由 `diff/{repoId}/{encodedPath}?source={…}`。

**`SshKeyScreen` 内容**：
- 首屏展示"生成新密钥" / "导入已有私钥" 两个主 CTA；
- 生成 → 询问是否设置 passphrase（默认不设置）→ 生成成功后展示**公钥全文** + "复制到剪贴板" + "导出到文件" + "前往 GitHub Deploy Keys"深链；
- 导入 → SAF `ACTION_OPEN_DOCUMENT` 选私钥文件 → 校验 OpenSSH 格式 → 存储；
- 密钥列表展示 `keyId` + fingerprint + 创建日期 + "删除"按钮（删除前调用 `SshKeyRepository.delete(keyId)`，若返回 `InUse(byRepoIds)` → 弹窗"该密钥被 N 个仓库使用，请先解绑"）。

### 4.5 LLM Harness 设计

不适用：本迭代不涉及 Prompt / Skill / Agent 注入内容的修改。

### 4.6 AndroidManifest 改动清单（R5）

```xml
<!-- 无新增权限。SSH 使用既有 INTERNET；SAF 导入私钥使用既有文档选择器 -->
<!-- 无新增 Activity/Service/Provider -->
```

**FLAG_SECURE**：现状 `MainActivity.onCreate` 已对**整个 Window** 设置 `FLAG_SECURE`（见 `ui/MainActivity.kt:49-52`），`SshKeyScreen` / `ConflictResolveScreen` / `DiffScreen` 天然继承保护——**无需额外代码改动**。v1.0 原文"`FLAG_SECURE` 应用于 `SshKeyScreen`"属重复声明，v1.1 删除该冗余条款。

### 4.7 依赖引入四件套（R4）

`gradle/libs.versions.toml`：

```toml
[versions]
jgit = "6.10.0.202406032230-r"  # 与迭代 1 保持一致；SSH 依赖需同版本
sshd = "2.13.2"

[libraries]
jgit-ssh-apache = { module = "org.eclipse.jgit:org.eclipse.jgit.ssh.apache", version.ref = "jgit" }
# jgit-ssh-apache 传递依赖 sshd-core / sshd-common，不单列
```

`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation(libs.jgit)
    implementation(libs.jgit.ssh.apache)  // 新增
    // 排除 bcprov 冲突（延续迭代 1 策略）
    configurations.implementation {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
}
```

`compileOptions` 已在迭代 1 开启 `isCoreLibraryDesugaringEnabled`，无需新增。

## 5. 信息架构与交互

### 5.1 UI 结构（v1.1 修订：挂接到现有 Screen，不新建 RepoDetailScreen/BindRepoScreen）

现有 NavHost（`ui/MainActivity.kt` 中 `SimplygitNavHost`）路由集 `home / policy / audit / audit/{logId}`。本迭代扩展为：

```
NavHost
  ├─ home                     HomeScreen（既有，扩展三处挂载点）
  │     ├─ 仓库卡片右侧按钮 "浏览" → navigate("browser/{repoId}")     P3.1 入口
  │     ├─ 绑定表单新增 Radio "认证方式: PAT | SSH"                    P3.4 入口
  │     ├─ PAUSED_CONFLICT banner 新增按钮 "解决冲突"                   P3.3 入口
  │     │     → navigate("conflict/{repoId}")
  │     └─ AppBar overflow 新增 "SSH 密钥" → navigate("ssh_keys")      P3.4 入口
  ├─ policy                   SyncPolicyScreen（既有，不改）
  ├─ audit / audit/{logId}    SyncAuditScreen（既有，不改）
  ├─ browser/{repoId}         RepoBrowserScreen（新建，P3.1）
  │     └─ 文件点击 → navigate("diff/{repoId}/{encodedPath}?source=WORKING_VS_HEAD")
  ├─ diff/{repoId}/{encodedPath}?source=…  DiffScreen（新建，P3.2）
  ├─ conflict/{repoId}        ConflictResolveScreen（新建，P3.3）
  │     └─ "预览差异" → navigate("diff/{repoId}/{encodedPath}?source=OURS_VS_THEIRS")
  └─ ssh_keys                 SshKeyScreen（新建，P3.4）
```

`MainActivity.Routes` 常量扩充（私有 object，现状见 `ui/MainActivity.kt:75-81`）：

```kotlin
private object Routes {
    const val HOME = "home"
    const val POLICY = "policy"
    const val AUDIT = "audit"
    const val AUDIT_DETAIL = "audit/{logId}"
    fun auditDetail(id: Long) = "audit/$id"
    // v1.1 新增
    const val BROWSER = "browser/{repoId}"
    fun browser(id: Long) = "browser/$id"
    const val DIFF = "diff/{repoId}/{encodedPath}?source={source}"
    fun diff(id: Long, encodedPath: String, source: String) = "diff/$id/$encodedPath?source=$source"
    const val CONFLICT = "conflict/{repoId}"
    fun conflict(id: Long) = "conflict/$id"
    const val SSH_KEYS = "ssh_keys"
}
```

**通知 deep-link（P0-5 闭环）**：现状 `NotificationPublisherImpl.EXTRA_NAV` 常量集 `NAV_AUDIT / NAV_RESUME`（见 `notification/NotificationPublisherImpl.kt:148-150`）。v1.1 扩展：

```kotlin
// notification/NotificationPublisherImpl.kt —— 新增常量 + 改派 publishConflict
const val NAV_CONFLICT: String = "conflict"            // 新增
const val EXTRA_REPO_ID: String = "repo_id"            // 新增：冲突页需要 repoId

override fun publishConflict(repoId: Long, kind: ConflictClass) {
    notify(
        id = notifId(NotifCategory.CONFLICT, repoId),
        channelId = NotificationChannels.CHANNEL_SYNC_ALERT,
        title = context.getString(R.string.notif_conflict_title),
        body = context.getString(R.string.notif_conflict_body, kind.displayName()),
        navKey = NAV_CONFLICT,                          // v1.0 为 NAV_AUDIT，v1.1 改派
        navRepoId = repoId,                             // 通过 Intent extra 透传
    )
}
```

`MainActivity.onCreate` 取 `pendingNav` 时**同时取 `repoId`**（现状只取 `EXTRA_NAV`，见 `ui/MainActivity.kt:54`），`SimplygitNavHost` 的 `LaunchedEffect` 新增分支：

```kotlin
androidx.compose.runtime.LaunchedEffect(pendingNav) {
    when (pendingNav) {
        NotificationPublisherImpl.NAV_AUDIT -> navController.navigate(Routes.AUDIT)
        NotificationPublisherImpl.NAV_RESUME -> Unit
        NotificationPublisherImpl.NAV_CONFLICT -> {     // v1.1 新增
            val repoId = pendingNavRepoId ?: return@LaunchedEffect
            navController.navigate(Routes.conflict(repoId))
        }
    }
    if (pendingNav != null) onNavConsumed()
}
```

**R10 三处入口一致性**（延续迭代 2）：`PAUSED_CONFLICT` 状态的"解决冲突"入口在①首页 `SyncStateBanner` 按钮、②通知点击跳转、③（可选）首页仓库卡片下方 badge 点击，三处保持可见；"恢复同步"按钮作为兜底路径依然保留。

### 5.2 用户流程

**流程 A：冲突通知 → 解决 → 同步恢复**
1. WorkManager Worker 遇冲突 → 通知"仓库 X 有 N 个冲突待解决" → 用户点击；
2. 跳转 `ConflictResolveScreen`，列出 N 个文件；
3. 用户逐项选择 Ours/Theirs，可选"预览差异"进入 `DiffScreen`（source=OURS_VS_THEIRS）；
4. 点"提交所有选择" → 二次确认 → 执行 `ResolveConflictUseCase`；
5. 成功 → Toast + 返回首页 + 仓库卡片状态变为 `IDLE`；
6. 失败 → 保留在 `ConflictResolveScreen` + 提示。

**流程 B：SSH 密钥首次配置（v1.1 修订：入口改到 HomeScreen）**
1. 用户在 `HomeScreen` AppBar overflow 点击 "SSH 密钥" → `navController.navigate("ssh_keys")`；
2. `SshKeyScreen` 点"生成新密钥" → 展示公钥 → 用户复制到 GitHub Deploy Keys；
3. 返回 `HomeScreen` 绑定表单 → 选择"认证方式: SSH" → 下拉选择刚生成的 keyId → 填写 SSH URL（`git@github.com:user/repo.git`） → 保存（写 `RepositoryEntity.authType="SSH"` + `authRef="ssh_<keyId>"`）；
4. 点"Clone"（既有按钮） → 首次连接抛 `SshHostKeyFirstConnectException(fingerprint)` → UI 捕获 → TOFU 确认弹窗 → 写入 known_hosts → 自动重试 clone 成功。

## 6. 技术实现

### 6.1 数据模型

#### Room 迁移 v2 → v3（v1.1 修订：追加 `auth_type` ALTER）

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    // (a) 新建 file_tree_cache 表
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS `file_tree_cache` (
        `repo_id` INTEGER NOT NULL,
        `path` TEXT NOT NULL,
        `parent_path` TEXT NOT NULL,
        `type` TEXT NOT NULL,
        `git_status` TEXT NOT NULL,
        `size` INTEGER NOT NULL,
        `last_modified` INTEGER NOT NULL,
        PRIMARY KEY(`repo_id`, `path`),
        FOREIGN KEY(`repo_id`) REFERENCES `repository`(`id`) ON DELETE CASCADE
      )
    """.trimIndent())
    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_file_tree_cache_repo` ON `file_tree_cache` (`repo_id`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_file_tree_cache_parent` ON `file_tree_cache` (`repo_id`, `parent_path`)")

    // (b) v1.1 / P0-3 闭环：为既有 repository 表增列 auth_type，默认 PAT（保向后兼容）
    db.execSQL("ALTER TABLE `repository` ADD COLUMN `auth_type` TEXT NOT NULL DEFAULT 'PAT'")
  }
}
```

**`RepositoryEntity` 扩字段**（现状 `data/sync/RepositoryEntity.kt:33-47`）：

```kotlin
data class RepositoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val remoteUrl: String,
    @ColumnInfo(name = "auth_type") val authType: String = "PAT",  // v1.1 新增
    val authRef: String,              // 既有；PAT 时恒为 "github_pat"，SSH 时为 "ssh_<keyId>"
    val localTreeUri: String,
    val localAbsPath: String?,
    val defaultBranch: String,
    val syncPolicyId: Long,
    val syncState: String,
    val lastSyncAt: Long?,
    val lastSyncResult: String?,
    val createdAt: Long,
)
```

**`RepoBinding` 扩字段**（现状 `domain/model/RepoBinding.kt:10-15`）：

```kotlin
data class RepoBinding(
    val treeUri: String,
    val localAbsPath: String,
    val remoteUrl: String,
    val id: Long = 0L,
    val authType: String = "PAT",     // v1.1 新增
    val authRef: String = "github_pat" // v1.1 新增（SSH 时为 "ssh_<keyId>"）
)
```

**`SyncResult` 扩枚举**（现状 `domain/model/SyncResult.kt:4-13`）：

```kotlin
enum class SyncResult {
    OK,
    CONFLICT,
    CONFLICT_RESOLVED,   // v1.1 新增：冲突解决成功审计
    NETWORK_ERR,
    AUTH_ERR,
    FS_ERR,
    ABORTED,
    SKIPPED_DEBOUNCE,
    SKIPPED_PAUSED,
}
```

> 该列是 `TEXT`（存 enum `.name`），不需要 DB 迁移；但需要 `SyncAuditDetailScreen` 的显示映射表（`string.xml`）增加 `sync_result_conflict_resolved` 条目。

#### DataStore 键位扩展（v1.1 修订：不改 PAT 键位）

```
EncryptedSharedPreferences（既有，迭代 1 起，单仓 N4）：
  github_pat          -- 既有 PAT 密文（ESP AES-256-GCM 自动处理）
  github_username     -- 既有
  github_email        -- 既有
  ---（v1.1 不做 `cred_<repoId>` 改造；PAT 在单仓场景下无需按 repoId 分桶）

EncryptedFile（新增，P3.4 专用）：
  filesDir/ssh/<keyId>.enc       -- SSH 私钥密文（OpenSSH private key format）
  filesDir/ssh/known_hosts       -- TOFU host key 数据库（明文 OpenSSH 格式）

DataStore<Preferences>（既有）：
  ssh_key_index       -- v1.1 新增：JSON List<{keyId, fingerprintSha256, createdAt}>
  ssh_passphrase_<keyId>  -- v1.1 可选：仅当用户开启"记住 passphrase"时写入；非敏感默认不存盘，运行时走 SshPassphraseCache
```

**R3 敏感凭证三件套**：`SshKeyPair` 类（§4.4.1 非 data class + CharArray + 手写 toString redacted）、`SshPassphraseCache`（CharArray + Application scope 清理）全部遵守"无 data class + CharArray + 禁止进入 UiState/Log"；其中 MINA SSHD `KeyPasswordProvider` 回调必须把 `CharArray` 转 `String` 是 API 约束（P1-5 详细说明），**这是 R3 的显式豁免点**：String 仅在 provider 方法内一次性构造并立即返回 SSHD 内部，不进入我方缓存、不落日志。

### 6.2 接口定义（v1.1 修订：全部挂载点对齐现状）

| 层 | 接口 / 类 | 新增/修改 | R9 对齐说明 |
|----|----------|----------|-------------|
| Domain | `FileTreeRepository` | 新增 | 全新接口，无签名冲突 |
| Domain | `DiffRepository` | 新增 | 全新接口；含 `sealed DiffOutcome` |
| Domain | `ConflictRepository` | 新增 | 全新接口；实现在 Data 层封装 `CheckoutCommand` + `CommitCommand`（P6） |
| Domain | `SshKeyRepository` | 新增 | 全新接口；与 `CredentialRepository` 解耦（P0-4） |
| Domain | `CredentialRepository` | **不变** | 现状方法 `observe / snapshotIdentity / save / loadPatOnce / clear` 保持原样；SSH 走独立的 `SshKeyRepository`，不扩展此接口 |
| Domain | `SyncLogRepository` | **不变** | `updateSyncState` / `pauseAndFinish` 既有；`ClearConflictPauseUseCase` 以调用方身份包装，不改接口 |
| Domain | `GitRepository` | **不变** | `clone/pull/push/commitAll/pullAndClassify/commitAllIfDirty` 方法签名完全保持（SPEC §6.2 Iter 2）；SSH 分派在 `JGitDataSource` 内部完成 |
| Domain | `RepoBindingRepository` | **不变** | 签名不变；`RepoBinding` 数据类增加 `authType / authRef` 字段（§6.1） |
| Domain | `ResolveConflictUseCase` | 新增 | — |
| Domain | `ClearConflictPauseUseCase` | 新增 | 包装 `SyncLogRepository.pauseAndFinish`，写 `SyncResult.CONFLICT_RESOLVED` + `SyncState.IDLE`；与 `ResumeFromPauseUseCase` 语义并存 |
| Data | `FileTreeRepositoryImpl` | 新增 | — |
| Data | `DiffRepositoryImpl` | 新增 | P6：JGit 原生类型不出 Data |
| Data | `ConflictRepositoryImpl` | 新增 | 同上 |
| Data | `FileTreeCacheDao` / `FileTreeCacheEntity` | 新增 | Room v3 |
| Data | `SshKeyRepositoryImpl` | 新增 | — |
| Data | `SshKeyDataSource` | 新增 | `EncryptedFile` 持久化；与 `EncryptedCredentialDataSource` 解耦 |
| Data | `SshPassphraseCache` | 新增 | `@Singleton` + `@ApplicationScope CoroutineScope`（P1-3 / R7） |
| Data | `GitSshSessionFactoryProvider` | 新增 | 封装 `SshdSessionFactory` 构建 |
| Data | `TofuServerKeyDatabase` | 新增 | `known_hosts` 持久化；抛 `SshHostKeyFirstConnectException` / `SshHostKeyChangedException` |
| Data | `JGitDataSource` | **修改（内部）** | 注入新增的 `GitSshSessionFactoryProvider` + `RepoBindingRepository`；方法签名（`clone/pull/push/pullAndClassify`）**不变**；内部 `applyAuth()` 根据 `binding.authType` 分派 |
| Data | `GitRepositoryImpl` | **不变** | 透传到 `JGitDataSource`，无需修改 |
| Data | `RepositoryEntity` | **修改** | 追加 `authType` 字段（§6.1） |
| Data | `RepoBindingRepositoryImpl` | **修改（内部）** | `toBindingOrNull()` 读 `authType / authRef` 填入 `RepoBinding`；接口签名不变 |
| Runtime | `GitSyncWorker` | **修改（内部）** | `RunSyncOutcome.Ok` 分支 `runCatching { fileTreeRepository.rescan(binding.id) }`；失败发 Info 日志不影响 `Result.success()` |
| UI | `HomeScreen` | **修改** | 仓库卡片加"浏览"按钮；`SyncStateBanner` `PAUSED_CONFLICT` 分支加"解决冲突"按钮；绑定表单加"认证方式"Radio；AppBar overflow 加"SSH 密钥" |
| UI | `MainActivity` + `SimplygitNavHost` | **修改** | `Routes` 增加 `BROWSER / DIFF / CONFLICT / SSH_KEYS`；`LaunchedEffect` 增加 `NAV_CONFLICT` 分支；`onCreate` 取 `EXTRA_REPO_ID` |
| UI | `NotificationPublisherImpl` | **修改** | 常量增加 `NAV_CONFLICT / EXTRA_REPO_ID`；`publishConflict` 改派 |
| UI | `RepoBrowserScreen` / `DiffScreen` / `ConflictResolveScreen` / `SshKeyScreen` | 新增 | 各自 ViewModel + UiState |

**新异常**（全部并入 `JGitExceptionSanitizer` 白名单，对齐 R8）：
- `ConflictResolutionFailedException(paths: List<String>, cause: Throwable)` —— 分类为 `SyncErrorKind.Unknown`
- `SshKeyFormatException(cause: Throwable)` —— 分类为 `SyncErrorKind.Unknown`
- `SshHostKeyChangedException(expected: String, actual: String)` —— 分类为 `SyncErrorKind.Auth`
- `SshHostKeyFirstConnectException(fingerprint: String)` —— **非错误**，`JGitDataSource` 捕获后不进 sanitizer，改为返回 `Result.failure(this)` 由 UI 层识别并弹 TOFU 确认框

**`ResolveResult.Success` 字段（P1-1 闭环）**：已在 §4.3.1 明确为 `(committedFiles: Int, pushOk: Boolean, remainingSkipped: Int)`；**不引入** `PushOutcome` 类型。

### 6.3 跨层字段传递矩阵

| 字段 | Renderer State | Domain UseCase | Data Repository | Runner/Engine (JGit) |
|------|---------------|----------------|-----------------|----------------------|
| `FileTreeNode.path` | `RepoBrowserUiState.currentEntries[n].path` 用于 key / 导航 | `FileTreeRepository.listChildren()` 返回 | `file_tree_cache.path` 列读取 | SAF `DocumentFile.getName()` / `git.status()` 路径 |
| `FileTreeNode.gitStatus` | 目录树行左侧圆点颜色 | 同上 | `file_tree_cache.git_status` 列 | `Status.getModified()/getUntracked()/getConflicting()` 集合分派 |
| `FileTreeNode.aggregatedStatus` | 父目录圆点颜色 | Presentation 层由 Repository 层预聚合填入 | 扫描器一次计算写入 | — |
| `DiffLine.kind` | 行背景色（ADDED/REMOVED/CONTEXT） | `DiffRepository.diff()` 返回的 `DiffOutcome.Full/Truncated.lines` | `DiffFormatter` 输出解析 | `DiffEntry` + `FileHeader` → `HunkHeader` + `EditList` |
| `DiffOutcome.Truncated.totalLines` | 横幅文案"共约 XX 行" | 同上 | `DiffFormatter` 解析时计数 + 粗估 | `RawText.size()` |
| `ResolveRequest.choices[path]` | `ConflictResolveUiState.selections` ViewModel 状态 | `ResolveConflictUseCase` 入参 | `CheckoutCommand.setStage(OURS/THEIRS)` 参数 | JGit `CheckoutCommand` |
| `ResolveResult.Success.committedFiles` | Toast "已解决 N 个冲突" | `ResolveConflictUseCase.invoke()` 返回 | `conflictRepository.commitResolved()` 返回的 staged 数 | `CommitCommand` |
| `ResolveResult.Success.pushOk` | 成功 Toast / 部分失败对话框 | 同上 | `GitRepository.push()` 返回 `GitOpResult.Success`/`Failure`（透传至 `pushOk = true/false`） | `git.push()` |
| `ResolveResult.Success.remainingSkipped` | UI "剩余 N 个冲突待处理"文案 | `ResolveRequest.choices` 中 `SKIP` 计数 | — | — |
| `SshKeyPair.publicKeyOpenssh` | `SshKeyScreen` 公钥展示文本 | `SshKeyRepository.generate()/import()` 返回 | 写入 `ssh_key_index`（JSON 索引），公钥部分由 `SshKeyDataSource.exportPublic()` 按 keyId 读取对应 `EncryptedFile` + 解密后提取 | MINA SSHD `OpenSSHKeyPairResourceWriter.writePublicKey()` |
| `SshKeyPair.keyId` | 仓库绑定页下拉 keyId 选项；`RepositoryEntity.authRef` 赋值 | 同上 | 写入 `filesDir/ssh/<keyId>.enc` + `ssh_key_index` | — |
| `RepoBinding.authType` | `HomeScreen` 绑定表单 Radio 选中态 | `BindRepoUseCase` 入参（新增参数） | `repository.auth_type` 列（v1.1 新增） | `JGitDataSource.applyAuth()` 分派 `TransportCommand` |
| `RepoBinding.authRef` | 无 UI 展示（内部；PAT 时恒 "github_pat"，SSH 时 "ssh_<keyId>"） | `BindRepoUseCase` 入参 | `repository.authRef` 列（既有） | `GitSshSessionFactoryProvider.buildFactory(repoId, authRef)` 按 keyId 加载私钥 |
| `TofuServerKeyDatabase.fingerprint` | 首次连接确认弹窗文案 | `CloneRepoUseCase` 捕获 `SshHostKeyFirstConnectException`，将 fingerprint 透传回 UI；UI 确认后调用 `SshKeyRepository.acceptHostKey(host, fingerprint)` 并重试 | `TofuServerKeyDatabase.accept()` 实现 | MINA SSHD `ServerKeyDatabase.ModifiedServerKeyAcceptor` 接口 |
| `SyncResult.CONFLICT_RESOLVED` | `SyncAuditScreen` 行显示 "✓ 冲突已解决"（新增 string 资源 `sync_result_conflict_resolved`） | `ClearConflictPauseUseCase` 写入 | `sync_log.result` 列（TEXT） | — |
| `GitSyncWorker` → `FileTreeRepository.rescan` | `RepoBrowserScreen` 下一次打开看到最新数据 | `GitSyncWorker.doWork()` 的 `RunSyncOutcome.Ok` 分支 `runCatching` 调用 | `file_tree_cache` 表 upsert | `git.status()` + SAF `DocumentFile.listFiles()` |

### 6.4 性能预算

| 场景 | 目标 | 测量方法 |
|------|------|---------|
| 目录树首屏渲染（10k 文件仓库） | ≤ 500 ms | `Trace.beginSection("tree_first_frame")` + `Systrace` |
| 目录树滚动帧率 | ≥ 55 FPS | `FrameMetrics` 统计 P90 |
| Diff 解析（50k 行文件触发降级） | Truncated 返回 ≤ 1.5 s | `System.nanoTime()` 差值 + 单元测试 |
| SSH clone（GitHub 50MB 仓库，Wi-Fi） | 首包 ≤ PAT × 1.5 | 自动化测试脚本 + `SyncLog.duration_ms` |
| APK 体积增量 | ≤ 3.5 MB（release shrink） | `./gradlew :app:bundleRelease` AAB 对比 |

## 7. 验收标准

### 7.1 功能验收

1. **F1 目录树（P3.1）**：绑定一个 ≥ 10,000 文件的 Git 仓库（可用测试 fixture 自动生成）；进入 `RepoBrowserScreen`：
   - 首屏在 500 ms 内渲染出根目录一级；
   - 展开任一目录能看到 Git 状态彩色圆点；
   - 父目录包含 CONFLICT 文件时，父目录圆点为红色；
   - 下拉刷新能触发 rescan 并更新。
2. **F2 Diff 视图（P3.2）**：
   - 修改某文本文件后从目录树进入 `DiffScreen`，正确展示增删行；
   - 构造一个 > 10,000 行 diff 的文件，`DiffScreen` 自动降级展示前 5000 行 + 横幅；
   - 修改一个二进制文件（如 PNG）后进入，展示 "二进制文件"提示；
   - Diff 渲染失败（权限丢失）时，UI 展示 sanitized 文案，**Logcat 无堆栈**（R8 验收）。
3. **F3 冲突解决（P3.3）**：
   - 人为构造 3 个文本冲突 + 1 个二进制冲突 + 1 个 delete-vs-modify；
   - 从通知进入 `ConflictResolveScreen`，列表正确分类；
   - 对其中 3 个选 Ours、1 个选 Theirs、1 个 Skip → 提交 → 二次确认弹窗正确列出 Skip；
   - 确认后：4 个文件落 commit，push 成功，`SyncState = IDLE`，`SyncLog` 落一条 `CONFLICT_RESOLVED`；
   - 下一次 WorkManager 周期同步正常执行（不再因 PAUSED_CONFLICT 跳过）——**覆盖"可执行性"非仅 UI 可见**（G8）。
4. **F4 SSH Key 生成与绑定（P3.4）**：
   - 在 `SshKeyScreen` 生成 ed25519 密钥对（不带 passphrase）；
   - 公钥全文可复制，fingerprint 展示正确（与 `ssh-keygen -l -f pub` 一致）；
   - 将公钥加到 GitHub Deploy Keys 后，**在 `HomeScreen` 绑定表单选"认证方式: SSH"** + 下拉选择该 keyId + `git@github.com:...` URL；
   - 首次 clone 触发 TOFU 确认弹窗，确认后 clone 成功；
   - 第二次 pull 不再弹窗，沉默完成；
   - 手动篡改 `known_hosts` 后触发 pull，得到 `PAUSED_AUTH` 状态 + 通知"Host Key 变更"。
5. **F5 密钥管理**：
   - 导入 OpenSSH 格式私钥（带/不带 passphrase）成功；
   - 尝试导入 PuTTY 格式 `.ppk` 文件 → 得到 sanitized 文案"不支持的私钥格式"；
   - 尝试删除正在被某仓库使用的 keyId → UI 提示"该密钥被 N 个仓库使用，请先解绑"。

### 7.2 非功能验收

1. **NF1 性能**：10k 文件仓库目录树滚动 P90 帧时间 ≤ 18 ms；50k 行 Diff Truncated 解析 ≤ 1.5 s。
2. **NF2 APK 体积**：release AAB 增量 ≤ 3.5 MB。
3. **NF3 凭证安全（延续 R3 验收）**：
   - ADB `run-as` 进入 data 目录，SSH 私钥文件内容无法直接读取（EncryptedFile 密文）；
   - Logcat 无任何 `-----BEGIN OPENSSH PRIVATE KEY-----` 字符串；
   - `SshKeyPair.toString()` 返回不含私钥内容。
4. **NF4 Doze / 后台兼容**：SSH clone 过程中息屏进入 Doze，clone 继续完成或按 WorkManager Expedited 降级处理，不崩溃。
5. **NF5 一致性**：`PAUSED_CONFLICT` 状态下，①`HomeScreen` 仓库卡片的 `SyncStateBanner`、②`NotificationPublisherImpl.publishConflict` 点击跳转、③通知栏 badge 三处均能通过"解决冲突"入口到达 `ConflictResolveScreen`——延续 R10 验收规则（不再引用已不存在的 `RepoDetailScreen`）。

## 8. 风险与缓解

1. **R-1 SSH MINA SSHD 与 Android Keystore 的 SecurityProvider 冲突**：MINA SSHD 默认使用 BouncyCastle，Android 已有内置 BC 裁剪版可能冲突。
   - **缓解**：参考迭代 1 的 `exclude(bcprov-jdk15on)` 策略；在 CI 集成测试中加一个"SSH clone 冒烟测试"。
2. **R-2 10k 文件目录树扫描阻塞主线程**：`DocumentFile.listFiles()` 在 SAF 下较慢。
   - **缓解**：扫描全程 `Dispatchers.IO`；UI 层只订阅 `observe()` 不等待 `rescan()`；扫描中展示进度 banner。
3. **R-3 Diff 对超大二进制 / 编码非 UTF-8 文件崩溃**：`RawText` 对非 UTF-8 可能抛异常。
   - **缓解**：`DiffRepositoryImpl` 捕获并返回 `DiffOutcome.Binary` 或 `DiffOutcome.Failed(reason = EncodingUnsupported)`；UI 走 R8 sanitizer 文案。
4. **R-4 冲突解决 Push 失败导致状态机不一致**：用户解决完 commit 已落、push 失败。
   - **缓解**：§4.3.1 步骤 7 已设计"保留 PAUSED_CONFLICT + 本地 commit 保留"策略；UI 区分展示"已本地解决，推送失败"；下次 resume 仍走同一路径。
5. **R-5 TOFU 策略被用户误拒**：首次连接用户点"拒绝" → known_hosts 为空。
   - **缓解**：拒绝不写入 known_hosts；下次还会弹窗；UI 在 SSH 状态页提供"重置已知主机"按钮。
6. **R-6 APK 体积接近上限**：若 MINA SSHD 实测超 3.5 MB。
   - **缓解**：开启 R8 / ProGuard 并配置 `keep` 规则；若仍超，评估仅保留 sshd-common + sshd-core 最小子集，排除 sftp。

## 9. 设计自检

> 提交评审前逐项确认。

| # | 检查项 | 状态 |
|---|--------|------|
| G1 | 生态调研已完成，确认无可直接复用的成熟方案（或已说明为何不复用） | ✅（§2.2） |
| G2 | 方案对比的关键差异有量化数字 | ✅（§3.1.1 APK +2.8 MB / CVE 数；§3.1.2 帧率 / 内存；§3.1.3 操作耗时） |
| G3 | 详细设计的每个 Phase/子任务都能回溯到 §2.3 的某个目标（无冗余设计） | ✅（P3.1→G1 / P3.2→G2 / P3.3→G3 / P3.4→G4；NFR 4.6→G5） |
| G4 | §2.4 非目标未在详细设计中出现（无范围蔓延） | ✅（无行级 chunk / 无 Diff 编辑 / 无多分支 / 无 Hardware Key） |
| G5 | 新增实体的分类逻辑在数据源自描述，非消费端硬编码 | ✅（`GitFileStatus` enum 驻留 Domain；`FileType` 驻留 Domain；`ConflictFileKind` 由 Data 分类器产出） |
| G6 | 新增字段单一语义（分类字段 ≠ 展示字段） | ✅（`gitStatus` 与 `aggregatedStatus` 独立字段；`DiffOutcome` sealed 子类独立表达 Full/Truncated/Binary/Failed，不用 flag） |
| G7 | 新增用户可见功能已填写跨层字段传递矩阵（§6.3） | ✅（§6.3 覆盖目录树 / Diff / 冲突解决 / SSH） |
| G8 | 验收标准覆盖"可执行性"，非仅 UI 可见 | ✅（F3 验收包含"下一次周期同步正常执行"；F4 包含 push 实际成功；NF5 对齐 R10） |
| H1 | 【Harness】Prompt 注入是声明式约束，非过程化步骤编排 | N/A（§4.5 无 Harness 改动） |
| H2 | 【Harness】工具描述要么给完整调用模板，要么只引用不命名（无"半知注入"） | N/A |
| H3 | 【Harness】同一能力调用语义只在一处定义 | N/A |
| H4 | 【Harness】新增注入已评估 Token 量级及与既有注入的叠加影响 | N/A |
| H5 | 【Harness】架构决策有行业标准/学术基准/评测数据支撑 | N/A |
| S1 | 【选型】对比表含"数据权威性"和"供应链安全"列 | ✅（§3.1.1 SSH 对比表） |
| S2 | 【选型】已检查 MCP 注册表 / SkillHub / GitHub 是否有成熟集成 | ✅（§2.2 调研）|

**追加自检（项目内沉淀规则对齐）**：

| # | 检查项 | 状态 |
|---|--------|------|
| R3 | 敏感凭证建模三件套（SSH 私钥 / passphrase） | ✅（§4.4.1 + §6.1；MINA SSHD `KeyPasswordProvider` 的 `String` 返回值作为显式豁免点已声明） |
| R4 | 外部依赖四件套（jgit-ssh-apache） | ✅（§4.7） |
| R5 | AndroidManifest 改动清单独立成节 | ✅（§4.6，无新增权限亦明确声明；`FLAG_SECURE` 现状已全局，不重复声明） |
| R6 | JGit 原生类型字段追溯到持久化层 | ✅（§6.3 TOFU fingerprint / `authType` / `CONFLICT_RESOLVED` 均追溯到列） |
| R7 | 安全清理协程不绑 UI 生命周期（SshPassphraseCache） | ✅（§4.4.1 注入 `@ApplicationScope CoroutineScope`，不走 `rememberCoroutineScope`） |
| R8 | 异常显式分派 + sanitizer 兜底（4 个新异常已入白名单） | ✅（§6.2 新异常表，含 `SyncErrorKind` 分类） |
| R9 | 接口扩展与既有链路显式对齐 | ✅（§6.2 表格**以现有代码实际接口为基点**：`GitRepository` / `SyncLogRepository` / `CredentialRepository` 全部标"不变"；新增接口独立命名 `SshKeyRepository / ConflictRepository / FileTreeRepository / DiffRepository`；包装用例 `ClearConflictPauseUseCase` 显式声明与 `ResumeFromPauseUseCase` 并存语义） |
| R10 | 状态机/UI 跨章节一致性（PAUSED_CONFLICT 三处一致入口） | ✅（§5.1 三入口 + NF5 验收） |
| R11 | Spec 挂载点必须以现有代码为基点 | ✅（v1.1 修订：已逐项核对 `HomeScreen / MainActivity / SimplygitNavHost / Routes / NotificationPublisherImpl / GitSyncWorker / GitRepository / SyncLogRepository / RepositoryEntity / RepoBinding / SyncResult` 均为代码中实际存在的符号；新建 Screen/Entity/UseCase 已显式纳入本迭代任务量） |
| P6 | JGit 原生类型不出 Data 层 | ✅（§6.2 `DiffRepositoryImpl` / `ConflictRepositoryImpl` / `FileTreeRepositoryImpl` 均封装在 Data） |
| P7 | 异步挂载点显式调用 + 幂等 | ✅（§4.1.1 "触发时机"列出 4 个显式调用点，均 upsert 幂等；`GitSyncWorker` 末尾调用用 `runCatching` 包裹不影响 Worker 结果） |

## 10. 变更记录

| 日期 | 版本 | 变更人 | 变更说明 |
|---|---|---|---|
| 2026-05-02 | v1.0 | alexjhwen | 初版：对齐总方案 §9 Phase 3 四子项（目录树 / Diff / 冲突解决 / SSH），含 3 组方案对比（SSH 传输层 / 目录树范式 / 冲突 UI）、Room v2→v3 迁移、10 项 R/P 规则自检。 |
| 2026-05-03 | v1.1 | alexjhwen | 闭环首轮评审 5 P0 + 7 P1 + 1 P2 共 13 个问题。核心改动：①挂载点全部回归现有代码（移除虚构的 `PullRepository/PushRepository/CloneRepository/SyncStateRepository/PushOutcome/RepoDetailScreen/BindRepoScreen/Credential.Pat`），`GitRepository` 签名不变、认证分派下沉到 `JGitDataSource.applyAuth()`；②冲突解决路径补 `ClearConflictPauseUseCase`（包装 `SyncLogRepository.pauseAndFinish`）+ `SyncResult.CONFLICT_RESOLVED` 枚举值；③Room Migration v2→v3 追加 `ALTER repository ADD COLUMN auth_type`；④`RepoBinding` 扩 `authType/authRef` 字段；⑤`CredentialRepository` 不扩展，SSH 走独立 `SshKeyRepository + SshKeyDataSource`（`EncryptedFile`）；⑥通知跳转链路新增 `NAV_CONFLICT + EXTRA_REPO_ID`，`MainActivity.Routes` 增加 `BROWSER/DIFF/CONFLICT/SSH_KEYS`；⑦Skip × Push 组合 6 行真值表；⑧`SshPassphraseCache` 注入 `@ApplicationScope`；⑨MINA SSHD `KeyPasswordProvider` 返回 `String` 作为 R3 显式豁免点；⑩冲突解决 commit 独立硬编码模板；⑪删除 `DiffSource.COMMIT_VS_COMMIT` 冗余枚举值。追加 R11 自检。 |
| 2026-05-03 | v1.2 | alexjhwen | feat(iter3)：冲突可视化 4 个 Phase 全量落地（P3.1 目录树 + P3.2 Diff + P3.3 冲突解决 + P3.4 SSH Key）。新增/修改 ~35 Kt + Room v2→v3 Migration（file_tree_cache + repository.auth_type）+ jgit-ssh-apache 2.13.2 依赖。`./gradlew :app:assembleDebug` 通过、lint 0 诊断。落地偏差（已记录于源码注释）：①`FileTreeRepositoryImpl` 目录枚举用 `java.io.File.listFiles()` 而非 `DocumentFile.listFiles()`，与项目现状 JGit 走 `localAbsPath` 一致，入口仍由 `SafPathResolver.hasPersistedPermission` 把关；②P3.4 `GitSshSessionFactoryProvider.buildFactory()` 采用骨架集成（SSHD `SshdSessionFactoryBuilder` 基础配置 + TOFU `known_hosts` 持久化），完整的 `KeyPasswordProvider` + 加密私钥解密读取留待后续集成测试验证；③SSH 密钥生成 `encodePrivateKeyPem` 使用 PKCS#8 PEM（JCE 通用），用户如需切换至 OpenSSH 标准 block 格式可 `ssh-keygen -p -m OpenSSH`，私钥字节层等价。 |
| 2026-05-03 | v1.3 | alexjhwen | fix(iter3)：闭环 CR Review 发现的 11 个问题（4 高 / 4 中 / 3 低），SSH 链路从骨架升级为端到端可用。核心改动：①`SyncErrorKind` 新增 `InvalidState`，`ResolveConflictUseCase` 的预检/全-SKIP 路径从 `Unknown` 改为 `InvalidState`，`RunSyncUseCase` 将 `InvalidState` 与 `Unknown` 归并为 `ABORTED`；②`JGitExceptionSanitizer.classifyKind` 显式白名单 `SshHostKeyChangedException → Auth`、`ConflictResolutionFailedException/SshKeyFormatException → Unknown`；③`mapException` 让 `SshHostKeyFirstConnectException` **直接透传**（不进 sanitizer），`GitOpPreflight.afterOp` 同样放行；④`FileTreeRepositoryImpl.toNode()` 对 DIR 节点强制 `gitStatus = CLEAN`，`aggregatedStatus` 独占聚合语义，闭环 G6 单一语义字段；⑤`ClearConflictPauseUseCase` 内调用 `fileTreeRepository.rescan()`（`runCatching` 吞异常），闭环 §4.1.1 触发时机 #4；⑥`JGitDataSource.applyAuth` 对未知 `authType` 抛 `IllegalStateException` 而非静默回退 PAT；⑦`HomeScreen` AppBar actions 改为 overflow `DropdownMenu`，对齐 §4.4.3 "overflow 菜单新增 SSH 密钥"；⑧`HomeScreen` 绑定表单新增 `AuthModeSection`（PAT/SSH Radio + SSH Key 下拉），`HomeViewModel.SubmitAuthType` + `SaveAuthTypeUseCase` + `RepoBindingRepository.saveAuth` + `RepositoryDao.updateAuth` 打通写入链路；⑨`SshKeyRepositoryImpl` 用 MINA SSHD `OpenSSHKeyPairResourceWriter.writePublicKey/writePrivateKey` 生成合法 OpenSSH 公钥与 `-----BEGIN OPENSSH PRIVATE KEY-----` 私钥，指纹走 `KeyUtils.getFingerPrint(SHA-256)`，导入走 `SecurityUtils.loadKeyPairIdentities` 并重新派生公钥（导入后 UI 展示的是真公钥而非中文提示字符串）；⑩`GitSshSessionFactoryProvider.buildFactory` 装配 `setDefaultKeysProvider`（解密加载私钥为 `KeyPair`）+ `setKeyPasswordProvider`（`CachedKeyPasswordProvider` 从 `SshPassphraseCache` 读 `char[]`）+ `setServerKeyDatabase`（`TofuServerKeyDatabase` 实现 JGit `ServerKeyDatabase` 接口，首次连接抛 `SshHostKeyFirstConnectException`，指纹不匹配抛 `SshHostKeyChangedException`）；⑪SSH key 生成去掉 RSA fallback，`SecurityUtils.getKeyPairGenerator("EdDSA")` 失败直接抛 `IllegalStateException`（避免静默降级误导 §3.1.1 选型）；⑫新增 TOFU UI 交互：`HomeUiState.TofuPrompt` + `HomeViewModel.confirmTofu/dismissTofuPrompt`，`HomeScreen` 弹 `AlertDialog` 显示 host + 指纹，用户确认后 `SshKeyRepository.acceptHostKey` 持久化到 `known_hosts` 并自动重试 op；⑬`SshKeyRepository` 扩 `acceptHostKey / resetKnownHosts` 接口。`./gradlew :app:assembleDebug` 通过、lint 0 诊断；SSH 实际认证链路从"骨架"转为"可用"，F4/F5 验收标准回归可执行。 |
