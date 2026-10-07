package com.martinhammer.tickdroid

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.martinhammer.tickdroid.data.sync.SyncCoordinator
import com.martinhammer.tickdroid.widget.WidgetRefresher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TickdroidApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncCoordinator: SyncCoordinator
    @Inject lateinit var widgetRefresher: WidgetRefresher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        syncCoordinator.start()
        widgetRefresher.start()
    }
}
