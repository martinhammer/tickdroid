package com.martinhammer.tickdroid.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.appwidget.AppWidgetHostView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.platform.app.InstrumentationRegistry
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.coffee
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.exercise
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.rows
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.today
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs every widget state through Glance's real RemoteViews translation and inflates the result,
 * which the Glance unit-test harness doesn't do. Catches layouts that only fail once they become
 * RemoteViews (unsupported modifiers, tree depth, child limits).
 */
class WidgetRemoteViewsTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Fixed(private val content: @Composable () -> Unit) : GlanceAppWidget() {
        override val sizeMode: SizeMode = SizeMode.Exact
        override suspend fun provideGlance(context: Context, id: GlanceId) =
            provideContent { GlanceTheme { content() } }
    }

    private suspend fun render(name: String, size: DpSize, night: Boolean = false, content: @Composable () -> Unit): View {
        val themed = if (night) {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            context.createConfigurationContext(config)
        } else {
            context
        }
        val remoteViews = Fixed(content).compose(themed, size = size)
        val density = context.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        var view: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // An AppWidgetHostView parent: the platform only installs collection adapters (the
            // list's rows) under one.
            val parent = AppWidgetHostView(themed)
            view = remoteViews.apply(themed, parent).also {
                parent.addView(it)
                parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, w, h)
                if (DUMP) {
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    parent.draw(Canvas(bitmap))
                    val dir = File(context.getExternalFilesDir(null), "widget-renders").apply { mkdirs() }
                    File(dir, "$name.png").outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                }
            }
        }
        assertTrue("$name did not inflate", view != null)
        return view!!
    }

    private fun View.adapterViews(): List<AdapterView<*>> = when (this) {
        is AdapterView<*> -> listOf(this)
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).adapterViews() }
        else -> emptyList()
    }

    private val list = ListModel.Content(today, rows, TrackLabels.Full)

    @Test fun todayList_allStates() = runTest {
        val size = DpSize(300.dp, 260.dp)
        val full = render("list_full", size) { TodayListContent(list, today, SyncBadge(), 1) }
        // The rows live in a RemoteViews collection; check they made it into its adapter.
        assertEquals(rows.size, full.adapterViews().single().adapter.count)
        render("list_full_dark", size, night = true) { TodayListContent(list, today, SyncBadge(), 1) }
        render("list_short_narrow", DpSize(140.dp, 260.dp)) {
            TodayListContent(list.copy(labels = TrackLabels.Short), today, SyncBadge(offlinePending = true), 1)
        }
        render("list_mid", DpSize(200.dp, 260.dp)) { TodayListContent(list, today, SyncBadge(syncing = true), 1) }
        render("list_empty", size) { TodayListContent(list.copy(rows = emptyList()), today, SyncBadge(), 1) }
        render("list_signed_out", size) { TodayListContent(ListModel.SignedOut, today, SyncBadge(), 1) }
    }

    @Test fun todayRow_allStates() = runTest {
        render("row", DpSize(320.dp, 72.dp)) { TodayRowContent(list, SyncBadge(), 2) }
        render("row_dark_offline", DpSize(320.dp, 72.dp), night = true) { TodayRowContent(list, SyncBadge(offlinePending = true), 2) }
        render("row_short_height", DpSize(320.dp, 56.dp)) { TodayRowContent(list, SyncBadge(), 2) }
        // Wide enough for more than one group of eight columns.
        val many = (1..20).map { exercise.copy(serverId = 1000L + it, name = "Track $it", emoji = null, value = it % 2) }
        render("row_wide", DpSize(900.dp, 72.dp)) { TodayRowContent(list.copy(rows = many), SyncBadge(), 2) }
        render("row_empty", DpSize(320.dp, 72.dp)) { TodayRowContent(list.copy(rows = emptyList()), SyncBadge(), 2) }
        render("row_signed_out", DpSize(320.dp, 72.dp)) { TodayRowContent(ListModel.SignedOut, SyncBadge(), 2) }
    }

    @Test fun singleTrack_allStates() = runTest {
        val size = DpSize(72.dp, 72.dp)
        render("single_done", size) { SingleTrackContent(ButtonModel.Content(today, exercise), SyncBadge(), 3) }
        render("single_counter_offline", size) { SingleTrackContent(ButtonModel.Content(today, coffee), SyncBadge(offlinePending = true), 3) }
        render("single_not_done_dark", size, night = true) {
            SingleTrackContent(ButtonModel.Content(today, exercise.copy(value = 0)), SyncBadge(), 3)
        }
        render("single_abbreviation", size) { SingleTrackContent(ButtonModel.Content(today, WidgetTestFixtures.floss), SyncBadge(), 3) }
        render("single_choose", size) { SingleTrackContent(ButtonModel.ChooseTrack, SyncBadge(), 3) }
        render("single_signed_out", size) { SingleTrackContent(ButtonModel.SignedOut, SyncBadge(), 3) }
    }

    private companion object {
        /** Flip on to write PNGs to the app's external files dir for a visual check. */
        const val DUMP = false
    }
}
