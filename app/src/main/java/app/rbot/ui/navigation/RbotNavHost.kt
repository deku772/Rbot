package app.rbot.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.rbot.core.AuthManager
import app.rbot.core.BotBridge
import app.rbot.core.ChrootManager
import app.rbot.core.PRootManager
import app.rbot.ui.screen.HomeScreen
import app.rbot.ui.screen.LogScreen
import app.rbot.ui.screen.PermissionsScreen
import app.rbot.ui.screen.SettingsScreen
import app.rbot.ui.screen.SetupScreen
import app.rbot.ui.screen.TerminalScreen

/**
 * 导航图 — 单 Activity 内的路由管理。
 * 包含启动引导逻辑：首次启动 → 权限页 → 安装页 → 主页。
 */

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "主页", Icons.Filled.Home)
    data object Terminal : Screen("terminal", "终端", Icons.Filled.Terminal)
    data object Log : Screen("log", "日志", Icons.AutoMirrored.Filled.List)
    data object Settings : Screen("settings", "设置", Icons.Filled.Settings)
}

sealed class Onboarding(val route: String) {
    data object Permissions : Onboarding("onboarding/permissions")
    data object Setup : Onboarding("onboarding/setup")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RbotNavHost() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Home, Screen.Terminal, Screen.Log, Screen.Settings)

    // ─── 启动引导路由 ───
    // 替代旧版 RbotActivity 的 checkAndRoute() 逻辑
    LaunchedEffect(Unit) {
        val isRootfsReady = ChrootManager.isRootfsReady() ||
            PRootManager.isAstrBotInstalledStatic() // 简化检查
        val isBotInstalled = ChrootManager.isAstrBotInstalled()

        if (!isRootfsReady || !isBotInstalled) {
            // 需要安装 → 跳转到权限页
            navController.navigate(Onboarding.Permissions.route) {
                popUpTo(Screen.Home.route) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            // 只在主页面显示底部导航栏
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            val isOnboarding = currentRoute?.startsWith("onboarding") == true

            if (!isOnboarding) {
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination

                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ─── 主页面 ───
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Terminal.route) { TerminalScreen() }
            composable(Screen.Log.route) { LogScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }

            // ─── 引导页面 ───
            composable(Onboarding.Permissions.route) {
                PermissionsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onStartInstall = {
                        navController.navigate(Onboarding.Setup.route)
                    }
                )
            }

            composable(Onboarding.Setup.route) {
                SetupScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onInstallComplete = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Onboarding.Permissions.route) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
