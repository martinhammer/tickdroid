package com.martinhammer.tickdroid.di

import com.martinhammer.tickdroid.data.time.Clock
import com.martinhammer.tickdroid.data.time.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
