# 迭代 1 Spec：MVP 核心链路

> **文档状态: 评审完成**

## 1. 文档信息

- 文档版本: v1.1
- 作者: alexjhwen
- 日期: 2026-05-01
- 迭代目标: 跑通 GitHub PAT 授权 → SAF 目录授权 → JGit Clone/Pull/Commit/Push 的端到端手动链路，验证 Android 现代存储与 Git 协议的连通性，并确立安全凭证建模基线。
- 前置依赖: 无（首个迭代）
- 与总方案的对齐声明：本迭代严格对齐总方案 §9 Phase 1（P1.1 ~ P1.6），**未引入总方案变更**。P1.5 "手动 Pull + Auto-merge"（仅无冲突场景）在 v1.1 纳入目标 G7；冲突解决/分级策略仍下沉到 Phase 2（N3 保留）。

## 2. 背景与目标

### 2.1 当前状态

当前仓库仅含脚手架（`app/`、默认 `MainActivity` + Compose 主题），无任何 Git、SAF、权限、网络能力。总方案文档确立了 MVVM 四层架构与 Kotlin/Compose/Hilt/WorkManager/JGit/DataStore/Room 技术栈，但尚未落地。

对 Phase 1 而言，三大未验证的技术风险点是：
- **SAF × JGit 的兼容性**：JGit 默认基于 `java.io.File`，而 Android 11+ 分区存储下 Obsidian Vault 目录（如 `/sdcard/Documents/xxx`）能否被 JGit 直接读写，需实测。若失败，需在本迭代识别并预留 `FS` 适配方案的边界（但不在本迭代实现）。
- **PAT 凭证存取**：JGit 的 `UsernamePasswordCredentialsProvider` 与加密 DataStore 的集成路径。
- **网络与大仓 Clone**：Android 主线程限制与 JGit 同步 API 的协程化封装。

### 2.2 生态与行业调研

1. **现有方案**：
   - **MGit / Pocket Git / Termux + git**：社区内已有 Android Git 客户端，但均为"通用 Git IDE"定位，不满足"静默后台同步 Obsidian"的核心差异化目标；且 MGit 已多年未更新（最后提交 2022），不具备 Android 11+ SAF 支持。
   - **JGit**：Eclipse 官方维护的 Java 原生 Git 实现，Android 生态事实标准，被 AndroidStudio/Gerrit 使用，供应链可信；当前稳定版 6.x 支持 Java 8+，兼容 Android API 26+。
   - **libgit2 (JNI)**：性能更好但需维护 NDK 构建，对小团队成本过高。
2. **行业实践**：
   - Obsidian 官方 Git 插件（桌面端）采用 isomorphic-git + Node.js，移动端对应方案是 **Obsidian Git Mobile**（基于 isomorphic-git + Capacitor），但其冲突处理体验差，是本项目想替代的对象。
   - GitHub 官方 Android 客户端不提供原生 Git 同步，仅为 Issue/PR 管理。
3. **调研结论**：**确需自建**。差异化定位（静默后台 + SAF 原生）无现成方案；Git 引擎直接复用 JGit，不自研。凭证管理复用 Jetpack `androidx.security:security-crypto` + DataStore，不自研加密。

### 2.3 本次迭代目标

1. **G1** 支持用户通过 GitHub PAT（Personal Access Token）录入并加密持久化；PAT 在 Domain/Data 层以 `CharArray` 持有，使用后主动清零（对齐总方案 §5.1）。
2. **G2** 支持用户通过 SAF 选取本地目录，并持久化该 URI 权限；完成 `canRead()/canWrite()` 可用性探测（对齐总方案 §4.1）。
3. **G3** 集成 JGit，实现手动点击触发的 **Clone**（远程仓库 → 本地目录）。
4. **G4** 集成 JGit，实现手动点击触发的 **Commit**（扫描工作区变更 → 暂存 → 提交）。
5. **G5** 集成 JGit，实现手动点击触发的 **Push**（本地 main 分支 → 远程）。
6. **G6** 落地 MVVM 四层骨架（UI / Presentation / Domain / Data），为后续迭代提供依赖注入与状态流基线。
7. **G7** 集成 JGit，实现手动点击触发的 **Pull**（`fetch` + `fast-forward / auto-merge`）；仅覆盖**无冲突场景**，对齐总方案 §9 P1.5。

### 2.4 非目标

1. **N1** 不做 WorkManager 后台自动化同步（Phase 2）。
2. **N2** 不做目录树 UI 与 Diff 视图（Phase 3）。
3. **N3** 不做冲突分级与冲突解决交互；Pull 遇到任何冲突（文本行级 / 二进制 / 删改 / force-push）**统一暂停并展示原始错误信息**，冲突分级与解决 UI 推迟到 Phase 2/3（对齐总方案 §4.5）。
4. **N4** 不做 SSH 方式授权，本迭代仅支持 PAT（HTTPS）。
5. **N5** 不做多仓库管理；本迭代只支持绑定**单一**仓库。
6. **N6** 不做 Room 目录缓存；本迭代无需持久化目录结构。
7. **N7** 不预先实现 JGit `FS` 自定义适配层；仅在实测发现 `java.io.File` 路径不可用时记录问题并停在"兼容性验证"结论上（已登记到 `docs/retro/patterns.md` D1）。
8. **N8** 不做增量同步调度与冲突自动处理策略；G7 的 Pull 仅验证无冲突链路的连通性。

## 3. 方案决策

### 3.1 方案对比

#### 3.1.1 Git 引擎选型

| 维度 | 方案 A：JGit（Java 原生） | 方案 B：libgit2 via JNI |
|------|------|------|
| 描述 | Eclipse 官方 Java 实现，Gradle 直接依赖 | C 库通过 JNI 暴露给 Kotlin |
| 集成成本 | 1 行 `implementation "org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r"` | 需自建 CMake/NDK，维护 4 个 ABI |
| Android 兼容 | 需排除 `bcprov` 冲突，其余开箱即用 | 需处理 libssl/zlib 等传递依赖 |
| Clone 性能（100MB 仓库实测参考） | ~12s | ~6s |
| 包体积增量 | +2.4 MB | +4.8 MB（4 ABI splits） |
| **数据权威性** | Eclipse 基金会一手维护，官方 Maven Central | libgit2 官方 + Kotlin 社区二手 JNI 绑定 |
| **供应链安全** | 月活发布，CVE 响应 <14 天；被 AndroidStudio 内置 | 官方 C 库可信，但 JNI 绑定层维护者分散 |
| 优点 | 纯 Java 无 NDK；总方案文档已锁定 | 性能更好 |
| 缺点 | 大仓性能不如 C 实现 | 构建复杂度高，小团队不可承受 |

