package com.sky.siplink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.sky.siplink.core.model.BannerPosition
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen
import com.sky.siplink.ui.accounts.AccountEditScreen
import com.sky.siplink.ui.accounts.AccountsScreen
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.pressScale
import com.sky.siplink.ui.dialer.DialerScreen
import com.sky.siplink.ui.directory.DirectoryScreen
import com.sky.siplink.ui.directory.LdapEditScreen
import com.sky.siplink.ui.directory.LdapListScreen
import com.sky.siplink.ui.history.HistoryScreen
import com.sky.siplink.ui.settings.CallHandlingScreen
import com.sky.siplink.ui.settings.CodecsScreen
import com.sky.siplink.ui.settings.FeatureCodesScreen
import com.sky.siplink.ui.settings.SettingsScreen
import com.sky.siplink.ui.settings.TlsScreen
import com.sky.siplink.ui.shortcuts.ShortcutsScreen
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    DIALER("dialer", "拨号", Icons.Outlined.Dialpad, Icons.Rounded.Dialpad),
    HISTORY("history", "记录", Icons.Outlined.History, Icons.Rounded.History),
    DIRECTORY("directory", "通讯录", Icons.Outlined.Contacts, Icons.Rounded.Contacts),
    SETTINGS("settings", "设置", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

private object Routes {
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_NEW = "account/new"
    const val ACCOUNT_EDIT = "account/edit/{id}"
    const val LDAP = "ldap"
    const val LDAP_NEW = "ldap/new"
    const val LDAP_EDIT = "ldap/edit/{id}"
    const val TLS = "tls"
    const val CALL_HANDLING = "call-handling"
    const val FEATURE_CODES = "feature-codes"
    const val CODECS = "codecs"

    /** The account chosen on the dial pad travels along; without it the default account is meant. */
    const val SHORTCUTS = "shortcuts?account={account}"

    fun accountEdit(id: String) = "account/edit/${Uri.encode(id)}"

    fun ldapEdit(id: String) = "ldap/edit/${Uri.encode(id)}"

    fun shortcuts(accountId: String?) = if (accountId == null) "shortcuts" else "shortcuts?account=${Uri.encode(accountId)}"
}

private fun isTab(route: String?): Boolean = Tab.entries.any { it.route == route }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val engine = context.engine
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val topLevel = route == null || isTab(route)
    val call by engine.calls.call.collectAsStateWithLifecycle()
    // Only the one setting this screen depends on, so that other settings do not recompose it.
    val bannerAtTop by remember(engine) {
        engine.settings.settings.map { it.callBannerPosition == BannerPosition.TOP }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(engine.settings.current.callBannerPosition == BannerPosition.TOP)
    // The way back to the call is offered on the main screens and next to the shortcut keys,
    // which is where a call in progress is handled from outside its own screen.
    val bannerWanted = topLevel || route == Routes.SHORTCUTS
    val openCall = { context.openCallScreen() }

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
            topBar = {
                // The banner takes the status bar inset over from the screen below it.
                ActiveCallBanner(call, visible = bannerWanted && bannerAtTop, onClick = openCall, modifier = Modifier.statusBarsPadding())
            },
            bottomBar = {
                Column {
                    ActiveCallBanner(
                        call,
                        visible = bannerWanted && !bannerAtTop,
                        onClick = openCall,
                        modifier = if (topLevel) Modifier else Modifier.navigationBarsPadding(),
                    )
                    AnimatedVisibility(
                        visible = topLevel,
                        enter = expandVertically(Motion.spatial()) + fadeIn(Motion.fade(160)),
                        exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.fade(100)),
                    ) {
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
                    if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) Motion.tabEnter else Motion.pushEnter
                },
                exitTransition = {
                    if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) Motion.tabExit else Motion.pushExit
                },
                popEnterTransition = { Motion.popEnter },
                popExitTransition = { Motion.popExit },
            ) {
                composable(Tab.DIALER.route) {
                    DialerScreen(
                        onAddAccount = { navController.navigate(Routes.ACCOUNT_NEW) },
                        onOpenShortcuts = { accountId -> navController.navigate(Routes.shortcuts(accountId)) },
                    )
                }
                composable(Tab.HISTORY.route) { HistoryScreen() }
                composable(Tab.DIRECTORY.route) {
                    DirectoryScreen(onOpenSettings = { navController.navigate(Routes.LDAP) })
                }
                composable(Routes.ACCOUNTS) {
                    AccountsScreen(
                        onBack = { navController.popBackStack() },
                        onAdd = { navController.navigate(Routes.ACCOUNT_NEW) },
                        onEdit = { navController.navigate(Routes.accountEdit(it)) },
                    )
                }
                composable(Tab.SETTINGS.route) {
                    SettingsScreen(
                        onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                        onOpenTls = { navController.navigate(Routes.TLS) },
                        onOpenLdap = { navController.navigate(Routes.LDAP) },
                        onOpenCallHandling = { navController.navigate(Routes.CALL_HANDLING) },
                        onOpenFeatureCodes = { navController.navigate(Routes.FEATURE_CODES) },
                        onOpenCodecs = { navController.navigate(Routes.CODECS) },
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
                    LdapListScreen(
                        onBack = { navController.popBackStack() },
                        onAdd = { navController.navigate(Routes.LDAP_NEW) },
                        onEdit = { navController.navigate(Routes.ldapEdit(it)) },
                    )
                }
                composable(Routes.LDAP_NEW) {
                    LdapEditScreen(
                        directoryId = null,
                        onBack = { navController.popBackStack() },
                        onOpenTls = { navController.navigate(Routes.TLS) },
                    )
                }
                composable(
                    Routes.LDAP_EDIT,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    LdapEditScreen(
                        directoryId = entry.arguments?.getString("id"),
                        onBack = { navController.popBackStack() },
                        onOpenTls = { navController.navigate(Routes.TLS) },
                    )
                }
                composable(Routes.TLS) { TlsScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.CALL_HANDLING) { CallHandlingScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.FEATURE_CODES) { FeatureCodesScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.CODECS) { CodecsScreen(onBack = { navController.popBackStack() }) }
                composable(
                    Routes.SHORTCUTS,
                    arguments = listOf(
                        navArgument("account") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    ShortcutsScreen(
                        accountId = entry.arguments?.getString("account"),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(route: String?, navController: NavHostController) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            // The icon of the chosen tab pops a little; the outline turns into the filled shape.
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.12f else 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                label = "tabIcon",
            )
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Crossfade(selected, animationSpec = Motion.fade(140), label = "tabIconShape") { on ->
                        Icon(
                            if (on) tab.selectedIcon else tab.icon,
                            contentDescription = null,
                            modifier = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        )
                    }
                },
                label = { Text(tab.label) },
            )
        }
    }
}

/** Green strip, at the top or above the navigation bar, that leads back to the running call. */
@Composable
private fun ActiveCallBanner(call: CallInfo?, visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = visible && call != null && call.phase != CallPhase.ENDED
    AnimatedVisibility(
        visible = active,
        enter = expandVertically(Motion.spatial()) + fadeIn(Motion.fade(160)),
        exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.fade(100)),
    ) {
        Surface(
            color = Brand.CallGreen,
            shape = MaterialTheme.shapes.large,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .pressScale(0.97f),
        ) {
            Row(
                Modifier
                    .clickable(onClick = onClick)
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
