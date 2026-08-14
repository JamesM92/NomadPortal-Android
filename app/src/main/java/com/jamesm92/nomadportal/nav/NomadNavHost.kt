package com.jamesm92.nomadportal.nav

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.PageAddress
import com.jamesm92.nomadportal.data.calling.CallRepository
import com.jamesm92.nomadportal.data.calling.CallState
import com.jamesm92.nomadportal.data.hosting.SiteFileRepository
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.data.rnsh.RnshHistoryRepository
import com.jamesm92.nomadportal.data.rnsh.RnshRepository
import com.jamesm92.nomadportal.ui.browser.BrowserScreen
import com.jamesm92.nomadportal.ui.browser.NodeListScreen
import com.jamesm92.nomadportal.ui.calling.CallOverlay
import com.jamesm92.nomadportal.ui.components.MessagesIconWithBadge
import com.jamesm92.nomadportal.ui.components.SettingsIconWithBadge
import com.jamesm92.nomadportal.ui.hosting.SiteFilesScreen
import com.jamesm92.nomadportal.ui.hosting.SitePageEditorScreen
import com.jamesm92.nomadportal.ui.messages.ConversationListScreen
import com.jamesm92.nomadportal.ui.messages.ConversationScreen
import com.jamesm92.nomadportal.ui.network.NetworkScreen
import com.jamesm92.nomadportal.ui.onboarding.OnboardingScreen
import com.jamesm92.nomadportal.ui.settings.SettingsScreen
import com.jamesm92.nomadportal.ui.terminal.RnshTerminalScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// The app's 4 real top-level destinations, shown as bottom-nav tabs —
// see NomadBottomNavigationBar's own doc comment for why (redesign
// Phase B: this app previously had zero NavigationBar anywhere, cross-
// nav was ad hoc top-bar IconButtons, and Settings was only reachable
// via the since-removed Home screen). Network is the newest — interface/
// connection status only (see that screen's own doc comment); a
// standalone Contacts tab and a Network-absorbs-all-announces design
// were both tried and reverted within this same session, per explicit
// direction ("keep the announces in the sites and messages tabs") —
// Sites/Messages keep their own Announces-heard/Users sections. Every
// other route (conversation thread, a site's page, hosted-site file
// management) is a pushed detail screen that hides the bottom bar, same
// as it already had its own back arrow.
private val TOP_LEVEL_ROUTES = setOf(
    Routes.MESSAGES, Routes.NODES, Routes.NETWORK, Routes.SETTINGS,
)

private object Routes {
    const val SETTINGS = "settings"
    const val MESSAGES = "messages"
    const val CONVERSATION = "messages/{contactHash}"
    fun conversation(contactHash: String) = "messages/$contactHash"
    const val NODES = "nodes"
    const val NETWORK = "network"
    const val BROWSER = "browser/{nodeHash}"
    fun browser(nodeHash: String) = "browser/$nodeHash"
    const val SITE_FILES = "site_files"
    // Uri-encoded: a page's relative path contains "/" (subfolders),
    // which a plain Navigation Compose {arg} placeholder would
    // otherwise parse as extra route segments.
    const val SITE_PAGE_EDITOR = "site_editor/{encodedPath}"
    fun sitePageEditor(path: String) = "site_editor/${Uri.encode(path)}"
    // Advanced-section-only, reached from Settings — see
    // RnshRepository's own doc comment for the client-only scope.
    const val RNSH_TERMINAL = "rnsh_terminal"
    // Deliberately absent from TOP_LEVEL_ROUTES -- renders without the
    // bottom nav bar, same as SITE_FILES/CONVERSATION. See NomadNavHost's
    // own start-destination gating for why this isn't just "the first
    // screen in the graph."
    const val ONBOARDING = "onboarding"
}

