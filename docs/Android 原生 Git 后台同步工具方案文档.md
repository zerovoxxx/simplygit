# **Android 原生 Git 后台同步工具方案文档**

## **1. 产品概述**
### **1.1 核心定位**
一款专注于“静默同步”与“轻量管理”的 Android 原生 Git 客户端。不追求成为全功能的 IDE 辅助工具，而是作为 Obsidian 等本地文件驱动型应用的**后台版本控制引擎**。
### **1.2 核心场景**

* **无感备份与同步**：用户在 Obsidian 中编辑笔记，应用在后台自动将修改 commit 并 push 到远程 GitHub 仓库，同时拉取其他端的更新。  
* **轻量级干预**：当出现同步冲突或需要查看修改历史时，提供基础的目录浏览和直观的文件 Diff 对比功能。

## ---

**2\. 产品设计**

### **2.1 核心功能模块**

| 模块 | 功能描述 | 关键交互 |
| :---- | :---- | :---- |
| **仓库管理** | 本地仓库绑定与远程克隆 | 支持通过 PAT (Personal Access Token) 或 SSH 授权；支持选取系统的现有文件夹（如 Obsidian Vault 目录）初始化为仓库。 |
| **自动化同步** | 后台静默执行 Pull/Commit/Push | 支持设置同步策略（如：APP 启动时拉取、每 X 小时后台同步、检测到文件变更后延时提交）。 |
| **目录管理** | 展现绑定的本地文件夹层级 | 树状列表展示文件目录；标识文件的 Git 状态（未追踪、已修改、已暂存）。 |
| **文件 Diff** | 差异对比与基础冲突处理 | 上下排布的单栏差异视图（适合手机屏幕）；支持展示行级的新增（绿）与删除（红）。 |

### **2.2 核心用户链路**

1. **初始化**：用户授权 GitHub 账号 \-\> 系统文件选择器 (SAF) 授权 Obsidian 目录 \-\> 执行 git init 或 git clone。  
2. **日常运转**：用户离开界面 \-\> 守护进程接管 \-\> 依据策略自动提交与同步。  
3. **冲突介入**：系统发送通知栏提醒“同步遇到冲突” \-\> 用户点击进入 APP \-\> 打开 Diff 视图 \-\> 选择保留本地或远程版本 \-\> 继续同步。

## ---

**3\. 技术架构设计**

### **3.1 总体架构 (MVVM \+ 单向数据流)**

系统采用现代 Android 推荐的清晰分层架构，确保 UI 渲染与底层 Git 引擎解耦。

* **UI Layer (视图层)**：完全使用 Jetpack Compose 构建。通过观察 ViewModel 暴露的 StateFlow 驱动界面刷新。  
* **Presentation Layer (逻辑层)**：ViewModel 负责将用户意图 (Intent) 转化为具体的操作，并持有 UI 状态。  
* **Domain Layer (领域层)**：处理核心业务逻辑，如 Diff 数据解析、同步冲突策略。  
* **Data Layer (数据层)**：包含两部分。  
  * Git Repository：封装 JGit 操作。  
  * File Repository：封装基于 SAF 的系统文件读写操作。

### **3.2 核心技术栈**

* **开发语言**：Kotlin  
* **依赖注入**：Hilt (管理复杂的数据层和领域层对象实例)  
* **并发处理**：Kotlin Coroutines \+ Flow (处理 Git IO 和文件扫描)  
* **本地存储**：DataStore (存储 Token 和同步配置) \+ Room (缓存目录树结构，提升加载性能)  
* **UI 框架**：Jetpack Compose (声明式 UI，高性能列表 LazyColumn)  
* **后台任务**：WorkManager (处理稳定的后台网络请求和文件 I/O)  
* **Git 引擎**：JGit (Java 原生 Git 实现)

## ---

**4\. 关键技术方案实现**

### **4.1 文件权限与系统集成 (SAF)**

由于 Android 11+ 的分区存储限制，这是最高风险点。

* **实现策略**：应用不使用传统的 java.io.File 去硬编码路径。必须通过 Intent.ACTION\_OPEN\_DOCUMENT\_TREE 调起系统文件选择器。  
* **URI 映射**：用户选中 Obsidian 目录后，系统返回一个 Uri。应用通过 DocumentFile.fromTreeUri() 拿到根目录句柄，并使用 contentResolver.takePersistableUriPermission() 固化该权限，避免每次启动都要重新授权。  
* **JGit 适配**：JGit 默认基于 java.io.File 操作。由于我们拿到了该目录的读写权限，且该目录通常位于 /sdcard/Documents 等公共区域，在获取权限后，依然可以尝试将 Uri 转换为绝对路径供 JGit 使用；若系统严格拦截，则需实现一个自定义的 JGit FS 类，通过 ContentResolver 的流来桥接 JGit 的读写请求。

### **4.2 后台自动化同步策略 (WorkManager)**

后台同步需要兼顾电量和系统资源的限制。

* **Worker 设计**：继承 CoroutineWorker 实现 GitSyncWorker，确保 Git 操作在协程上下文中异步执行。  
* **触发条件 (Constraints)**：  
  * 网络类型：NetworkType.CONNECTED  
  * 电池状态：非低电量模式  
* **重试机制**：WorkManager 原生支持指数退避策略 (Exponential Backoff)。如果 Push 失败（如网络抖动），Worker 会自动在 10 分钟、20 分钟后重试。

### **4.3 高性能 Diff 视图渲染**

在移动端展示大量代码的变更，容易导致内存溢出 (OOM) 或界面卡顿。

* **数据提取**：使用 JGit 的 DiffFormatter 和 ByteArrayOutputStream 提取出标准的 unified diff 格式文本。  
* **结构化解析**：在 Domain 层将纯文本的 diff 输出解析为结构化的 List\<DiffLine\> 数据类（包含行号、内容、状态标志：增加/删除/不变）。  
* **按需渲染**：使用 Compose 的 LazyColumn。它只会渲染当前屏幕可见的 DiffLine 节点。通过为不同的状态标志分配不同的 Modifier.background() 颜色，实现流畅的语法高亮和差异对比。

## ---

**5\. 项目演进路线 (Roadmap)**

**Phase 1：MVP 核心链路 (优先跑通技术难点)**

* 实现 GitHub PAT 授权登录。  
* 跑通 SAF 获取本地目录权限。  
* 集成 JGit，实现手动点击触发 Clone、Commit 和 Push。  
* *目标：验证文件读写与 Git 核心协议在现行 Android 系统上的连通性。*

**Phase 2：自动化与静默同步 (核心价值)**

* 引入 WorkManager。  
* 开发同步策略配置界面（间隔时间、网络条件）。  
* 完善状态通知，在同步失败或产生冲突时通过 NotificationManager 推送本地通知。  
* *目标：让用户完全忘记 APP 的存在，实现 Obsidian 数据的无感备份。*

**Phase 3：冲突解决与可视化 (完善体验)**

* 实现目录树 UI TreeView，展示文件本地变更状态。  
* 实现单栏文件 Diff 视图。  
* 实现基础的冲突解决交互（覆盖本地或丢弃本地）。  
* *目标：提供闭环的版本管理体验，无需在手机上切换到其他工具处理冲突。*

*附录：*

[*https://github.com/eclipse-jgit/jgit*](https://github.com/eclipse-jgit/jgit)