#### 3.1.2 PAT 存储方案

> 术语统一说明：下表方案 A 指代 `EncryptedSharedPreferences`（简称 **ESP**），由 `androidx.security:security-crypto` 提供。早期方案探索阶段曾考虑将 PAT 密文写入 DataStore Preferences（"EncryptedDataStore"构想），但经核实 `androidx.datastore` 官方无加密 API，Jetpack 官方在 `security-crypto` alpha 阶段明确推荐使用 ESP 承载密文；本迭代直接采用 ESP，不再讨论自拼 EDS 路线。

| 维度 | 方案 A：EncryptedSharedPreferences（ESP） | 方案 B：Android Keystore + 自建加密 |
|------|------|------|
| 描述 | 使用 `androidx.security:security-crypto` 提供的 `EncryptedSharedPreferences` + Jetpack `MasterKey` | 直接用 Keystore 生成 AES key 自行加解密 SharedPreferences/DataStore |
| 代码量 | ~30 行 | ~120 行 |
| 维护成本 | Jetpack 官方封装，升级 Android 版本免维护 | 需自行处理 KeyStore 的 `UserNotAuthenticatedException` 等场景 |
| **数据权威性** | AndroidX 官方库 | 自建 |
| **供应链安全** | Google 一方维护 | 自建代码需自审 |
| 优点 | 快速、合规 | 可控粒度高 |
| 缺点 | `security-crypto` 目前为 alpha（但 Jetpack 官方推荐，生产可用） | 代码膨胀，易出 bug |

#### 3.1.3 JGit 与 SAF 的桥接策略

| 维度 | 方案 A：绝对路径直连 | 方案 B：JGit 自定义 `FS` 适配 `ContentResolver` |
|------|------|------|
| 描述 | 从 SAF Uri 解析出 `/sdcard/...` 绝对路径，直接给 JGit | 实现 `org.eclipse.jgit.util.FS` 子类代理 SAF 流 |
| 实现成本 | 10 行 | ~600 行（需覆盖 40+ `FS` 抽象方法） |
| 适用条件 | 目标目录在 `/sdcard/Documents` 等公共位置 + `takePersistableUriPermission` 生效 | 任意 SAF 目录（包括云盘、私有 provider） |
| 风险 | Android 14+ 可能进一步收紧公共目录直接 IO；且 Obsidian 目录若放在 `Android/data` 下直连会失败 | 工作量极大，且 JGit 部分 API 硬依赖 `File`（如 `PackFile`），可能需重写 |
| 优点 | 开箱即用，验证技术连通性最快 | 通用性强 |
| 缺点 | 兼容性待实测 | Phase 1 无法承载 |

### 3.2 选型结论

- **Git 引擎**：方案 A（JGit），与总方案文档一致；包体、供应链、集成成本全面胜出，性能差距对"笔记级别小仓"可忽略。
- **PAT 存储**：方案 A（`EncryptedSharedPreferences`，ESP），30 行 vs 120 行代码量直接决定 Phase 1 优先采纳官方封装；后续如 `security-crypto` 弃用再迁移。
- **SAF × JGit 桥接**：方案 A（绝对路径直连）— 作为**本迭代技术验证的主路径**。若实测失败，**本迭代即宣告"发现阻塞 → 产出问题报告，推迟到 Phase 2 前做 FS 适配"**，不在 Phase 1 里现写 600 行 `FS` 子类。这是 §2.3 "验证连通性" 目标的直接对应：先试最便宜的路径，验证不通再升级。

对 §2.2 调研结论的呼应：**Git 引擎 = 复用 JGit / 凭证 = 复用 Jetpack / 存储桥接 = 先用最简方案验证，失败再自建**，三条决策均符合"不重复造轮子"原则。

## 4. 详细设计

### 4.1 模块分层与目录结构

> **包名基线**：沿用现有 `com.example.simplygit`（由 `app/build.gradle.kts` 的 `namespace` + `applicationId` 决定）。本迭代**不改包名**，避免引入无关迁移成本；若未来品牌定型需要改为 `com.simplygit`，在后续迭代单独立项。

```
app/src/main/java/com/example/simplygit/
├── SimplyGitApp.kt                     // @HiltAndroidApp
├── di/
│   ├── DataModule.kt                   // DataStore / JGit / EncryptedPrefs 注入
│   └── DispatcherModule.kt             // IO/Default Dispatcher 注入
├── data/
│   ├── credential/
│   │   ├── CredentialDataSource.kt     // EncryptedSharedPreferences 封装
│   │   └── CredentialRepositoryImpl.kt
│   ├── saf/
│   │   ├── SafUriStore.kt              // SAF Uri 持久化（DataStore）
│   │   └── SafPathResolver.kt          // Uri → 绝对路径解析 + canRead/canWrite 探测
│   ├── git/
│   │   ├── JGitDataSource.kt           // JGit 原子封装（clone/pull/add/commit/push）
│   │   ├── JGitExceptionSanitizer.kt   // 异常 message 脱敏（剥离 URL query / Authorization 头）
│   │   └── GitRepositoryImpl.kt        // 对外暴露 Domain 可用接口
│   └── binding/
│       └── RepoBindingRepositoryImpl.kt
├── domain/
│   ├── model/
│   │   ├── Credential.kt               // 非 data class + CharArray（见 §6.1）
│   │   ├── RepoBinding.kt
│   │   └── GitOpResult.kt
│   └── usecase/
│       ├── BindRepoUseCase.kt          // SAF 授权 + 可用性探测 + 远程信息持久化
│       ├── CloneRepoUseCase.kt
│       ├── PullRepoUseCase.kt          // G7 新增
│       ├── CommitLocalUseCase.kt
│       └── PushRepoUseCase.kt
└── ui/
    ├── MainActivity.kt                 // 唯一 Activity（Compose 宿主，启用 FLAG_SECURE）
    ├── theme/                          // 保留现有主题
    └── home/
        ├── HomeScreen.kt               // 单屏五按钮 + 状态栏（含 Pull）
        ├── HomeViewModel.kt            // @HiltViewModel
        └── HomeUiState.kt              // sealed interface
```

### 4.1.1 依赖清单（`libs.versions.toml` 与 `app/build.gradle.kts` 增量）

> 遵循 R4（外部依赖引入四件套）。研发在任一项遗漏时，视为 C2 改动完备性不足。

**`gradle/libs.versions.toml` 增量**：

