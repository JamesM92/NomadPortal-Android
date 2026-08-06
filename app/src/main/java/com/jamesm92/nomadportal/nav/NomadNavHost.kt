package com.jamesm92.nomadportal.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jamesm92.nomadportal.connectivity.InterfaceController
import com.jamesm92.nomadportal.data.messaging.MessagingRepository
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
}

@Composable
fun NomadNavHost(
    interfaceController: InterfaceController,
    messagingRepository: MessagingRepository,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenMessages = { navController.navigate(Routes.MESSAGES) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                interfaceController = interfaceController,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MESSAGES) {
            ConversationListScreen(
                repository = messagingRepository,
                onOpenConversation = { hash -> navController.navigate(Routes.conversation(hash)) },
                onBack = { navController.popBackStack() },
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
    }
}
