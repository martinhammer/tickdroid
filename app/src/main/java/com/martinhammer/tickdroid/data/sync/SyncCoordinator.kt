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
    private val database: TickdroidDatabase,
    private val uiPreferences: UiPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            authRepository.state
                .distinctUntilChanged { a, b -> a::class == b::class }
                .collect { state ->
                    when (state) {
                        is AuthState.SignedIn -> {
                            scheduler.schedulePeriodicPush()
                            scheduler.schedulePushNow()
                        }
                        AuthState.SignedOut -> {
                            scheduler.cancelAll()
                            database.clearAllTables()
                            uiPreferences.clear()
                        }
                        AuthState.Unknown -> Unit
                    }
                }
        }
    }
}