```toml
[versions]
# 现有：agp / kotlin / coreKtx / composeBom 等保留不动
hilt = "2.51.1"
ksp = "2.0.21-1.0.28"
datastore = "1.1.1"
securityCrypto = "1.1.0-alpha06"
jgit = "6.10.0.202406032230-r"
desugarJdkLibs = "2.0.4"
coroutines = "1.8.1"

[libraries]
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version = "1.2.0" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
jgit = { group = "org.eclipse.jgit", name = "org.eclipse.jgit", version.ref = "jgit" }
desugar-jdk-libs = { group = "com.android.tools", name = "desugar_jdk_libs", version.ref = "desugarJdkLibs" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version = "2.8.4" }

[plugins]
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

**`app/build.gradle.kts` 增量**：

```kotlin
plugins {
    // 现有三项保留
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true   // 新增：与下方 coreLibraryDesugaring(...) 成对出现
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE.md", "META-INF/NOTICE.md",
        )   // JGit 依赖传递的资源冲突规避
    }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.jgit) {
        // JGit 传递依赖 bcprov，与 AOSP 内置 Conscrypt/bcprov 冲突，显式排除
        exclude(group = "org.bouncycastle")
        // JSch（SSH 通道）本迭代不使用，排除以减小包体
        exclude(group = "com.jcraft", module = "jsch")
    }
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
```

### 4.1.2 AndroidManifest 改动清单

> 遵循 R5（Manifest 改动独立成节）。

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 新增：Clone / Pull / Push 的 HTTPS 网络访问 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- 新增：Push 失败时判断网络状态（可选，本迭代仅用于日志提示）-->
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".SimplyGitApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Simplygit">
        <!-- android:name 新增：@HiltAndroidApp 入口 -->
        <!-- android:allowBackup 修改为 false：避免备份机制带走加密 prefs 明文副本 -->
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.Simplygit">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> **研发注意**：`android:allowBackup="false"` 是安全硬性要求（含 PAT），不得为调试方便回退；`SimplyGitApp` 必须标注 `@HiltAndroidApp`，否则 `@AndroidEntryPoint` 注入会失败。

### 4.2 Data 层：凭证存储（CredentialDataSource）

- **存储技术**：`EncryptedSharedPreferences`（ESP），由 `androidx.security:security-crypto:1.1.0-alpha06` 提供；`MasterKey` 使用 `KeyScheme.AES256_GCM`。
- **持久化 Key**：`github_pat`（以 Base64 字符串形式承载 AES-256-GCM 加密后的密文——注意这是 ESP 库内部已完成的加密，应用层写入前不再手动加密）、`github_username`（明文）、`github_email`（明文，默认 `$username@users.noreply.github.com`，用户可改）。
- **接口（仅暴露给 `CredentialRepositoryImpl`）**：
  ```kotlin
  interface CredentialDataSource {
      suspend fun save(username: String, email: String, pat: CharArray)   // 内部 ESP.putString 后立即 pat.fill('\u0000')
      fun observe(): Flow<Credential?>                                    // 仅投影 username/email，不带 pat
      suspend fun loadPatOnce(): CharArray?                               // 仅在执行 Git 操作前取出，用完由调用方立即 fill('\u0000')
      suspend fun clear()
  }
  ```
- **读写约束**：
  - `observe()` **绝不暴露 pat 字段**（仅回放 username/email），避免 `Flow` 订阅链路泄漏。
  - `loadPatOnce()` 每次从 ESP 读出新的 `CharArray` 副本（ESP 内部存 `String`，读出后转 `CharArray`，原 `String` 立刻失去引用；无法 100% 清零 String，但将作用域压到最短）。
  - 调用方拿到的 `CharArray` 必须在 `try/finally` 中 `java.util.Arrays.fill(pat, '\u0000')`。
- **规则**：`CredentialDataSource` 是进程内凭证访问的**唯一入口**，禁止其他模块直连 ESP 取 `github_pat`（对应总方案 §3.2 "凭证读取只能通过 CredentialStore" 强边界）。

### 4.3 Data 层：SAF 目录授权（SafUriStore + SafPathResolver）

- `SafUriStore`：用 DataStore 存 `vault_tree_uri: String`（`Uri.toString()` 结果）。
- 授权入口在 UI 层通过 `ActivityResultContracts.OpenDocumentTree`，回调拿到 Uri 后：
  1. `contentResolver.takePersistableUriPermission(uri, READ | WRITE)`
  2. 调用 `SafPathResolver.tryResolveAbsolutePath(uri)` 做**解析 + 可用性探测**
  3. 探测通过才 `SafUriStore.save(uri)` + `RepoBindingRepository.saveVault(uri, absPath)`
- `SafPathResolver.tryResolveAbsolutePath(treeUri: Uri): ResolveResult`：

  ```kotlin
  sealed interface ResolveResult {
      data class Ok(val absPath: String) : ResolveResult
      data object NotPrimary : ResolveResult       // docId 前缀非 primary:，直接不兼容
      data object NotReadable : ResolveResult      // canRead()/canWrite() 探测失败
  }
  ```

  实现步骤（对齐总方案 §4.1 "启动时用 `File.canRead() && File.canWrite()` 探测可用性；不可用则明确提示"）：
  1. 从 `DocumentsContract.getTreeDocumentId(treeUri)` 解析 `primary:Documents/ObsidianVault`。
  2. 若前缀不是 `primary:`（如 SD 卡、云端 provider），返回 `NotPrimary`。
  3. 拼接 `Environment.getExternalStorageDirectory()` → `/storage/emulated/0/Documents/ObsidianVault`。
  4. `val dir = File(absPath); if (!dir.canRead() || !dir.canWrite()) return NotReadable`。
  5. 通过则 `ResolveResult.Ok(absPath)`。
- **本迭代契约**：`NotPrimary` / `NotReadable` 均视为 §3.2 所述"主路径失败"，UI 层透传错误文案（见 §4.6），不走自建 `FS` 适配（N7）。
- **权限失效检测**（对齐总方案 §4.1）：`BindRepoUseCase` 每次执行前先 `contentResolver.persistedUriPermissions.any { it.uri == treeUri }` 校验；权限被系统回收则返回 `GitOpResult.Failure(op, SafPermissionRevokedException)`，UI 引导用户重新授权。

### 4.4 Data 层：JGit 封装（JGitDataSource）

所有 JGit API 调用强制包裹在 `withContext(ioDispatcher)`，禁止主线程调用。

```kotlin
class JGitDataSource @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val sanitizer: JGitExceptionSanitizer,
) {
    /** Clone：PAT 作为 CharArray 透传，内部构造 CredentialsProvider 后立即 fill */
    suspend fun clone(
        remoteUrl: String,
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<Unit> = withContext(io) {
        runCatching {
            val provider = UsernamePasswordCredentialsProvider(username, pat) // JGit 自行持有 char[] 副本
            Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(localDir)
                .setCredentialsProvider(provider)
                .call()
                .use { /* repository.close() */ }
        }.mapException(sanitizer)
    }

    /** Pull：仅无冲突（fast-forward / auto-merge）场景成功；冲突场景抛 NotMergedException 由上层捕获 */
    suspend fun pull(
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<PullOutcome> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val provider = UsernamePasswordCredentialsProvider(username, pat)
                val result = git.pull()
                    .setCredentialsProvider(provider)
                    .setFastForward(MergeCommand.FastForwardMode.FF)
                    .call()
                if (!result.isSuccessful) {
                    throw PullConflictException(result.mergeResult?.mergeStatus?.name ?: "unknown")
                }
                PullOutcome(
                    commitsPulled = result.fetchResult.trackingRefUpdates.size,
                    mergeStatus = result.mergeResult?.mergeStatus?.name,
                )
            }
        }.mapException(sanitizer)
    }

    /** Commit：author 来自 CredentialRepository.observe() 的 username + email */
    suspend fun commitAll(
        localDir: File,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<ObjectId> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                git.add().addFilepattern(".").call()
                val status = git.status().call()
                if (status.isClean) throw NoChangesException()
                val ident = PersonIdent(authorName, authorEmail)
                git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call().id
            }
        }.mapException(sanitizer)
    }

    suspend fun push(
        localDir: File,
        username: String,
        pat: CharArray,
    ): Result<Unit> = withContext(io) {
        runCatching {
            Git.open(localDir).use { git ->
                val provider = UsernamePasswordCredentialsProvider(username, pat)
                git.push().setCredentialsProvider(provider).call().forEach { pr ->
                    pr.remoteUpdates.forEach { up ->
                        if (up.status != RemoteRefUpdate.Status.OK &&
                            up.status != RemoteRefUpdate.Status.UP_TO_DATE) {
                            throw PushRejectedException(up.status.name, up.message.orEmpty())
                        }
                    }
                }
            }
        }.mapException(sanitizer)
    }
}

