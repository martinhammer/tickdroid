package com.martinhammer.tickdroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.prefs.ThemeMode
import com.martinhammer.tickdroid.ui.auth.AuthScreen
import com.martinhammer.tickdroid.ui.journal.JournalScreen
import com.martinhammer.tickdroid.ui.settings.AccountSettingsScreen
import com.martinhammer.tickdroid.ui.settings.AppSettingsScreen
import com.martinhammer.tickdroid.ui.settings.TrackDetailScreen
import com.martinhammer.tickdroid.ui.settings.TracksSettingsScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme

object Routes {
    const val AUTH = "auth"
    const val JOURNAL = "journal"
    const val SETTINGS_ACCOUNT = "settings/account"
    const val SETTINGS_APP = "settings/app"
    const val SETTINGS_TRACKS = "settings/tracks"
    const val SETTINGS_TRACK_DETAIL = "settings/tracks/{localId}"
    fun trackDetail(localId: Long): String = "settings/tracks/$localId"
}

@Composable
fun TickdroidApp(rootViewModel: RootViewModel = hiltViewModel()) {
    val authState by rootViewModel.authState.collectAsStateWithLifecycle()
    val themeMode by rootViewModel.themeMode.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    TickdroidTheme(darkTheme = darkTheme) {
        TickdroidNav(authState = authState)
    }
}

@Composable
private fun TickdroidNav(authState: AuthState) {
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
        composable(Routes.JOURNAL) {
            JournalScreen(
                onOpenAccount = { navController.navigate(Routes.SETTINGS_ACCOUNT) },
                onOpenAppSettings = { navController.navigate(Routes.SETTINGS_APP) },
                onOpenTracksSettings = { navController.navigate(Routes.SETTINGS_TRACKS) },
            )
        }
        composable(Routes.SETTINGS_ACCOUNT) {
            AccountSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_APP) {
            AppSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_TRACKS) {
            TracksSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrack = { localId -> navController.navigate(Routes.trackDetail(localId)) },
            )
        }
        composable(
            route = Routes.SETTINGS_TRACK_DETAIL,
            arguments = listOf(navArgument("localId") { type = NavType.LongType }),
        ) {
            TrackDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun SyncNavToAuthState(navController: NavHostController, authState: AuthState) {
    LaunchedEffect(authState) {
        val target = when (authState) {
            is AuthState.SignedIn -> Routes.JOURNAL
            AuthState.SignedOut -> Routes.AUTH
            AuthState.Unknown -> return@LaunchedEffect
        }
        val current = navController.currentDestination?.route ?: return@LaunchedEffect
        val onAuthRoute = current == Routes.AUTH
        val needsSwitch = (target == Routes.AUTH && !onAuthRoute) ||
            (target == Routes.JOURNAL && onAuthRoute)
        if (needsSwitch) {
            navController.navigate(target) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
