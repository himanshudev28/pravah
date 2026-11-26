package dev.pravah.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pravah.app.service.BatteryMonitorService
import dev.pravah.app.ui.screen.DashboardScreen
import dev.pravah.app.ui.screen.HealthScreen
import dev.pravah.app.ui.screen.HistoryScreen
import dev.pravah.app.ui.screen.SettingsScreen
import dev.pravah.app.ui.theme.PravahTheme
import dev.pravah.app.ui.viewmodel.BatteryViewModel

private sealed class Dest(val route: String, val label: String) {
    data object Dashboard : Dest("dashboard", "Dashboard")
    data object Health    : Dest("health", "Health")
    data object History   : Dest("history", "History")
    data object Settings  : Dest("settings", "Settings")
}

class MainActivity : ComponentActivity() {

    private val vm: BatteryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startBatteryService()
        requestNotificationPermission()

        setContent {
            val state by vm.uiState.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (state.themeMode) {
                1    -> false
                2    -> true
                else -> systemDark
            }
            PravahTheme(amoled = state.amoledTheme, darkTheme = isDark) {
                PravahApp(vm = vm)
            }
        }
    }

    override fun onStart() { super.onStart(); vm.setUiVisible(true) }
    override fun onStop() { super.onStop(); vm.setUiVisible(false) }

    /** Android 13+: without this the charge alarm and live status never appear. Asked once. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun startBatteryService() {
        val intent = Intent(this, BatteryMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
private fun PravahApp(vm: BatteryViewModel) {
    // State is collected inside each destination so only the visible screen recomposes,
    // and the NavHost graph isn't rebuilt every second.
    val navController = rememberNavController()

    val navItems = listOf(
        NavItem(Dest.Dashboard, Icons.Outlined.Bolt, "Live"),
        NavItem(Dest.Health, Icons.Outlined.MonitorHeart, "Health"),
        NavItem(Dest.History, Icons.Outlined.History, "History"),
        NavItem(Dest.Settings, Icons.Outlined.Settings, "Settings"),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                val entry by navController.currentBackStackEntryAsState()
                val currentDest = entry?.destination
                navItems.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        selected = currentDest?.hierarchy?.any { it.route == item.dest.route } == true,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        onClick = {
                            navController.navigate(item.dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Dashboard.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(100)) },
            popEnterTransition = { fadeIn(tween(150)) },
            popExitTransition = { fadeOut(tween(100)) },
        ) {
            composable(Dest.Dashboard.route) {
                val state by vm.uiState.collectAsState()
                DashboardScreen(state)
            }
            composable(Dest.Health.route) {
                val state by vm.uiState.collectAsState()
                HealthScreen(state, onSetDesignCapacity = vm::setDesignCapacity)
            }
            composable(Dest.History.route) {
                val state by vm.settledState.collectAsState()
                HistoryScreen(state)
            }
            composable(Dest.Settings.route) {
                val state by vm.settledState.collectAsState()
                SettingsScreen(
                    state = state,
                    onAlarmPercentChange = vm::setAlarmPercent,
                    onAlarmEnabledChange = vm::setAlarmEnabled,
                    onUseFahrenheit = vm::setUseFahrenheit,
                    onAmoledTheme = vm::setAmoledTheme,
                    onNotificationLive = vm::setNotificationLive,
                    onResetData = vm::resetData,
                    onThemeMode = vm::setThemeMode,
                    onChargeLimit = vm::setChargeLimitManual,
                )
            }
        }
    }
}

private class NavItem(val dest: Dest, val icon: ImageVector, val label: String)
