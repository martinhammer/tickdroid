package com.martinhammer.tickdroid.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules background sync work via WorkManager. One-shot push fires after every local
 * write; periodic push runs every ~15 minutes while signed in.
 */
@Singleton
open class SyncScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    // Lazy so that constructing the scheduler doesn't require WorkManager to be initialized
    // up front — the production app initializes WorkManager via Configuration.Provider on first
    // access, and tests that override every scheduling method never trigger this getter.
    private val workManager by lazy { WorkManager.getInstance(context) }

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Enqueue a one-off push attempt. If a worker is already running we append a follow-up
     * (so writes made during a drain still get pushed); a pending-but-not-started worker is
     * replaced to coalesce bursts.
     *
     * Expedited: on battery, JobScheduler holds back ordinary network jobs (on a Samsung S23,
     * Android 16, it waits on mobile data until three are ready, for up to 4 minutes) and
     * defers them in Doze. Once the app is frozen this job is the only thing that wakes it
     * when the network comes back, so a held-back push left the widgets' offline symbol up
     * long after the device was online. Expedited jobs skip that batching and Doze; past their
     * quota this runs as ordinary work.
     */
    open fun schedulePushNow() {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_PUSH_ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Schedule periodic push (idempotent). Call on sign-in. */
    open fun schedulePeriodicPush() {
        val request = PeriodicWorkRequestBuilder<PushWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_PUSH_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel everything we own. Call on sign-out. */
    open fun cancelAll() {
        workManager.cancelUniqueWork(WORK_PUSH_ONCE)
        workManager.cancelUniqueWork(WORK_PUSH_PERIODIC)
    }

    private companion object {
        const val WORK_PUSH_ONCE = "tickdroid.push.once"
        const val WORK_PUSH_PERIODIC = "tickdroid.push.periodic"
    }
}