data class PullOutcome(val commitsPulled: Int, val mergeStatus: String?)
class PullConflictException(val status: String) : RuntimeException("pull conflict: $status")
class PushRejectedException(val code: String, val msg: String) : RuntimeException("push rejected: $code $msg")
class NoChangesException : RuntimeException("no changes to commit")
class SafPermissionRevokedException : RuntimeException("SAF permission revoked")
```

**凭证生命周期**：
- `pat: CharArray` 仅作方法参数透传；`JGitDataSource` **不在字段或局部变量以外的位置持有** PAT；方法返回前无需清零（JGit 的 `UsernamePasswordCredentialsProvider` 会自行持有 `char[]` 副本直至 GC）。
- **调用方**（UseCase 层）在 `try/finally` 中对传入的 `CharArray` 执行 `Arrays.fill(pat, '\u0000')`（见 §4.5 UseCase 模板）。

**异常脱敏（`JGitExceptionSanitizer`）** — 对齐总方案 §5.1 "禁止把凭证写入 `Log.*` / 异常堆栈"：

```kotlin
class JGitExceptionSanitizer @Inject constructor() {
    /** 将 JGit 抛出的异常 message / cause 链清洗后重新包装。 */
    fun sanitize(t: Throwable): Throwable {
        val cleaned = t.message
            ?.replace(Regex("""https?://[^\s@]+@"""), "https://[redacted]@")  // 清洗 URL 中的 basic auth
            ?.replace(Regex("""token\s*=\s*[A-Za-z0-9_\-]+"""), "token=[redacted]")
            ?.replace(Regex("""Authorization:\s*\S+"""), "Authorization: [redacted]")
            ?.replace(Regex("""gh[pous]_[A-Za-z0-9]{20,}"""), "[redacted-pat]")
            ?.replace(Regex("""github_pat_[A-Za-z0-9_]{20,}"""), "[redacted-pat]")
            ?: t.javaClass.simpleName
        return SanitizedGitException(cleaned, t.javaClass.simpleName)
    }
}

class SanitizedGitException(msg: String, val originalType: String) : RuntimeException(msg)

private fun <T> Result<T>.mapException(s: JGitExceptionSanitizer): Result<T> =
    fold({ Result.success(it) }, { Result.failure(s.sanitize(it)) })
```

- UI 层展示 `SanitizedGitException.message`；原始异常类型名保留在 `originalType` 供本地日志用（日志 Appender 同样走 sanitizer，不落原始 message）。

### 4.5 Presentation 层：HomeViewModel 与状态机

```kotlin
sealed interface HomeUiState {
    data object Idle : HomeUiState
    data class Bound(val treeUri: String, val remoteUrl: String, val username: String) : HomeUiState
    data class Working(val op: GitOp, val startedAt: Long) : HomeUiState
    data class Error(val op: GitOp, val message: String) : HomeUiState    // message 已由 sanitizer 脱敏
}
enum class GitOp { CLONE, PULL, COMMIT, PUSH }
```

- ViewModel 暴露 `val uiState: StateFlow<HomeUiState>` + `fun onIntent(intent: HomeIntent)`。
- Intents：`SubmitCredential(username, email, pat)`、`PickVault(uri)`、`SubmitRemote(url)`、`DoClone`、`DoPull`、`DoCommit(message)`、`DoPush`、`Reset`。
- 任意时刻 `Working` 状态下新 Intent 被 ViewModel 丢弃（单操作串行，Phase 1 不做队列）。
- **UiState 边界**：`Bound` / `Error` 均**不含 `pat` / `email`** 字段；`email` 仅在 Commit 时由 `CredentialRepository.observe()` 现取现用。

**UseCase 模板（PAT 用完必清零）** — 所有涉及 PAT 的 UseCase 统一结构：

```kotlin
class CloneRepoUseCase @Inject constructor(
    private val credRepo: CredentialRepository,
    private val bindingRepo: RepoBindingRepository,
    private val gitRepo: GitRepository,
) {
    suspend operator fun invoke(): GitOpResult {
        val binding = bindingRepo.requireCurrent()
        val pat = credRepo.loadPatOnce() ?: return GitOpResult.Failure(GitOp.CLONE, MissingCredentialException())
        return try {
            gitRepo.clone(binding, pat)
        } finally {
            java.util.Arrays.fill(pat, '\u0000')   // R3 强制
        }
    }
}
```

- `PullRepoUseCase` / `PushRepoUseCase` 结构相同；`CommitLocalUseCase` 不涉及 PAT，但需从 `credRepo.observe().first()` 取 username/email。
- **UseCase 依赖顺序**：`BindRepoUseCase` → Clone / Pull / Commit / Push（后者 4 个需 `RepoBinding` 存在）；`CommitLocalUseCase` 还要求本地已有 `.git`（由 Clone 或用户手动 `git init` 产生，本迭代只覆盖 Clone 路径）。

### 4.6 UI 层：HomeScreen

**Activity 级安全策略（对齐总方案 §5.3 / §5.4）**：

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §5.3 含凭证输入的界面必须启用 FLAG_SECURE，阻止截屏 / 录屏 / 最近任务缩略图泄漏
        window.setFlags(FLAG_SECURE, FLAG_SECURE)
        setContent { SimplygitTheme { HomeScreen() } }
    }
}
```

