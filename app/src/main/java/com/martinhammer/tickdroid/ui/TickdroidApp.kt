package com.martinhammer.tickdroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.prefs.ThemeMode
import com.martinhammer.tickdroid.ui.about.AboutScreen
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
    const val ABOUT = "about"
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

    // Captured once, deliberately. NavHost rebuilds its graph whenever `startDestination`
    // changes, and NavController.setGraph pops the whole back stack when handed a new graph
    // instance — destroying the current destination's ViewModelStore. Deriving this from a
    // changing authState made the first sign-in tear down JournalViewModel mid-pull, which
    // cancelled a Room transaction and crashed the process. SyncNavToAuthState is the single
    // driver for auth transitions; this only picks the entry point.
    //
    // Safe to freeze: AuthRepository resolves the real state in its constructor, so a returning
    // signed-in user's first composition already reads SignedIn and starts on JOURNAL.
    val startDestination = remember {
        if (authState is AuthState.SignedIn) Routes.JOURNAL else Routes.AUTH
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.AUTH) {
            AuthScreen(onOpenAbout = { navController.navigate(Routes.ABOUT) })
        }
        composable(Routes.JOURNAL) {
            JournalScreen(
                onOpenAccount = { navController.navigate(Routes.SETTINGS_ACCOUNT) },
                onOpenAppSettings = { navController.navigate(Routes.SETTINGS_APP) },
                onOpenTracksSettings = { navController.navigate(Routes.SETTINGS_TRACKS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
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
                // Pop the whole graph, not the start destination. `startDestination` is now
                // frozen at the process's initial auth state, so it no longer tracks where the
                // user actually is: popping it is a no-op whenever it isn't on the back stack,
                // which left the previous screen (and its ViewModel) alive underneath the new
                // one. That resurfaced the signed-out user's typed credentials — app password
                // included — on the login screen, and left AUTH under JOURNAL so Back from the
                // journal landed on the login screen while signed in.
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
