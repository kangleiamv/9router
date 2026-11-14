package ai.ninerouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.ninerouter.ui.components.StatusPill
import ai.ninerouter.ui.screens.CombosScreen
import ai.ninerouter.ui.screens.DashboardScreen
import ai.ninerouter.ui.screens.KeysScreen
import ai.ninerouter.ui.screens.LogsScreen
import ai.ninerouter.ui.screens.ProvidersScreen
import ai.ninerouter.ui.screens.QuotaScreen
import ai.ninerouter.ui.screens.SettingsScreen
import ai.ninerouter.ui.screens.TelegramScreen
import ai.ninerouter.ui.screens.UsageScreen
import ai.ninerouter.ui.theme.AppBorder
import ai.ninerouter.ui.theme.AppSurface
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Obsidian
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary
import ai.ninerouter.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class DrawerEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val subtitle: String
)

private val entries = listOf(
    DrawerEntry("dashboard", "Dashboard", Icons.Filled.Home, "Local gateway"),
    DrawerEntry("providers", "Providers", Icons.Filled.Dns, "Connections & credentials"),
    DrawerEntry("combos", "Combo & Vision", Icons.Filled.Layers, "Fallback chains"),
    DrawerEntry("keys", "Endpoint & Key", Icons.Filled.VpnKey, "Client credentials"),
    DrawerEntry("usage", "Usage", Icons.Filled.BarChart, "Request analytics"),
    DrawerEntry("quota", "Quota Tracker", Icons.Filled.DataUsage, "Live limits & cooldowns"),
    DrawerEntry("telegram", "Telegram Bot", Icons.Filled.Send, "Chat bridge"),
    DrawerEntry("logs", "Console Log", Icons.Filled.Article, "Request history"),
    DrawerEntry("settings", "Settings", Icons.Filled.Settings, "Gateway & storage")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NineRouterRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val running by viewModel.running.collectAsState()
    val port by viewModel.port.collectAsState()
    val requests by viewModel.requests.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbar.showSnackbar(
                message = if (event.isError) "⚠️ ${event.message}" else event.message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "dashboard"
    val entry = entries.firstOrNull { it.route == route } ?: entries.first()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brand),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("9", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("9Router", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                            Text(
                                "android 1.0.0",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    entries.forEach { item ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(item.label, style = MaterialTheme.typography.titleSmall)
                                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            selected = route == item.route,
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (item.route == "dashboard") {
                                    // Always reveal the existing dashboard entry. This avoids
                                    // restored navigation state leaving another screen visible.
                                    navController.popBackStack("dashboard", inclusive = false)
                                } else if (route != item.route) {
                                    navController.navigate(item.route) {
                                        launchSingleTop = true
                                        popUpTo("dashboard") { saveState = true }
                                        restoreState = true
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Brand.copy(alpha = 0.14f),
                                selectedIconColor = Brand,
                                selectedTextColor = Brand,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Divider(color = AppBorder)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (running) Emerald else Rose)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (running) "Gateway running" else "Gateway stopped",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Text(
                                "127.0.0.1:$port",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = TextMuted
                            )
                        }
                        Text(
                            "${requests.size} req",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Obsidian,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(entry.label, style = MaterialTheme.typography.titleLarge)
                            Text(
                                entry.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open navigation", tint = TextSecondary)
                        }
                    },
                    actions = {
                        StatusPill(
                            text = if (running) "Live" else "Offline",
                            color = if (running) Emerald else TextMuted,
                            modifier = Modifier.padding(end = 14.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Obsidian,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextSecondary,
                        actionIconContentColor = TextSecondary
                    )
                )
            },
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = AppSurfaceHigh,
                        contentColor = TextPrimary,
                        actionColor = Brand
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "dashboard",
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                composable("dashboard") {
                    DashboardScreen(viewModel) { target ->
                        navController.navigate(target) { launchSingleTop = true }
                    }
                }
                composable("providers") { ProvidersScreen(viewModel) }
                composable("combos") { CombosScreen(viewModel) }
                composable("keys") { KeysScreen(viewModel) }
                composable("usage") { UsageScreen(viewModel) }
                composable("quota") { QuotaScreen(viewModel) }
                composable("telegram") { TelegramScreen(viewModel) }
                composable("logs") { LogsScreen(viewModel) }
                composable("settings") { SettingsScreen(viewModel) }
            }
        }
    }
}
