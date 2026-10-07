package com.martinhammer.tickdroid.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.platform.app.InstrumentationRegistry
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.addOneParams
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.coffee
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.exercise
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.floss
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.rows
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.today
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.toggleParams
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.water
import org.junit.Test

class TodayRowWidgetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun GlanceAppWidgetUnitTest.render(
        model: ListModel,
        badge: SyncBadge = SyncBadge(),
        width: Float = 320f,
    ) {
        setContext(context)
        setAppWidgetSize(DpSize(width.dp, 72.dp))
        provideComposable { GlanceTheme { TodayRowContent(model, badge, appWidgetId = 9) } }
    }

    private val content = ListModel.Content(today, rows, TrackLabels.Full)

    @Test fun columns_show_emoji_or_abbreviation_with_actions() = runGlanceAppWidgetUnitTest {
        render(content)
        onNode(hasText("🏋")).assertExists()
        onNode(hasText("FL")).assertExists()
        onNode(hasText("Exercise")).assertDoesNotExist()
        onNode(hasTestTag("widgetColumn-${exercise.serverId}"))
            .assert(hasRunCallbackClickAction<ToggleTickAction>(toggleParams(exercise)))
        onNode(hasTestTag("widgetColumn-${coffee.serverId}"))
            .assert(hasRunCallbackClickAction<AdjustCounterAction>(addOneParams(coffee)))
        onNode(hasTestTag("widgetRefresh")).assertExists()
        onNode(hasTestTag("widgetEdit")).assertExists()
    }

    @Test fun tracks_that_dont_fit_are_left_out_from_the_end() = runGlanceAppWidgetUnitTest {
        // rowCapacity(160) = 2.
        render(content, width = 160f)
        onNode(hasTestTag("widgetColumn-${exercise.serverId}")).assertExists()
        onNode(hasTestTag("widgetColumn-${coffee.serverId}")).assertExists()
        onNode(hasTestTag("widgetColumn-${floss.serverId}")).assertDoesNotExist()
        onNode(hasTestTag("widgetColumn-${water.serverId}")).assertDoesNotExist()
        onNode(hasTestTag("widgetEdit")).assertExists()
    }

    @Test fun offline_symbol_takes_the_refresh_slot_without_dropping_a_track() = runGlanceAppWidgetUnitTest {
        render(content, badge = SyncBadge(offlinePending = true), width = 160f)
        onNode(hasTestTag("widgetRefresh")).assertDoesNotExist()
        onNode(hasTestTag("widgetOffline")).assert(hasRunCallbackClickAction<RefreshAction>())
        onNode(hasTestTag("widgetColumn-${coffee.serverId}")).assertExists()
    }

    @Test fun syncing_slot_has_no_action() = runGlanceAppWidgetUnitTest {
        render(content, badge = SyncBadge(syncing = true))
        onAllNodes(hasTestTag("widgetRefresh")).assertCountEquals(1)
        onAllNodes(hasTestTag("widgetRefresh")).filter(hasClickAction()).assertCountEquals(0)
    }

    @Test fun no_tracks_keeps_controls() = runGlanceAppWidgetUnitTest {
        render(content.copy(rows = emptyList()))
        onNode(hasText("No tracks to show")).assertExists()
        onNode(hasText("Choose tracks")).assertExists()
        onNode(hasTestTag("widgetRefresh")).assertExists()
        onNode(hasTestTag("widgetEdit")).assertExists()
    }

    @Test fun signed_out_has_no_controls() = runGlanceAppWidgetUnitTest {
        render(ListModel.SignedOut)
        onNode(hasText("Signed out")).assertExists()
        onNode(hasText("Open Tickdroid")).assertExists()
        onNode(hasTestTag("widgetRefresh")).assertDoesNotExist()
        onNode(hasTestTag("widgetEdit")).assertDoesNotExist()
    }
}
