package com.martinhammer.tickdroid.data.sync

import com.martinhammer.tickdroid.data.auth.AuthRepository
import com.martinhammer.tickdroid.data.auth.AuthState
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reacts to [AuthRepository] state changes:
 *  - On sign-in: schedules the periodic push and kicks a one-shot drain.
 *  - On sign-out: cancels scheduled work, wipes the local database, and resets app preferences
 *    so the next user doesn't inherit any cached data, queued writes, or UI settings.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val authRepository: AuthRepository,
    private val scheduler: SyncScheduler,
    private val syncManager: SyncManager,
    private val database: TickdroidDatabase,
    private val uiPreferences: UiPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** Test-only: cancel the scope so the coordinator stops observing auth state. */
    internal fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            var previous: AuthState? = null
            authRepository.state
                .distinctUntilChanged { a, b -> a::class == b::class }
                .collect { state ->
                    when (state) {
                        is AuthState.SignedIn -> {
                            scheduler.schedulePeriodicPush()
                            scheduler.schedulePushNow()
                        }
                        AuthState.SignedOut -> {
                            // Only wipe on a real SignedIn -> SignedOut transition. The initial
                            // SignedOut on a fresh install has nothing to wipe, and closing the
                            // Hilt-singleton DB here would permanently break the next sign-in.
                            if (previous is AuthState.SignedIn) {
                                scheduler.cancelAll()
                                // Under the sync lock: pulls run on an application-lifetime
                                // scope (SyncManager.schedulePull), so one can still be inside
                                // db.withTransaction when the user signs out.
                                //
                                // clearAllTables(), NOT close() + deleteDatabase(). Closing is
                                // permanent for a Hilt @Singleton: nothing recreates the
                                // instance, so every consumer (SyncManager, the repositories,
                                // the injected DAOs) keeps a reference to a dead database for
                                // the rest of the process. Signing back in without killing the
                                // app then crashed on the first query with
                                // "SQLException: connection is closed".
                                //
                                // The schema-mismatch worry that originally motivated file
                                // deletion doesn't justify it: there is no destructive
                                // migration fallback, so a stale on-disk schema crashes at
                                // startup long before anyone reaches sign-out.
                                syncManager.runExclusive {
                                    database.clearAllTables()
                                }
                                uiPreferences.clear()
                            }
                        }
                        AuthState.Unknown -> Unit
                    }
                    previous = state
                }
        }
    }
}
