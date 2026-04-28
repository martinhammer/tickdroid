package com.martinhammer.tickdroid.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
class SyncScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Enqueue a one-off push attempt. If a worker is already running we append a follow-up
     * (so writes made during a drain still get pushed); a pending-but-not-started worker is
     * replaced to coalesce bursts.
     */
    fun schedulePushNow() {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_PUSH_ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Schedule periodic push (idempotent). Call on sign-in. */
    fun schedulePeriodicPush() {
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
    fun cancelAll() {
        workManager.cancelUniqueWork(WORK_PUSH_ONCE)
        workManager.cancelUniqueWork(WORK_PUSH_PERIODIC)
    }

    private companion object {
        const val WORK_PUSH_ONCE = "tickdroid.push.once"
        const val WORK_PUSH_PERIODIC = "tickdroid.push.periodic"
    }
}
