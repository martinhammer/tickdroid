package com.martinhammer.tickdroid.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.test.platform.app.InstrumentationRegistry
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.addOneParams
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.coffee
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.exercise
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.rows
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.today
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.toggleParams
import org.junit.Test

class TodayListWidgetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun GlanceAppWidgetUnitTest.render(
        model: ListModel,
        badge: SyncBadge = SyncBadge(),
        size: DpSize = DpSize(300.dp, 220.dp),
    ) {
        setContext(context)
        setAppWidgetSize(size)
        provideComposable { GlanceTheme { TodayListContent(model, today, badge, appWidgetId = 7) } }
    }

    private val content = ListModel.Content(today, rows, TrackLabels.Full)

    @Test fun header_and_rows_render() = runGlanceAppWidgetUnitTest {
        render(content)
        onNode(hasTextEqualTo("Tickdroid")).assertExists()
        onNode(hasText("Exercise")).assertExists()
        onNode(hasText("Glasses of water")).assertExists()
        onNode(hasTestTag("widgetRefresh")).assertExists()
        onNode(hasTestTag("widgetEdit")).assertExists()
        onNode(hasTestTag("widgetOffline")).assertDoesNotExist()
    }

    @Test fun rows_carry_the_tick_actions() = runGlanceAppWidgetUnitTest {
        render(content)
        onNode(hasTestTag("widgetRow-${exercise.serverId}"))
            .assert(hasRunCallbackClickAction<ToggleTickAction>(toggleParams(exercise)))
        onNode(hasTestTag("widgetRow-${coffee.serverId}"))
            .assert(hasRunCallbackClickAction<AdjustCounterAction>(addOneParams(coffee)))
    }

    @Test fun short_labels_show_emoji_or_abbreviation_not_names() = runGlanceAppWidgetUnitTest {
        render(content.copy(labels = TrackLabels.Short))
        onNode(hasText("Exercise")).assertDoesNotExist()
        onNode(hasText("🏋")).assertExists()
        onNode(hasText("FL")).assertExists()
    }

    @Test fun narrow_header_drops_the_title() = runGlanceAppWidgetUnitTest {
        render(content, size = DpSize(200.dp, 220.dp))
        onNode(hasTextEqualTo("Tickdroid")).assertDoesNotExist()
        onNode(hasTestTag("widgetEdit")).assertExists()
    }

    @Test fun offline_symbol_replaces_refresh_and_retries() = runGlanceAppWidgetUnitTest {
        render(content, badge = SyncBadge(offlinePending = true))
        onNode(hasTestTag("widgetRefresh")).assertDoesNotExist()
        onNode(hasTestTag("widgetOffline")).assert(hasRunCallbackClickAction<RefreshAction>())
    }

    @Test fun refresh_carries_the_refresh_action() = runGlanceAppWidgetUnitTest {
        render(content)
        onNode(hasTestTag("widgetRefresh")).assert(hasRunCallbackClickAction<RefreshAction>())
    }

    @Test fun syncing_fades_the_slot_and_drops_its_action() = runGlanceAppWidgetUnitTest {
        render(content, badge = SyncBadge(syncing = true, offlinePending = true))
        onNode(hasTestTag("widgetOffline")).assertDoesNotExist()
        onNode(hasTestTag("widgetRefresh")).assertExists()
        onAllNodes(hasTestTag("widgetRefresh")).filter(hasClickAction()).assertCountEquals(0)
    }

    @Test fun no_tracks_keeps_header_and_controls() = runGlanceAppWidgetUnitTest {
        render(content.copy(rows = emptyList()))
        onNode(hasTextEqualTo("Tickdroid")).assertExists()
        onNode(hasText("No tracks to show")).assertExists()
        onNode(hasText("Choose tracks")).assertExists()
        onNode(hasTestTag("widgetRefresh")).assertExists()
        onNode(hasTestTag("widgetEdit")).assertExists()
    }

    @Test fun signed_out_keeps_header_without_controls() = runGlanceAppWidgetUnitTest {
        render(ListModel.SignedOut)
        onNode(hasTextEqualTo("Tickdroid")).assertExists()
        onNode(hasText("Signed out")).assertExists()
        onNode(hasText("Open Tickdroid")).assertExists()
        onNode(hasTestTag("widgetRefresh")).assertDoesNotExist()
        onNode(hasTestTag("widgetOffline")).assertDoesNotExist()
        onNode(hasTestTag("widgetEdit")).assertDoesNotExist()
    }
}
