package com.martinhammer.tickdroid.data.sync

import android.content.Context
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Messages from background sync to the user, as toasts over whatever is on screen (text toasts
 * from the background are allowed on Android 12+), so they also reach people who only use the
 * widgets. Open so tests can record them instead.
 */
@Singleton
open class PushNotices @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open suspend fun show(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
