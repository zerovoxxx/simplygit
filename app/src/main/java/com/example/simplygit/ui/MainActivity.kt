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
import com.example.simplygit.ui.home.HomeScreen
import com.example.simplygit.ui.policy.SyncPolicyScreen
import com.example.simplygit.ui.theme.SimplygitTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Host Activity (SPEC §4.5 Iteration 1 + §4.7 Iteration 2).
 *
 *  - FLAG_SECURE covers the entire window so the PAT text field cannot be
 *    captured by screenshots / screen recording / recent-task thumbnails.
 *  - NavHost routes: `home` / `policy` / `audit` / `audit/{logId}`.
 *  - Cold-start catch-up sync is triggered asynchronously so it never blocks
 *    the first Home frame (SPEC NF1 / G8).
 *  - Notification deep links reach us via `onNewIntent` carrying
 *    [NotificationPublisherImpl.EXTRA_NAV] = `audit` or `resume`.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var catchUpTrigger: CatchUpTrigger

    private var pendingNav by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        pendingNav = intent?.getStringExtra(NotificationPublisherImpl.EXTRA_NAV)

        lifecycleScope.launch { runCatching { catchUpTrigger.triggerIfStale() } }

        setContent {
            SimplygitTheme {
                SimplygitNavHost(
                    pendingNav = pendingNav,
                    onNavConsumed = { pendingNav = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNav = intent.getStringExtra(NotificationPublisherImpl.EXTRA_NAV)
    }
}

private object Routes {
    const val HOME = "home"
    const val POLICY = "policy"
    const val AUDIT = "audit"
    const val AUDIT_DETAIL = "audit/{logId}"
    fun auditDetail(id: Long) = "audit/$id"
}

@androidx.compose.runtime.Composable
private fun SimplygitNavHost(
    pendingNav: String?,
    onNavConsumed: () -> Unit,
) {
    val navController = rememberNavController()

    // SPEC §4.5 / §4.7 Iteration 2: honour notification deep links.
    androidx.compose.runtime.LaunchedEffect(pendingNav) {
        when (pendingNav) {
            NotificationPublisherImpl.NAV_AUDIT -> navController.navigate(Routes.AUDIT)
            NotificationPublisherImpl.NAV_RESUME -> Unit // stay on Home; banner surfaces the "Resume" button.
        }
        if (pendingNav != null) onNavConsumed()
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPolicy = { navController.navigate(Routes.POLICY) },
                onOpenAudit = { navController.navigate(Routes.AUDIT) },
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
    }
}