> 注：本迭代单 Activity 单屏含凭证输入，整窗口加 `FLAG_SECURE`；后续迭代引入多屏时再按"仅凭证输入屏启用"粒度重构。

**剪贴板 60s 自动清空**（仅清理自身写入的内容，避免干扰用户其他剪贴板）：

```kotlin
// 在凭证保存成功后调用
fun scheduleClipboardClear(cm: ClipboardManager, scope: CoroutineScope) {
    val markedLabel = "simplygit-pat"
    scope.launch {
        delay(60_000)
        val current = cm.primaryClip ?: return@launch
        if (current.description?.label == markedLabel) cm.clearPrimaryClip()
    }
}
```

> UI 中提供"清空剪贴板"按钮作为备用手动入口；自动清空仅对本 APP 粘贴过 PAT 的场景生效（通过 `ClipData.newPlainText("simplygit-pat", pat)` 写入时带标签识别）。

**单屏布局**（自上而下）：

1. **凭证区**：PAT 输入框（`VisualTransformation.Password` + `KeyboardOptions(keyboardType = Password)`）+ username 输入框 + email 输入框（默认占位 `$username@users.noreply.github.com`）+ "保存凭证"按钮；已保存时显示 `已绑定 @username`（不显示 email，email 仅在 Commit 时取）。
2. **目录区**：按钮"选择 Vault 目录"（触发 SAF）+ 显示当前绑定目录的绝对路径；解析失败按 `ResolveResult` 分别提示：
   - `NotPrimary`：红字 `"该目录所在存储分区不被 JGit 直连支持，请选择内置存储 /sdcard/Documents 下的目录"`
   - `NotReadable`：红字 `"该目录当前无读写权限（可能位于 Android/data 等受限位置），请选择内置存储 Documents 下的目录"`
3. **远程仓库区**：TextField 输入远程 URL + "保存"按钮。
4. **操作区**：四个按钮 **Clone / Pull / Commit / Push**，`Working` 期间全部禁用。
5. **日志区**：最新一条 `HomeUiState` 的简述（仅 `username` 投影，不含 PAT；错误 message 已经 sanitizer 脱敏）。

**UI 字符串**：所有中文文案通过 `strings.xml` 引用，不在 Composable 内硬编码（对应 NF5 的 `HardcodedText` lint 规则）。

### 4.7 依赖注入骨架（DataModule）

- `@Provides @Singleton fun provideEncryptedPrefs(ctx): SharedPreferences` — 基于 `MasterKey.Builder(ctx).setKeyScheme(AES256_GCM).build()` 构造 `EncryptedSharedPreferences`，承载 `github_pat` / `github_username` / `github_email`。
- `@Provides @Singleton fun provideRepoPrefsDataStore(ctx): DataStore<Preferences>` — 明文 `DataStore` 承载 `vault_tree_uri` / `remote_url`。
- `@Provides @Singleton fun provideJGitExceptionSanitizer(): JGitExceptionSanitizer`。
- `@Binds` 绑定 `CredentialRepository` / `RepoBindingRepository` / `GitRepository` 的实现类。
- **架构说明**：本迭代 **明确选择 `EncryptedSharedPreferences` 承载敏感凭证**，原因：①`androidx.datastore` 原生无加密 API；②Jetpack 官方在 `security-crypto` alpha 阶段明确推荐 ESP 路径；③引入 Tink/DataStore 自拼加密会膨胀到 §3.1.2 的方案 B 水平，违背"复用 Jetpack"原则。非敏感配置继续用 Preferences DataStore，两个存储面互不跨越。

## 5. 信息架构与交互

### 5.1 UI 结构

- **单 Activity**（`MainActivity`）+ **单 Composable**（`HomeScreen`）。
- 无导航图（后续迭代引入 Navigation Compose）。

### 5.2 用户流程

```
首次启动
 → HomeScreen (Idle)
 → 填 PAT + username + email → 保存 → 凭证区显示 "已绑定 @username"；60s 后剪贴板自动清空
 → 点"选择 Vault 目录" → SAF 系统选择器 → 选中目录
    → ResolveResult.Ok → 目录区显示绝对路径
    → NotPrimary / NotReadable → 红字提示 + 保持 Idle
 → 填远程 URL → 保存 → 状态变为 Bound
 → 点 Clone → Working(CLONE) → 成功回 Bound / 失败 Error（已脱敏）
 → 编辑笔记（APP 外）→ 回到 APP
 → 点 Pull → Working(PULL) → 无冲突成功回 Bound；冲突 → Error("pull conflict: CONFLICTING")
 → 点 Commit → 输入 message → Working(COMMIT) → 成功回 Bound / NoChanges → Error
 → 点 Push → Working(PUSH) → 成功回 Bound / Rejected → Error
```

## 6. 技术实现

### 6.1 数据模型

**持久化层（共 2 个存储面）**

| 存储 | 技术 | Key | 类型 | 敏感 |
|------|------|-----|------|------|
| `encrypted_prefs.xml`（应用私有目录） | EncryptedSharedPreferences | `github_pat` | String（ESP 内部已加密存储） | 是 |
| `encrypted_prefs.xml` | EncryptedSharedPreferences | `github_username` | String | 否 |
| `encrypted_prefs.xml` | EncryptedSharedPreferences | `github_email` | String | 否 |
| `repo.preferences_pb` | DataStore Preferences | `vault_tree_uri` | String | 否 |
| `repo.preferences_pb` | DataStore Preferences | `remote_url` | String | 否 |

**Domain 模型（凭证安全建模，对齐总方案 §5.1 / R3 黄金法则）**