@Composable
fun NomadNavHost(
    interfaceController: InterfaceController,
    messagingRepository: MessagingRepository,
    browserRepository: BrowserRepository,
    settingsRepository: SettingsRepository,
    tcpConnectionsRepository: TcpConnectionsRepository,
    siteFileRepository: SiteFileRepository,
    callRepository: CallRepository,
    rnshRepository: RnshRepository,
    rnshHistoryRepository: RnshHistoryRepository,
    navController: NavHostController = rememberNavController(),
) {
    // Overlaid on top of everything below, including the bottom nav bar
    // — not a destination of its own, matching a real phone's own "an
    // incoming call interrupts whatever screen you're on" behavior (see
    // CallOverlay's own doc comment).
    val scope = rememberCoroutineScope()
    val callState by callRepository.callState().collectAsState(initial = CallState.IDLE)

    // Powers the bottom nav's Messages/Settings badges — collected once
    // here rather than duplicated per-screen (HomeScreen/NodeListScreen
    // each used to compute their own copy of totalUnread independently
    // before the redesign).
    val conversations by messagingRepository.conversations().collectAsState(initial = emptyList())
    val totalUnread = conversations.sumOf { it.unreadCount }
    val hasDownTcpConnection by interfaceController.hasDownTcpConnection().collectAsState(initial = false)

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Resolved once (a single suspend `first()` read, not a continuous
    // collection) purely to pick NavHost's startDestination -- matches
    // NavHost's own "fixed at first composition" nature, so there's no
    // need to keep observing this after the initial decision. Nullable
    // and gated below so a returning user's app never flashes onboarding
    // for a frame before the real persisted value resolves.
    val hasCompletedOnboarding by produceState<Boolean?>(initialValue = null) {
        value = settingsRepository.hasCompletedOnboarding.first()
    }
    if (hasCompletedOnboarding == null) return

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            if (currentRoute in TOP_LEVEL_ROUTES) {
                NomadBottomNavigationBar(
                    navController = navController,
                    currentRoute = currentRoute,
                    unreadCount = totalUnread,
                    hasDownTcpConnection = hasDownTcpConnection,
                )
            }
        },
    ) { innerPadding ->
    NavHost(
        navController = navController,
        startDestination = if (hasCompletedOnboarding == true) Routes.MESSAGES else Routes.ONBOARDING,
        modifier = Modifier.padding(innerPadding),
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                messagingRepository = messagingRepository,
                interfaceController = interfaceController,
                onComplete = {
                    scope.launch { settingsRepository.setOnboardingComplete(true) }
                    navController.navigate(Routes.MESSAGES) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                interfaceController = interfaceController,
                settingsRepository = settingsRepository,
                messagingRepository = messagingRepository,
                tcpConnectionsRepository = tcpConnectionsRepository,
                onManageHostedPages = { navController.navigate(Routes.SITE_FILES) },
                onOpenRnshTerminal = { navController.navigate(Routes.RNSH_TERMINAL) },
            )
        }
        composable(Routes.RNSH_TERMINAL) {
            RnshTerminalScreen(
                repository = rnshRepository,
                historyRepository = rnshHistoryRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MESSAGES) {
            ConversationListScreen(
                repository = messagingRepository,
                callRepository = callRepository,
                onOpenConversation = { hash -> navController.navigate(Routes.conversation(hash)) },
            )
        }
        composable(Routes.CONVERSATION) { backStackEntry ->
            val contactHash = backStackEntry.arguments?.getString("contactHash")
            val contact = contactHash?.let(messagingRepository::contact)
            // A hash with no matching contact (stale deep link, bad nav
            // arg) has nothing sensible to render — back out rather than
            // crash on a null contact.
            if (contact == null) {
                navController.popBackStack()
            } else {
                ConversationScreen(
                    repository = messagingRepository,
                    settingsRepository = settingsRepository,
                    contact = contact,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.NODES) {
            NodeListScreen(
                repository = browserRepository,
                onOpenNode = { hash -> navController.navigate(Routes.browser(hash)) },
            )
        }
        composable(Routes.NETWORK) {
            NetworkScreen(
                interfaceController = interfaceController,
                tcpConnectionsRepository = tcpConnectionsRepository,
                messagingRepository = messagingRepository,
                callRepository = callRepository,
                browserRepository = browserRepository,
                onOpenConversation = { hash -> navController.navigate(Routes.conversation(hash)) },
                onOpenNode = { hash -> navController.navigate(Routes.browser(hash)) },
            )
        }
        composable(Routes.SITE_FILES) {
            SiteFilesScreen(
                repository = siteFileRepository,
                onOpenPage = { path -> navController.navigate(Routes.sitePageEditor(path)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SITE_PAGE_EDITOR) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("encodedPath")
            val path = encodedPath?.let(Uri::decode)
            if (path == null) {
                navController.popBackStack()
            } else {
                SitePageEditorScreen(
                    repository = siteFileRepository,
                    path = path,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.BROWSER) { backStackEntry ->
            val nodeHash = backStackEntry.arguments?.getString("nodeHash")
            if (nodeHash == null) {
                navController.popBackStack()
            } else {
                BrowserScreen(
                    repository = browserRepository,
                    startAddress = PageAddress(nodeHash),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
    }

    CallOverlay(
        state = callState,
        onAnswer = { scope.launch { callRepository.answerCall() } },
        onHangUp = { scope.launch { callRepository.hangUp() } },
        onDismiss = { scope.launch { callRepository.dismiss() } },
    )
    }
}

/**
 * The app's persistent bottom navigation — Messages/Sites/Network/
 * Settings, the 4 real top-level destinations. Was Messages/Sites/
 * Settings for a while (Home was dropped entirely per explicit direction
 * — its two sections, this device's own LXMF identity and its hosted
 * NomadNet site, moved into Settings instead, see that screen's own doc
 * comment); Network is the newest addition, a Columba UI/UX
 * parity-audit follow-up. Two bigger restructurings were tried and
 * reverted within this same session before settling here: a standalone
 * 5th "Contacts" tab, and Network absorbing every LXMF-peer/NomadNet-
 * node announce — both undone per explicit direction ("keep the
 * announces in the sites and messages tabs"), so Sites/Messages keep
 * their original Announces-heard/Users sections and Network stays
 * interface-status-only.
 *
 * Each tab uses the standard Navigation-Compose bottom-nav click pattern
 * (`popUpTo(startDestination) { saveState = true }` +
 * `launchSingleTop = true` + `restoreState = true`) so switching tabs
 * preserves each one's own scroll position/back stack instead of
 * resetting it — the established convention for this exact UI shape,
 * not something invented here.
 */
@Composable
private fun NomadBottomNavigationBar(
    navController: NavHostController,
    currentRoute: String?,
    unreadCount: Int,
    hasDownTcpConnection: Boolean,
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.MESSAGES,
            onClick = { navigateToTopLevelTab(navController, Routes.MESSAGES) },
            icon = { MessagesIconWithBadge(unreadCount = unreadCount) },
            label = { Text("Messages") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.NODES,
            onClick = { navigateToTopLevelTab(navController, Routes.NODES) },
            icon = { Icon(Icons.Filled.Explore, contentDescription = null) },
            label = { Text("Sites") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.NETWORK,
            onClick = { navigateToTopLevelTab(navController, Routes.NETWORK) },
            icon = { Icon(Icons.Filled.Hub, contentDescription = null) },
            label = { Text("Network") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SETTINGS,
            onClick = { navigateToTopLevelTab(navController, Routes.SETTINGS) },
            icon = { SettingsIconWithBadge(hasWarning = hasDownTcpConnection) },
            label = { Text("Settings") },
        )
    }
}

private fun navigateToTopLevelTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
