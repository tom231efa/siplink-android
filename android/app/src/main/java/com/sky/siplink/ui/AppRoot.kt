package com.sky.siplink.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sky.siplink.BuildConfig
import com.sky.siplink.R
import com.sky.siplink.core.logic.ManagedConfig
import com.sky.siplink.core.logic.UpdateRules
import com.sky.siplink.core.model.BannerPosition
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.CallPhase
import com.sky.siplink.core.render
import com.sky.siplink.engine
import com.sky.siplink.openCallScreen
import com.sky.siplink.ui.accounts.AccountEditScreen
import com.sky.siplink.ui.accounts.AccountsScreen
import com.sky.siplink.ui.accounts.LineDiagnosisScreen
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.LdapRestartDialog
import com.sky.siplink.ui.components.LocalConfigLocked
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.pressScale
import com.sky.siplink.ui.components.stringOf
import com.sky.siplink.ui.dialer.DialerScreen
import com.sky.siplink.ui.directory.DirectoryScreen
import com.sky.siplink.ui.directory.LdapEditScreen
import com.sky.siplink.ui.directory.LdapListScreen
import com.sky.siplink.ui.history.HistoryScreen
import com.sky.siplink.ui.recordings.RecordingsScreen
import com.sky.siplink.ui.settings.AboutScreen
import com.sky.siplink.ui.settings.AdvancedSettingsScreen
import com.sky.siplink.ui.settings.AutoAnswerScreen
import com.sky.siplink.ui.settings.CodecsScreen
import com.sky.siplink.ui.settings.FeatureCodesScreen
import com.sky.siplink.ui.settings.LanguageScreen
import com.sky.siplink.ui.settings.RecordingSettingsScreen
import com.sky.siplink.ui.settings.RemoteConfigScreen
import com.sky.siplink.ui.settings.SettingsScreen
import com.sky.siplink.ui.settings.TlsScreen
import com.sky.siplink.ui.shortcuts.ShortcutsScreen
import com.sky.siplink.ui.theme.Brand
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private object Routes {
    const val ACCOUNTS = "accounts"
    const val ACCOUNT_NEW = "account/new"
    const val ACCOUNT_EDIT = "account/edit/{id}"
    const val LINE_DIAGNOSIS = "account/line/{id}"
    const val LDAP = "ldap"
    const val LDAP_NEW = "ldap/new"
    const val LDAP_EDIT = "ldap/edit/{id}"
    const val TLS = "tls"
    const val ADVANCED = "advanced"
    const val FEATURE_CODES = "feature-codes"
    const val CODECS = "codecs"
    const val REMOTE_CONFIG = "remote-config"
    const val RECORDING_SETTINGS = "recording-settings"
    const val RECORDINGS = "recordings"
    const val AUTO_ANSWER = "auto-answer"
    const val ABOUT = "about"
    const val LANGUAGE = "language"

    /** The account chosen on the dial pad travels along; without it the default account is meant. */
    const val SHORTCUTS = "shortcuts?account={account}"

    fun accountEdit(id: String) = "account/edit/${Uri.encode(id)}"

    fun lineDiagnosis(id: String) = "account/line/${Uri.encode(id)}"

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
    val rail = usesRail(LocalWindowInfo.current.containerDpSize.width)
    val call by engine.calls.call.collectAsStateWithLifecycle()
    // A managed configuration is only looked at: the screens below read this through LocalConfigLocked.
    val managed by engine.lock.managed.collectAsStateWithLifecycle()
    // Only the one setting this screen depends on, so that other settings do not recompose it.
    val bannerAtTop by remember(engine) {
        engine.settings.settings.map { it.callBannerPosition == BannerPosition.TOP }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(engine.settings.current.callBannerPosition == BannerPosition.TOP)
    // A newer version that the user has not skipped puts a dot on the settings tab.
    val updateOffered by remember(engine) {
        engine.updates.status.map { UpdateRules.offered(it, BuildConfig.VERSION_NAME) != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    // A remote configuration changed the CAs after LDAP had used TLS: wherever the user is, the app asks to restart.
    val restartForLdap by remember(engine) {
        engine.remote.status.map { it.restartNeeded }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
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

    CompositionLocalProvider(LocalMessenger provides messenger, LocalConfigLocked provides managed) {
        StartupPermissions()
        if (restartForLdap) LdapRestartDialog(onDismiss = engine.remote::restartHandled)
        // On a wide window (a phone on its side) the tabs stand in a rail at the start, which keeps
        // clear of the system bars and the cutout on its side. The screens beside it work out from
        // where they stand which insets are still theirs: no gap is left twice.
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                AnimatedVisibility(
                    visible = topLevel,
                    // In from the edge of the screen and back out to it.
                    enter = expandHorizontally(Motion.spatial()) + fadeIn(Motion.fade(160)),
                    exit = shrinkHorizontally(Motion.spatial()) + fadeOut(Motion.fade(100)),
                ) {
                    SideRail(route, navController, updateOffered)
                }
            }
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .recalculateWindowInsets(),
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
                            // Beside the rail the main screens still end above the navigation bar.
                            if (rail) {
                                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            } else {
                                BottomBar(route, navController, updateOffered)
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = Tab.DIALER.route,
                    // On its side the phone has the camera cutout, and with three-button navigation the
                    // navigation bar, on the left or the right: no screen puts anything under them.
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
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
                            // Without an account the dial pad leads to the form for one, which a managed configuration has no use for.
                            onAddAccount = { if (managed) messenger(context.render(ManagedConfig.NOTICE)) else navController.navigate(Routes.ACCOUNT_NEW) },
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
                            onOpenAdvanced = { navController.navigate(Routes.ADVANCED) },
                            onOpenFeatureCodes = { navController.navigate(Routes.FEATURE_CODES) },
                            onOpenCodecs = { navController.navigate(Routes.CODECS) },
                            onOpenRemoteConfig = { navController.navigate(Routes.REMOTE_CONFIG) },
                            onOpenRecordingSettings = { navController.navigate(Routes.RECORDING_SETTINGS) },
                            onOpenAutoAnswer = { navController.navigate(Routes.AUTO_ANSWER) },
                            onOpenAbout = { navController.navigate(Routes.ABOUT) },
                            onOpenLanguage = { navController.navigate(Routes.LANGUAGE) },
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
                            onOpenLine = { navController.navigate(Routes.lineDiagnosis(it)) },
                        )
                    }
                    composable(
                        Routes.LINE_DIAGNOSIS,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        LineDiagnosisScreen(
                            accountId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { navController.popBackStack() },
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
                    composable(Routes.LANGUAGE) { LanguageScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.TLS) { TlsScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.ADVANCED) { AdvancedSettingsScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.FEATURE_CODES) { FeatureCodesScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.CODECS) { CodecsScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.REMOTE_CONFIG) { RemoteConfigScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.RECORDING_SETTINGS) {
                        RecordingSettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
                        )
                    }
                    composable(Routes.RECORDINGS) { RecordingsScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.AUTO_ANSWER) { AutoAnswerScreen(onBack = { navController.popBackStack() }) }
                    composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }
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
}

/**
 * Green strip, at the top or above the navigation bar, that leads back to the running call. On
 * its side the phone may have the cutout or the navigation bar at either end of it.
 */
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
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
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
                FadingText(
                    text = when (call?.phase) {
                        CallPhase.INCOMING -> stringOf(R.string.main_banner_incoming, call.remoteName)
                        CallPhase.OUTGOING -> stringOf(R.string.main_banner_outgoing, call.remoteName)
                        else -> stringOf(R.string.main_banner_in_call, call?.remoteName.orEmpty())
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_banner_return), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
