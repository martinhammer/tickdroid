package com.martinhammer.tickdroid.ui

import androidx.activity.compose.BackHandler
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
import com.martinhammer.tickdroid.ui.today.TodayScreen
import com.martinhammer.tickdroid.ui.settings.AccountSettingsScreen
import com.martinhammer.tickdroid.ui.settings.AppSettingsScreen
import com.martinhammer.tickdroid.ui.settings.TrackDetailScreen
import com.martinhammer.tickdroid.ui.settings.TracksSettingsScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import java.time.LocalDate

object Routes {
    const val AUTH = "auth"
    const val TODAY = "today"
    const val JOURNAL = "journal"
    const val SETTINGS_ACCOUNT = "settings/account"
    const val SETTINGS_APP = "settings/app"
    const val SETTINGS_TRACKS = "settings/tracks"
    const val SETTINGS_TRACK_DETAIL = "settings/tracks/{localId}"
    const val ABOUT = "about"
    fun trackDetail(localId: Long): String = "settings/tracks/$localId"
}

/** Navigation result: the server id of a track the track screen has just archived. */
private const val JUST_ARCHIVED = "justArchivedServerId"

/**
 * Navigation results for [Routes.TODAY]: the epoch day to show (the bar's Today item, or a
 * Journal day label), and whether a day label opened it (then Back returns to the Journal).
 * Written into TODAY's own entry, which is always the root while signed in.
 */
private const val OPEN_DAY = "openDay"
private const val OPEN_FROM_JOURNAL = "openFromJournal"

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
    // signed-in user's first composition already reads SignedIn and starts on TODAY. Only a
    // start from nothing gets here: coming back to the app (also after Android restored it)
    // keeps the screen that was left, through the platform's own saved state.
    val startDestination = remember {
        if (authState is AuthState.SignedIn) Routes.TODAY else Routes.AUTH
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.AUTH) {
            AuthScreen(onOpenAbout = { navController.navigate(Routes.ABOUT) })
        }
        composable(Routes.TODAY) { entry ->
            val openDay by entry.savedStateHandle
                .getStateFlow<Long?>(OPEN_DAY, null)
                .collectAsStateWithLifecycle()
            TodayScreen(
                openDay = openDay,
                openFromJournal = entry.savedStateHandle.get<Boolean>(OPEN_FROM_JOURNAL) ?: false,
                onOpenDayHandled = {
                    entry.savedStateHandle[OPEN_DAY] = null
                    entry.savedStateHandle.remove<Boolean>(OPEN_FROM_JOURNAL)
                },
                onOpenJournal = { navController.openTab(Routes.JOURNAL) },
                onOpenAccount = { navController.navigate(Routes.SETTINGS_ACCOUNT) },
                onOpenAppSettings = { navController.navigate(Routes.SETTINGS_APP) },
                onOpenTracksSettings = { navController.navigate(Routes.SETTINGS_TRACKS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.JOURNAL) {
            // Back goes to Today, keeping the Journal as it was for the bar's Journal item
            // (a plain pop would discard its saved state).
            BackHandler { navController.openTab(Routes.TODAY) }
            JournalScreen(
                onOpenToday = { today -> navController.openToday(today, fromJournal = false) },
                onOpenDay = { day -> navController.openToday(day, fromJournal = true) },
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
        composable(Routes.SETTINGS_TRACKS) { entry ->
            val justArchived by entry.savedStateHandle
                .getStateFlow<Long?>(JUST_ARCHIVED, null)
                .collectAsStateWithLifecycle()
            TracksSettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenTrack = { localId -> navController.navigate(Routes.trackDetail(localId)) },
                justArchived = justArchived,
                onJustArchivedHandled = { entry.savedStateHandle[JUST_ARCHIVED] = null },
            )
        }
        composable(
            route = Routes.SETTINGS_TRACK_DETAIL,
            arguments = listOf(navArgument("localId") { type = NavType.LongType }),
        ) {
            TrackDetailScreen(
                onBack = { navController.popBackStack() },
                // Tells the list to open its Archived row and scroll to the track.
                onArchived = { serverId ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(JUST_ARCHIVED, serverId)
                    navController.popBackStack()
                },
            )
        }
    }
}

/**
 * Opens one of the bar's screens, the standard bottom-navigation pattern: everything above
 * TODAY is popped with its state saved, and a screen opened again gets its saved state back
 * (the Journal's layout, scroll and loaded days). Pops by route, never
 * `graph.startDestinationId`: the start destination is frozen at the process's first auth
 * state (AUTH after a sign-in), and popping it would be a no-op.
 */
private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(Routes.TODAY) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Shows [day] on Today, as a navigation result written into TODAY's own entry. */
private fun NavHostController.openToday(day: LocalDate, fromJournal: Boolean) {
    val handle = getBackStackEntry(Routes.TODAY).savedStateHandle
    // The flag first: the screen reads it when the day arrives.
    handle[OPEN_FROM_JOURNAL] = fromJournal
    handle[OPEN_DAY] = day.toEpochDay()
    openTab(Routes.TODAY)
}

@Composable
private fun SyncNavToAuthState(navController: NavHostController, authState: AuthState) {
    LaunchedEffect(authState) {
        val target = when (authState) {
            is AuthState.SignedIn -> Routes.TODAY
            AuthState.SignedOut -> Routes.AUTH
            AuthState.Unknown -> return@LaunchedEffect
        }
        val current = navController.currentDestination?.route ?: return@LaunchedEffect
        val onAuthRoute = current == Routes.AUTH
        val needsSwitch = (target == Routes.AUTH && !onAuthRoute) ||
            (target == Routes.TODAY && onAuthRoute)
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
            // The Journal state saved by the bar belongs to the account that just signed out.
            if (target == Routes.AUTH) navController.clearBackStack(Routes.JOURNAL)
        }
    }
}