```kotlin
/** 凭证对象：非 data class，手写 toString 防泄漏；PAT 以 CharArray 承载，允许主动清零。 */
class Credential(
    val username: String,
    val email: String,
    private val patRef: CharArray,     // 持有 CharArray 副本的引用（非拷贝）
) {
    /** 受控访问入口：仅在 IO 边界调用一次，使用后调用方必须 wipe() */
    fun patCopy(): CharArray = patRef.copyOf()

    /** 清零 PAT 副本；应在 Credential 生命周期结束前调用 */
    fun wipe() { java.util.Arrays.fill(patRef, '\u0000') }

    override fun toString(): String = "Credential(username=$username, email=$email, pat=***)"
    override fun equals(other: Any?): Boolean =
        other is Credential && other.username == username && other.email == email
    override fun hashCode(): Int = username.hashCode() * 31 + email.hashCode()
}

data class RepoBinding(val treeUri: String, val localAbsPath: String, val remoteUrl: String)

sealed interface GitOpResult {
    data object Success : GitOpResult
    data class SuccessWithPayload(val payload: Any) : GitOpResult   // 供 Pull 返回 commitsPulled
    data class Failure(val op: GitOp, val cause: Throwable) : GitOpResult   // cause 必是 SanitizedGitException
}
```

**禁用项（强制约束，CI 可静态检查）**：
- **禁止** `Credential` 改回 `data class`（默认 `toString` 会暴露字段）。
- **禁止** 将 `Credential` 放入 `HomeUiState` 的任一分支（UiState 会被 Compose 快照持久化）。
- **禁止** 在 `Log.*` / `println` 传入 `Credential` 实例或 PAT `CharArray` 的内容。
- **禁止** 在 `toString()` 中暴露 PAT；若新增字段含敏感值，必须覆盖 `toString`。

### 6.2 接口定义

**Repository 接口（Domain 依赖的抽象）**

```kotlin
interface GitRepository {
    /** PAT 以 CharArray 传入，Repository 层不缓存，用完由 UseCase 层 wipe。 */
    suspend fun clone(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun pull(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
    suspend fun commitAll(binding: RepoBinding, message: String, authorName: String, authorEmail: String): GitOpResult
    suspend fun push(binding: RepoBinding, username: String, pat: CharArray): GitOpResult
}

interface CredentialRepository {
    /** 仅投影 username / email，不暴露 PAT。 */
    fun observe(): Flow<CredentialPublicView?>
    /** 保存后立即清零传入的 pat；未传 email 时默认 `$username@users.noreply.github.com`。 */
    suspend fun save(username: String, email: String, pat: CharArray)
    /** 一次性取出 PAT 副本；调用方必须在 try/finally wipe。 */
    suspend fun loadPatOnce(): CharArray?
    suspend fun clear()
}

data class CredentialPublicView(val username: String, val email: String)

interface RepoBindingRepository {
    fun observe(): Flow<RepoBinding?>
    suspend fun requireCurrent(): RepoBinding    // 无绑定时抛 IllegalStateException
    suspend fun saveVault(treeUri: String, absPath: String)
    suspend fun saveRemote(url: String)
}
```

> **关键决策**：`GitRepository` 方法签名**直接接收 `username` + `pat: CharArray`**，而非 `Credential` 整体——目的是让"PAT 生命周期"显式出现在调用链上，研发看到 `CharArray` 参数自然会配对 `try/finally + fill`；若传 `Credential` 整体，容易被当作普通数据对象随手日志化。

### 6.3 跨层字段传递矩阵

| 字段 | UI (Compose) | ViewModel | UseCase | Repository | JGit/SAF |
|------|-------------|-----------|---------|-----------|---------|
| `pat` (CharArray) | `TextField` 密码蒙版；提交后立即 `text = ""` | 从 Intent 接收 `CharArray` → 同步传给 UseCase，不在字段保存 | `loadPatOnce()` → `try { gitRepo.xxx(pat) } finally { fill(pat, '\u0000') }` | 方法参数透传，不在实例字段保留 | `UsernamePasswordCredentialsProvider(username, pat)` |
| `username` | 输入框 + 回显 `已绑定 @username` | `StateFlow<CredentialPublicView?>` 映射 | 参数透传（`authorName`） | 参数透传 | `PersonIdent(name, email)` |
| `email` | 输入框（默认占位 `$username@users.noreply.github.com`） | 保存到 ESP；Commit 时现取 | `authorEmail` 参数 | 参数透传 | `PersonIdent(name, email)` |
| `vault_tree_uri` | SAF 回调接收 `Uri` | 调 `BindRepoUseCase(uri)` | 调 `SafPathResolver` + 权限 take + 写入 `RepoBindingRepository` | `saveVault(uri.toString(), absPath)` | `takePersistableUriPermission(uri, R\|W)` |
| `local_abs_path` | 仅展示 | 从 `RepoBinding` 投影 | `BindRepoUseCase` 内由 `SafPathResolver` 产出 | `RepoBinding` 字段 | `File(absPath)` 给 JGit |
| `remote_url` | 输入框 + 回显 | 参数 | 参数 | `saveRemote` + `RepoBinding` | `Git.cloneRepository().setURI()` |
| `commit_message` | 输入框 | 参数 | 参数 | 参数 | JGit `commit().setMessage()` |
| `commitsPulled` | Pull 成功后日志区显示 `"pulled N commits"` | 从 `GitOpResult.SuccessWithPayload` 投影 | 返回值 | `PullOutcome.commitsPulled` | JGit `PullResult.fetchResult` |
| `pullMergeStatus` | 失败时展示 | 同上 | 同上 | `PullOutcome.mergeStatus` | JGit `MergeResult.MergeStatus` |
| `error_message` | `Error.message`（已脱敏） | 从 `SanitizedGitException.message` 投影 | 不做二次加工 | 由 DataSource 的 sanitizer 包装 | 原始异常 → `JGitExceptionSanitizer.sanitize()` |

**边界过滤点**：
- `pat` **绝不进入 `HomeUiState`**（避免 Compose savedState 持久化 + 最近任务缩略图泄漏；配合 `FLAG_SECURE` 双重保险）。
- `pat` **绝不进入日志**：ViewModel/UseCase/Repository/DataSource 任一层的 `Log.*` 调用传入包含 PAT 的对象视为 CR 必拒。
- `Credential.toString()` 强制 `"Credential(..., pat=***)"`（§6.1 手写覆盖）。
- `Throwable.message` 必须走 `JGitExceptionSanitizer` 脱敏后才能进入 UI 或日志。

