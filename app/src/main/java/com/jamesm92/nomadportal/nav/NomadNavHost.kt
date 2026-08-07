package com.jamesm92.nomadportal.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.connectivity.TcpConnectionsRepository
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.PageAddress
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
import com.jamesm92.nomadportal.ui.browser.BrowserScreen
import com.jamesm92.nomadportal.ui.browser.NodeListScreen
import com.jamesm92.nomadportal.ui.home.HomeScreen
import com.jamesm92.nomadportal.ui.messages.ConversationListScreen
import com.jamesm92.nomadportal.ui.messages.ConversationScreen
import com.jamesm92.nomadportal.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val MESSAGES = "messages"
    const val CONVERSATION = "messages/{contactHash}"
    fun conversation(contactHash: String) = "messages/$contactHash"
    const val NODES = "nodes"
    const val BROWSER = "browser/{nodeHash}"
    fun browser(nodeHash: String) = "browser/$nodeHash"
}

@Composable
fun NomadNavHost(
    interfaceController: InterfaceController,
    messagingRepository: MessagingRepository,
    browserRepository: BrowserRepository,
    settingsRepository: SettingsRepository,
    tcpConnectionsRepository: TcpConnectionsRepository,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                messagingRepository = messagingRepository,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenMessages = { navController.navigate(Routes.MESSAGES) },
                onOpenNodes = { navController.navigate(Routes.NODES) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                interfaceController = interfaceController,
                settingsRepository = settingsRepository,
                messagingRepository = messagingRepository,
                tcpConnectionsRepository = tcpConnectionsRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MESSAGES) {
            ConversationListScreen(
                repository = messagingRepository,
                onOpenConversation = { hash -> navController.navigate(Routes.conversation(hash)) },
                // Always back to the main menu, not whatever screen was
                // previously visited (e.g. Nodes, reached via its own
                // cross-nav link) — explicit user direction: the back
                // arrow here is "home", not "previous".
                onBack = { navController.popBackStack(Routes.HOME, inclusive = false) },
                onOpenNodes = { navController.navigate(Routes.NODES) },
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
                    contact = contact,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.NODES) {
            NodeListScreen(
                repository = browserRepository,
                onOpenNode = { hash -> navController.navigate(Routes.browser(hash)) },
                // Same "back means home, not previous" as Messages above.
                onBack = { navController.popBackStack(Routes.HOME, inclusive = false) },
                onOpenMessages = { navController.navigate(Routes.MESSAGES) },
            )
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
