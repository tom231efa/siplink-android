package com.sky.siplink.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

/** The four main screens, in the order of the bar and of the rail. */
internal enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    DIALER("dialer", "拨号", Icons.Outlined.Dialpad, Icons.Rounded.Dialpad),
    HISTORY("history", "记录", Icons.Outlined.History, Icons.Rounded.History),
    DIRECTORY("directory", "通讯录", Icons.Outlined.Contacts, Icons.Rounded.Contacts),
    SETTINGS("settings", "设置", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

/**
 * From this window width on, the tabs stand in a rail at the side instead of the bar at the
 * bottom: where Material's medium width class begins (WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND
 * in androidx.window). A phone held upright, its cover screen and half a split screen are
 * narrower; a phone on its side is wider. The height does not count: a phone on its side is low
 * as well, and that is where the rail saves the most room.
 */
internal val RailFromWidth = 600.dp

/** Whether a window [width] wide (the whole window, the system bars included) shows the rail. */
internal fun usesRail(width: Dp): Boolean = width >= RailFromWidth

/** What the settings tab adds to its name for a screen reader while it carries the dot. */
private const val UPDATE_NEWS = "有新版本可用"

/** The bar of the tabs, at the bottom of a narrow window. */
@Composable
internal fun BottomBar(route: String?, navController: NavHostController, updateOffered: Boolean) {
    NavigationBar {
        Tabs(route, navController, updateOffered) { selected, onClick, icon, label ->
            NavigationBarItem(selected = selected, onClick = onClick, icon = icon, label = label)
        }
    }
}

/**
 * The rail of the tabs, at the start of a wide window. It keeps clear of the status bar, the
 * navigation bar and the camera cutout on its own side; the screen beside it takes the other.
 */
@Composable
internal fun SideRail(route: String?, navController: NavHostController, updateOffered: Boolean) {
    // The tone of the bar: turning the phone moves the tabs, it does not change how they look.
    NavigationRail(containerColor = NavigationBarDefaults.containerColor) {
        // In the middle of the height, where a thumb reaches them on a phone held sideways.
        Spacer(Modifier.weight(1f))
        Tabs(route, navController, updateOffered) { selected, onClick, icon, label ->
            NavigationRailItem(selected = selected, onClick = onClick, icon = icon, label = label)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** One tab as the bar or the rail lays it out, the parts being the same in both. */
private typealias TabSlot = @Composable (
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
) -> Unit

/** @param updateOffered whether the settings tab carries the dot of a newer version */
@Composable
private fun Tabs(route: String?, navController: NavHostController, updateOffered: Boolean, slot: TabSlot) {
    Tab.entries.forEach { tab ->
        val selected = route == tab.route
        val news = tab == Tab.SETTINGS && updateOffered
        slot(
            selected,
            {
                navController.navigate(tab.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            { TabIcon(tab, selected, news) },
            { TabLabel(tab, news) },
        )
    }
}

@Composable
private fun TabIcon(tab: Tab, selected: Boolean, news: Boolean) {
    // The icon of the chosen tab pops a little; the outline turns into the filled shape.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "tabIcon",
    )
    BadgedBox(
        badge = {
            // A small dot in a neutral colour: news, not an alarm.
            if (news) Badge(containerColor = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
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
    }
}

/**
 * The icon slot, dot included, is hidden from accessibility while a label is shown (Material
 * clears its semantics, in the bar and in the rail), so the label tells of the news. A
 * description stands in for the text when it is read out: it names the tab as well.
 */
@Composable
private fun TabLabel(tab: Tab, news: Boolean) {
    Text(tab.label, modifier = if (news) Modifier.semantics { contentDescription = "${tab.label}，$UPDATE_NEWS" } else Modifier)
}
