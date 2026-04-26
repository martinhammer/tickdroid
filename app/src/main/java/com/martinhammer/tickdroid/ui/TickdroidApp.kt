package com.martinhammer.tickdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.ui.auth.AuthScreen
import com.martinhammer.tickdroid.ui.journal.JournalScreen

object Routes {
    const val AUTH = "auth"
    const val JOURNAL = "journal"
}

@Composable
fun TickdroidApp(rootViewModel: RootViewModel = hiltViewModel()) {
    val authState by rootViewModel.authState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    SyncNavToAuthState(navController, authState)

    NavHost(
        navController = navController,
        startDestination = when (authState) {
            is AuthState.SignedIn -> Routes.JOURNAL
            else -> Routes.AUTH
        },
    ) {
        composable(Routes.AUTH) { AuthScreen() }
        composable(Routes.JOURNAL) { JournalScreen() }
    }
}

@Composable
private fun SyncNavToAuthState(navController: NavHostController, authState: AuthState) {
    val target = when (authState) {
        is AuthState.SignedIn -> Routes.JOURNAL
        AuthState.SignedOut -> Routes.AUTH
        AuthState.Unknown -> null
    } ?: return

    val current = navController.currentDestination?.route
    if (current != null && current != target) {
        navController.navigate(target) {
            popUpTo(navController.graph.startDestinationId) { inclusive = true }
            launchSingleTop = true
        }
    }
}