## 7. 验收标准

### 7.1 功能验收

1. **A1** 在 `SimplyGitApp` 应用 `@HiltAndroidApp`，点击运行 APP，Home 页可见且不崩溃；`MainActivity` 开启 `FLAG_SECURE`（验证：Android 最近任务中该 APP 截图为黑屏，或系统截图/录屏时提示"应用或组织不允许截屏"）。
2. **A2** 凭证持久化 + 加密落地：
   - (a) 输入 PAT + username + email 保存，杀进程重启后 Home 页仍显示 `已绑定 @username`。
   - (b) 用 `adb shell run-as com.example.simplygit cat shared_prefs/encrypted_prefs.xml`（debug 构建）查看文件内容：不含明文 `ghp_*` / `github_pat_*` 前缀；`github_pat` 条目的 value 为 Base64 密文。
   - (c) 对该 APP 执行 `adb backup` 得到的备份不含 `encrypted_prefs.xml`（`android:allowBackup="false"` 生效）。
3. **A3** 点击"选择 Vault 目录"弹出系统文件选择器，选取 `/sdcard/Documents/` 下任意空目录；成功回调后目录区展示正确绝对路径；**重启 APP 后 URI 权限仍有效**（通过 `contentResolver.persistedUriPermissions` 日志核对）；`canRead()/canWrite()` 均返回 true。
4. **A4** 输入一个 PAT 已授权的 GitHub 私有仓库 URL，点 Clone：
   - (a) `HomeUiState` 从 `Working(CLONE)` 回到 `Bound`。
   - (b) 本地目录出现 `.git` 目录 + 仓库文件。
   - (c) Clone 期间 UI 可响应（按钮禁用但滚动/返回交互不卡顿）；Android Studio Profiler 主线程无单次 > 500ms 的阻塞；无 `Application Not Responding` 弹窗。
5. **A5** 在文件管理器修改任意 `.md` 文件后，APP 内输入 commit message 并点 Commit：
   - (a) 成功回到 `Bound`。
   - (b) 桌面 `git -C <localAbsPath> log --format='%an <%ae> %s' -1` 输出 `author.name = username`、`author.email = email`、`subject = 输入的 message`。
   - (c) 若未修改任何文件直接 Commit，UI 显示 `Error(COMMIT, "no changes to commit")`，不产生空 commit。
6. **A6** 点 Push 成功后，在 GitHub 网页端能看到新 commit 出现在 `main` 分支；若远端已有超前 commit 导致 non-fast-forward，UI 显示 `Error(PUSH, "push rejected: REJECTED_NONFASTFORWARD ...")`。
7. **A7** 选择非 `primary:` 前缀的 SAF 目录（如 SD 卡路径）时目录区红字显示 `NotPrimary` 文案；选择 `Android/data/...` 等受限路径（如可模拟）时显示 `NotReadable` 文案；两种情况均不让用户走到 Clone 步骤。
8. **A8** **（G7 Pull）** 在另一端（桌面）对 `main` push 一个新 commit，APP 内点 Pull：
   - (a) `HomeUiState` 从 `Working(PULL)` 回到 `Bound`。
   - (b) 日志区显示 `"pulled N commits"`（N ≥ 1）。
   - (c) 本地 `git log` 能看到该新 commit；`MergeStatus` 为 `FAST_FORWARD` 或 `MERGED`。
   - (d) 若在本地同文件同行也做了修改再 Pull，UI 显示 `Error(PULL, "pull conflict: CONFLICTING")`（不自动解决；N3 保留）。
9. **A9** **（安全）** 凭证输入后剪贴板若包含 PAT（用户从密码管理器粘贴），60s 后再次查询 `ClipboardManager.primaryClip` 为空（或 label 不再是 `simplygit-pat`）。
10. **A10** **（异常脱敏）** 故意输入错误 PAT 触发 401，`HomeUiState.Error.message` 中不含 `ghp_` / `github_pat_` / `Authorization:` 片段；原始异常类型名可在本地 `logs/` 文件中按类型名定位。

### 7.2 非功能验收

1. **NF1** 冷启动到 Home 页首帧 < 1.5s（Pixel 6a / Android 14 基准；Android Studio Profiler - Startup 采样）。
2. **NF2** Clone 一个 10MB 级仓库 < 15s（WiFi）；全程主线程无 > 500ms 阻塞。
3. **NF3** Gradle 构建 Debug APK 体积 < 15MB（含 JGit；Release 启用 R8 后目标 < 10MB，本迭代不做 Release 验收）。
4. **NF4** `minSdk = 26`，`compileSdk = 36`，`targetSdk = 36`（沿用现状，不降级）；Gradle 构建通过 `./gradlew :app:lintDebug` 无 ERROR 级告警。
5. **NF5** `lint.xml` 启用规则：`NewApi`（默认 error）、`HardcodedText`（UI 字符串必须进 `strings.xml`，error 级）；`detekt` 基线确立（本迭代首建 `detekt-baseline.xml`）。
6. **NF6** **（安全）** 项目全局 grep `"\\bprintln\\b|Log\\.(v|d|i|w|e)\\b"` 对 `Credential` / `pat` 等关键字零命中（CI 静态检查；初版用 `detekt` 自定义规则或 grep 脚本兜底）。

## 8. 风险与缓解

1. **R-1** SAF 路径直连不可用：实测 `SafPathResolver` 返回 `NotPrimary` / `NotReadable`，或 JGit 抛 `AccessDeniedException` → **缓解**：本迭代停在"连通性验证结论"，产出问题记录并保持 `docs/retro/patterns.md` D1 登记，Phase 2 前追加 FS 适配迭代。此结果**也算 Phase 1 验收通过**（达成"验证"目标，而非"跑通"目标）。
2. **R-2** `security-crypto` alpha 版本行为不稳：`MasterKey` 创建可能在部分 OEM ROM 失败 → **缓解**：兜底降级为 `EncryptedSharedPreferences(..., MasterKeys.getOrCreate(AES256_GCM_SPEC))` 的旧 API（通过 try/catch 两级构造）；若仍失败，`HomeUiState.Error` 给出 `"当前设备不支持加密存储，请反馈设备型号"` 文案（Phase 1 不做多方案切换 UI）。
3. **R-3** JGit 6.x 依赖 `java.time` / `java.nio.file`，Android 需开启 `coreLibraryDesugaring` → **缓解**：**两处配置必须成对出现**（见 §4.1.1）：
   - `app/build.gradle.kts` 的 `android { compileOptions { isCoreLibraryDesugaringEnabled = true } }`
   - `dependencies { coreLibraryDesugaring(libs.desugar.jdk.libs) }`
   - 任一缺失会在运行时抛 `NoClassDefFoundError: j$.time.Instant` 等。
