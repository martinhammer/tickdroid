package com.martinhammer.tickdroid.widget

import android.content.Context
import com.martinhammer.tickdroid.data.sync.SyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Glance creates widgets, receivers and action callbacks itself, so they can't take constructor
 * injection; they reach the singletons through this instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetTickHandler(): WidgetTickHandler
    fun widgetConfigStore(): WidgetConfigStore
    fun widgetRefresher(): WidgetRefresher
    fun syncScheduler(): SyncScheduler
}

internal fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)
