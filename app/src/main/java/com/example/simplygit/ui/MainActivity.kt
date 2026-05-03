package com.example.simplygit.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.simplygit.notification.NotificationPublisherImpl
import com.example.simplygit.runtime.CatchUpTrigger
import com.example.simplygit.ui.audit.SyncAuditDetailScreen
import com.example.simplygit.ui.audit.SyncAuditScreen
import com.example.simplygit.ui.browser.RepoBrowserScreen
import com.example.simplygit.ui.conflict.ConflictResolveScreen
import com.example.simplygit.ui.diff.DiffScreen
import com.example.simplygit.ui.home.HomeScreen
import com.example.simplygit.ui.policy.SyncPolicyScreen
import com.example.simplygit.ui.ssh.SshKeyScreen
import com.example.simplygit.ui.theme.SimplygitTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Host Activity (SPEC §4.5 Iteration 1 + §4.7 Iteration 2 + §5.1 Iteration 3).
 *
 *  - FLAG_SECURE covers the entire window so the PAT text field cannot be
 *    captured by screenshots / screen recording / recent-task thumbnails.
 *  - NavHost routes: `home` / `policy` / `audit` / `audit/{logId}` /
 *    `browser/{repoId}` / `diff/{repoId}/{encodedPath}?source=...` /
 *    `conflict/{repoId}` / `ssh_keys`.
 *  - Cold-start catch-up sync is triggered asynchronously so it never blocks
 *    the first Home frame (SPEC NF1 / G8).
 *  - Notification deep links reach us via `onNewIntent` carrying
 *    [NotificationPublisherImpl.EXTRA_NAV] (`audit` / `resume` / `conflict`)
 *    plus an optional [NotificationPublisherImpl.EXTRA_REPO_ID] for the
 *    conflict-specific landing.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var catchUpTrigger: CatchUpTrigger

    private var pendingNav by mutableStateOf<String?>(null)
    private var pendingNavRepoId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        pendingNav = intent?.getStringExtra(NotificationPublisherImpl.EXTRA_NAV)
        pendingNavRepoId = intent?.takeIf {
            it.hasExtra(NotificationPublisherImpl.EXTRA_REPO_ID)
        }?.getLongExtra(NotificationPublisherImpl.EXTRA_REPO_ID, 0L)

        lifecycleScope.launch { runCatching { catchUpTrigger.triggerIfStale() } }

        setContent {
            SimplygitTheme {
                SimplygitNavHost(
                    pendingNav = pendingNav,
                    pendingNavRepoId = pendingNavRepoId,
                    onNavConsumed = {
                        pendingNav = null
                        pendingNavRepoId = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNav = intent.getStringExtra(NotificationPublisherImpl.EXTRA_NAV)
        pendingNavRepoId = if (intent.hasExtra(NotificationPublisherImpl.EXTRA_REPO_ID)) {
            intent.getLongExtra(NotificationPublisherImpl.EXTRA_REPO_ID, 0L)
        } else null
    }
}

private object Routes {
    const val HOME = "home"
    const val POLICY = "policy"
    const val AUDIT = "audit"
    const val AUDIT_DETAIL = "audit/{logId}"
    fun auditDetail(id: Long) = "audit/$id"

    // SPEC §5.1 Iteration 3 (P0-2): new routes for browser / diff / conflict / ssh.
    const val BROWSER = "browser/{repoId}"
    fun browser(id: Long) = "browser/$id"
    const val DIFF = "diff/{repoId}/{encodedPath}?source={source}"
    fun diff(id: Long, encodedPath: String, source: String) =
        "diff/$id/$encodedPath?source=$source"
    const val CONFLICT = "conflict/{repoId}"
    fun conflict(id: Long) = "conflict/$id"
    const val SSH_KEYS = "ssh_keys"
}

@androidx.compose.runtime.Composable
private fun SimplygitNavHost(
    pendingNav: String?,
    pendingNavRepoId: Long?,
    onNavConsumed: () -> Unit,
) {
    val navController = rememberNavController()

    // SPEC §4.5 / §4.7 Iteration 2 + §5.1 Iteration 3: honour notification deep links.
    androidx.compose.runtime.LaunchedEffect(pendingNav, pendingNavRepoId) {
        when (pendingNav) {
            NotificationPublisherImpl.NAV_AUDIT -> navController.navigate(Routes.AUDIT)
            NotificationPublisherImpl.NAV_RESUME -> Unit // stay on Home; banner surfaces the "Resume" button.
            NotificationPublisherImpl.NAV_CONFLICT -> {
                val repoId = pendingNavRepoId ?: return@LaunchedEffect
                if (repoId > 0L) navController.navigate(Routes.conflict(repoId))
            }
        }
        if (pendingNav != null) onNavConsumed()
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPolicy = { navController.navigate(Routes.POLICY) },
                onOpenAudit = { navController.navigate(Routes.AUDIT) },
                onBrowseRepo = { repoId -> navController.navigate(Routes.browser(repoId)) },
                onResolveConflict = { repoId -> navController.navigate(Routes.conflict(repoId)) },
                onOpenSshKeys = { navController.navigate(Routes.SSH_KEYS) },
            )
        }
        composable(Routes.POLICY) {
            SyncPolicyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AUDIT) {
            SyncAuditScreen(
                onBack = { navController.popBackStack() },
                onOpenDetail = { id -> navController.navigate(Routes.auditDetail(id)) },
            )
        }
        composable(
            route = Routes.AUDIT_DETAIL,
            arguments = listOf(navArgument("logId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("logId") ?: 0L
            SyncAuditDetailScreen(logId = id, onBack = { navController.popBackStack() })
        }
        // SPEC §4.1.2 / §5.1 Iteration 3: file-tree browser.
        composable(
            route = Routes.BROWSER,
            arguments = listOf(navArgument("repoId") { type = NavType.LongType }),
        ) { entry ->
            val repoId = entry.arguments?.getLong("repoId") ?: 0L
            RepoBrowserScreen(
                repoId = repoId,
                onBack = { navController.popBackStack() },
                onOpenDiff = { path ->
                    val encoded = android.net.Uri.encode(path)
                    navController.navigate(
                        Routes.diff(
                            id = repoId,
                            encodedPath = encoded,
                            source = com.example.simplygit.domain.model.DiffSource.WORKING_VS_HEAD.name,
                        )
                    )
                },
            )
        }
        // SPEC §4.2.2 / §5.1 Iteration 3: unified-diff viewer.
        composable(
            route = Routes.DIFF,
            arguments = listOf(
                navArgument("repoId") { type = NavType.LongType },
                navArgument("encodedPath") { type = NavType.StringType },
                navArgument("source") {
                    type = NavType.StringType
                    defaultValue = com.example.simplygit.domain.model.DiffSource.WORKING_VS_HEAD.name
                },
            ),
        ) { entry ->
            val repoId = entry.arguments?.getLong("repoId") ?: 0L
            val encodedPath = entry.arguments?.getString("encodedPath").orEmpty()
            val sourceName = entry.arguments?.getString("source")
                ?: com.example.simplygit.domain.model.DiffSource.WORKING_VS_HEAD.name
            val source = runCatching {
                com.example.simplygit.domain.model.DiffSource.valueOf(sourceName)
            }.getOrDefault(com.example.simplygit.domain.model.DiffSource.WORKING_VS_HEAD)
            DiffScreen(
                repoId = repoId,
                path = android.net.Uri.decode(encodedPath),
                source = source,
                onBack = { navController.popBackStack() },
            )
        }
        // SPEC §4.3.2 / §5.1 Iteration 3: conflict-resolve screen.
        composable(
            route = Routes.CONFLICT,
            arguments = listOf(navArgument("repoId") { type = NavType.LongType }),
        ) { entry ->
            val repoId = entry.arguments?.getLong("repoId") ?: 0L
            ConflictResolveScreen(
                repoId = repoId,
                onBack = { navController.popBackStack() },
                onPreviewDiff = { path ->
                    val encoded = android.net.Uri.encode(path)
                    navController.navigate(
                        Routes.diff(
                            id = repoId,
                            encodedPath = encoded,
                            source = com.example.simplygit.domain.model.DiffSource.OURS_VS_THEIRS.name,
                        )
                    )
                },
            )
        }
        // SPEC §4.4.3 / §5.1 Iteration 3: SSH key management.
        composable(Routes.SSH_KEYS) {
            SshKeyScreen(onBack = { navController.popBackStack() })
        }
    }
}
