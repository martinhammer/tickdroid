package com.martinhammer.tickdroid.di

import android.content.Context
import androidx.room.Room
import com.martinhammer.tickdroid.data.local.MIGRATION_2_3
import com.martinhammer.tickdroid.data.local.MIGRATION_3_4
import com.martinhammer.tickdroid.data.local.MIGRATION_4_5
import com.martinhammer.tickdroid.data.local.NoteDao
import com.martinhammer.tickdroid.data.local.TickDao
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackDao
import com.martinhammer.tickdroid.data.local.TrackPrefsDao
import com.martinhammer.tickdroid.data.local.TrackRunDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TickdroidDatabase =
        Room.databaseBuilder(context, TickdroidDatabase::class.java, TickdroidDatabase.NAME)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideTrackDao(db: TickdroidDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideTickDao(db: TickdroidDatabase): TickDao = db.tickDao()

    @Provides
    fun provideTrackPrefsDao(db: TickdroidDatabase): TrackPrefsDao = db.trackPrefsDao()

    @Provides
    fun provideNoteDao(db: TickdroidDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideTrackRunDao(db: TickdroidDatabase): TrackRunDao = db.trackRunDao()
}
