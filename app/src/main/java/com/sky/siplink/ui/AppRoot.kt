package com.sky.siplink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen
import com.sky.siplink.ui.accounts.AccountEditScreen
import com.sky.siplink.ui.accounts.AccountsScreen
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.dialer.DialerScreen
import com.sky.siplink.ui.directory.DirectoryScreen
import com.sky.siplink.ui.directory.LdapSettingsScreen
import com.sky.siplink.ui.history.HistoryScreen
import com.sky.siplink.ui.settings.SettingsScreen
import com.sky.siplink.ui.settings.TlsScreen
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    DIALER("dialer", "拨号", Icons.Outlined.Dialpad, Icons.Rounded.Dialpad),
    HISTORY("history", "记录", Icons.Outlined.History, Icons.Rounded.History),
    DIRECTORY("directory", "通讯录", Icons.Outlined.Contacts, Icons.Rounded.Contacts),
    ACCOUNTS("accounts", "账号", Icons.Outlined.ManageAccounts, Icons.Rounded.ManageAccounts),
    SETTINGS("settings", "设置", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

private object Routes {
    const val ACCOUNT_NEW = "account/new"
    const val ACCOUNT_EDIT = "account/edit/{id}"
    const val LDAP = "ldap"
    const val TLS = "tls"

    fun accountEdit(id: String) = "account/edit/${Uri.encode(id)}"
}

private fun isTab(route: String?): Boolean = Tab.entries.any { it.route == route }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val topLevel = route == null || isTab(route)
    val call by context.engine.calls.call.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messenger: (String) -> Unit = remember {
        { message ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    StartupPermissions()

    CompositionLocalProvider(LocalMessenger provides messenger) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    snackbarHostState,
                    modifier = if (topLevel) Modifier else Modifier.navigationBarsPadding().imePadding(),
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = topLevel,
                    enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
                ) {
                    Column {
                        ActiveCallBanner(call, onClick = { context.openCallScreen() })
                        BottomBar(route, navController)
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Tab.DIALER.route,
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding),
                enterTransition = {
                    if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) {
                        fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f)
                    } else {
                        slideInHorizontally(tween(320)) { it / 5 } + fadeIn(tween(240))
                    }
                },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(240)) },
                popExitTransition = { slideOutHorizontally(tween(280)) { it / 5 } + fadeOut(tween(200)) },
            ) {
                composable(Tab.DIALER.route) {
                    DialerScreen(onAddAccount = { navController.navigate(Routes.ACCOUNT_NEW) })
                }
                composable(Tab.HISTORY.route) { HistoryScreen() }
                composable(Tab.DIRECTORY.route) {
                    DirectoryScreen(onOpenSettings = { navController.navigate(Routes.LDAP) })
                }
                composable(Tab.ACCOUNTS.route) {
                    AccountsScreen(
                        onAdd = { navController.navigate(Routes.ACCOUNT_NEW) },
                        onEdit = { navController.navigate(Routes.accountEdit(it)) },
                    )
                }
                composable(Tab.SETTINGS.route) {
                    SettingsScreen(
                        onOpenTls = { navController.navigate(Routes.TLS) },
                        onOpenLdap = { navController.navigate(Routes.LDAP) },
                    )
                }
                composable(Routes.ACCOUNT_NEW) {
                    AccountEditScreen(
                        accountId = null,
                        onBack = { navController.popBackStack() },
                        onOpenTls = { navController.navigate(Routes.TLS) },
                    )
                }
                composable(
                    Routes.ACCOUNT_EDIT,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    AccountEditScreen(
                        accountId = entry.arguments?.getString("id"),
                        onBack = { navController.popBackStack() },
                        onOpenTls = { navController.navigate(Routes.TLS) },
                    )
                }
                composable(Routes.LDAP) {
                    LdapSettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTls = { navController.navigate(Routes.TLS) },
                    )
                }
                composable(Routes.TLS) { TlsScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun BottomBar(route: String?, navController: NavHostController) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

/** Green strip above the navigation bar that leads back to the running call. */
@Composable
private fun ActiveCallBanner(call: CallInfo?, onClick: () -> Unit) {
    val active = call != null && call.phase != CallPhase.ENDED
    AnimatedVisibility(active, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Surface(color = Brand.CallGreen, shape = MaterialTheme.shapes.large) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PhoneInTalk, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when (call?.phase) {
                            CallPhase.INCOMING -> "来电：${call.remoteName}"
                            CallPhase.OUTGOING -> "正在呼叫 ${call.remoteName}"
                            else -> "通话中：${call?.remoteName.orEmpty()}"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("返回通话", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Asks once per launch for the runtime permissions a softphone needs. */
@Composable
private fun StartupPermissions() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}
