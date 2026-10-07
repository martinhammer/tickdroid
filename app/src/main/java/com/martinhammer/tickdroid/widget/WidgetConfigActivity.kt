package com.martinhammer.tickdroid.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.martinhammer.tickdroid.MainActivity
import com.martinhammer.tickdroid.data.prefs.ThemeMode
import com.martinhammer.tickdroid.data.prefs.UiPreferences
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal enum class WidgetKind(val title: String) {
    SingleTrack("Choose a track"),
    TodayList("Today (list)"),
    TodayRow("Today (row)");

    fun widget(): GlanceAppWidget = when (this) {
        SingleTrack -> SingleTrackWidget()
        TodayList -> TodayListWidget()
        TodayRow -> TodayRowWidget()
    }

    companion object {
        fun fromProvider(className: String): WidgetKind? = when (className) {
            SingleTrackWidgetReceiver::class.java.name -> SingleTrack
            TodayListWidgetReceiver::class.java.name -> TodayList
            TodayRowWidgetReceiver::class.java.name -> TodayRow
            else -> null
        }
    }
}

/**
 * Widget setup. Opened by the launcher when a widget is placed or reconfigured, by ✎ on the list
 * and the row, and by a Single track widget in its "Choose track" state.
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var uiPreferences: UiPreferences
    @Inject lateinit var store: WidgetConfigStore

    private val viewModel: WidgetConfigViewModel by viewModels()
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // First, so backing out of a fresh placement removes the widget.
        setResult(RESULT_CANCELED, resultValue)

        val provider = appWidgetId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
            ?.let { AppWidgetManager.getInstance(this).getAppWidgetInfo(it)?.provider }
        val kind = provider?.takeIf { it.packageName == packageName }?.let { WidgetKind.fromProvider(it.className) }
        if (kind == null) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            val themeMode by uiPreferences.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            TickdroidTheme(darkTheme = darkTheme) {
                WidgetConfigScreen(
                    kind = kind,
                    viewModel = viewModel,
                    onClose = ::finish,
                    onOpenApp = {
                        // NEW_TASK: this screen runs in its own task (see the manifest), and the
                        // journal belongs in the app's, not in this one.
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    },
                    onSave = { config -> save(kind, appWidgetId, config, resultValue) },
                )
            }
        }
    }

    private fun save(kind: WidgetKind, appWidgetId: Int, config: WidgetConfig, resultValue: Intent) {
        if (saving) return
        saving = true
        // Not viewModelScope: the save, the redraw and the result must all happen even if the
        // user leaves half-way. finish() only once they have.
        lifecycleScope.launch {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store.put(appWidgetId, config) }
                val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
                kind.widget().update(applicationContext, glanceId)
            }
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}