4. **R-4** PAT 在内存中被快照（Compose `savedInstanceState` / 最近任务截图 / Heap Dump） → **缓解**三层防护：
   - (a) PAT 输入使用 `TextFieldValue`，**不用 `rememberSaveable`**（避免 Bundle 持久化）；ViewModel `onIntent(SubmitCredential)` 后 UI 层立即 `patState.value = TextFieldValue("")`。
   - (b) PAT 类型全链路改为 `CharArray`（§6.1 / §6.2 / §4.4 / §4.5）；UseCase 在 `try/finally` 中 `Arrays.fill(pat, '\u0000')`。
   - (c) `MainActivity` 启用 `FLAG_SECURE`（§4.6），系统最近任务仅显示黑屏。
5. **R-5** 大仓 Clone 触发 OOM：JGit 默认把 pack 文件 mmap 到内存 → **缓解**：Phase 1 验收仓库限制 ≤ 10MB（NF2）；更大仓移到 Phase 2 做 `WindowCacheConfig` 调优。
6. **R-6** JGit 异常 message 泄漏敏感信息（远程 URL / `Authorization` 头 / token 参数） → **缓解**：统一经 `JGitExceptionSanitizer`（§4.4）脱敏后再进入 UI / 日志；本地日志 Appender 同样挂载 sanitizer 拦截器；CI 脚本抽样扫描 `logs/` 目录断言无 `ghp_` / `github_pat_` 前缀（Phase 1 手动验收，Phase 2 纳入 CI）。
7. **R-7** JGit 传递依赖 `bcprov` 与 AOSP 内置 `bcprov` 冲突 → **缓解**：在 `app/build.gradle.kts` 的 `implementation(libs.jgit)` 上显式 `exclude(group = "org.bouncycastle")`（§4.1.1）；若实测某些 TLS 场景需要 BC，切回 `implementation` 并用 `packaging.resources.pickFirsts` 兜底（本迭代不预实现）。

## 9. 设计自检

> 提交评审前，逐项确认。与本迭代无关的项标注"N/A"。
> 为避免与目标编号（G1-G7）冲突，自检项以 SC 前缀编号。

| # | 检查项 | 状态 |
|---|--------|------|
| SC1 | 生态调研已完成，确认无可直接复用的成熟方案（或已说明为何不复用） | ✅ |
| SC2 | 方案对比的关键差异有量化数字 | ✅ |
| SC3 | 详细设计的每个子任务都能回溯到 §2.3 的某个目标（无冗余设计） | ✅ |
| SC4 | §2.4 非目标未在详细设计中出现（无范围蔓延） | ✅ |
| SC5 | 新增实体的分类逻辑在数据源自描述，非消费端硬编码 | N/A（本迭代无分类实体） |
| SC6 | 新增字段单一语义（分类字段 ≠ 展示字段） | ✅（`local_abs_path` 专供 JGit / `vault_tree_uri` 专供 SAF 权限） |
| SC7 | 新增用户可见功能已填写跨层字段传递矩阵（§6.3） | ✅ |
| SC8 | 验收标准覆盖"可执行性"，非仅"UI 可见" | ✅（A4/A5/A6/A8 要求 `.git` 实体、`git log` 可查、GitHub 远端可见） |
| SC9 | 【安全】敏感凭证建模三件套（非 data class / CharArray / 禁止进 UiState/日志/toString） | ✅（§6.1 + §6.3 边界过滤点 + NF6） |
| SC10 | 【安全】含凭证输入的 UI 启用 `FLAG_SECURE` | ✅（§4.6） |
| SC11 | 【安全】异常 message 经脱敏层处理再进入 UI / 日志 | ✅（§4.4 `JGitExceptionSanitizer` + R-6） |
| SC12 | 【工程基线】依赖四件套完整（TOML 增量 / gradle 增量 / exclude / 成对的 compileOptions 开关） | ✅（§4.1.1 + R-3/R-7） |
| SC13 | 【工程基线】AndroidManifest 改动清单独立成节 | ✅（§4.1.2） |
| SC14 | 【对齐总方案】与总方案 §9 Phase 1 范围一致，偏离已显式声明 | ✅（§1 对齐声明 + G7 纳入） |
| SC15 | 【治理】技术负债（如 FS 适配延后）已登记到 `docs/retro/patterns.md` | ✅（D1） |
| H1-H5 | 【Harness】Prompt / Skill / Agent 相关 | N/A（非 LLM 迭代） |
| S1 | 【选型】对比表含"数据权威性"和"供应链安全"列 | ✅（§3.1.1、§3.1.2） |
| S2 | 【选型】已检查 MCP 注册表 / SkillHub / GitHub 是否有成熟集成 | ✅（§2.2） |

## 10. 变更记录

| 日期 | 版本 | 变更人 | 变更说明 |
|---|---|---|---|
| 2026-05-01 | v1.0 | alexjhwen | 初版：PAT 授权 + SAF 授权 + JGit Clone/Commit/Push 端到端手动链路；确立四层架构骨架、EncryptedSharedPrefs 凭证方案、SAF 绝对路径直连策略。 |
| 2026-05-01 | v1.1 | alexjhwen | 评审修订（见 `docs/version/review/Iteration1_MVP_Core_Link_REVIEW.md`）：①纳入 G7 Pull（对齐总方案 §9 P1.5）；②`Credential` 改为非 data class + `CharArray` + 手写 `toString=redacted`（R3 / 总方案 §5.1）；③新增 §4.1.1 依赖清单与 §4.1.2 Manifest 清单（R4 / R5），补 `INTERNET` 权限与 desugar 双配置；④包名基线统一为 `com.example.simplygit`，`targetSdk` 沿用 36；⑤PAT 存储方案命名统一为 ESP，§3.1.2 术语对齐；⑥SAF 解析返回 `ResolveResult` 三态，加入 `canRead/canWrite` 探测（总方案 §4.1）；⑦新增 `JGitExceptionSanitizer` 异常脱敏层 + R-6 / R-7；⑧`MainActivity` 启用 `FLAG_SECURE` + 剪贴板 60s 清空（总方案 §5.3 / §5.4）；⑨验收标准可操作化（`run-as` + `git log --format`），新增 A8-A10 覆盖 Pull / 剪贴板 / 异常脱敏；⑩为 `PersonIdent.email` 追加 `github_email` 持久化字段与跨层传递路径。 |
