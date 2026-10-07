package com.martinhammer.tickdroid.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.platform.app.InstrumentationRegistry
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.addOneParams
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.coffee
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.exercise
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.floss
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.today
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.toggleParams
import org.junit.Test

class SingleTrackWidgetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun GlanceAppWidgetUnitTest.render(model: ButtonModel, badge: SyncBadge = SyncBadge()) {
        setContext(context)
        setAppWidgetSize(DpSize(72.dp, 72.dp))
        provideComposable { GlanceTheme { SingleTrackContent(model, badge, appWidgetId = 3) } }
    }

    @Test fun yes_no_toggles_and_labels_with_the_emoji_only() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, exercise))
        onNode(hasTextEqualTo("🏋")).assertExists()
        onNode(hasText("Exercise")).assertDoesNotExist()
        onNode(hasTestTag("widgetButton"))
            .assert(hasRunCallbackClickAction<ToggleTickAction>(toggleParams(exercise)))
            .assert(hasContentDescription("Exercise, done"))
    }

    @Test fun counter_adds_one_and_shows_its_value() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, coffee))
        onNode(hasText("2")).assertExists()
        onNode(hasTestTag("widgetButton"))
            .assert(hasRunCallbackClickAction<AdjustCounterAction>(addOneParams(coffee)))
    }

    @Test fun no_emoji_labels_with_the_abbreviation() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, floss))
        onNode(hasTextEqualTo("FL")).assertExists()
        onNode(hasText("Floss")).assertDoesNotExist()
        // The full name is still there for TalkBack.
        onNode(hasTestTag("widgetButton")).assert(hasContentDescription("Floss, not done"))
    }

    @Test fun choose_track_and_signed_out_share_the_question_tile() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.ChooseTrack)
        onNode(hasText("?")).assertExists()
        onNode(hasText("Choose track")).assertExists()
    }

    @Test fun signed_out() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.SignedOut)
        onNode(hasText("?")).assertExists()
        onNode(hasText("Signed out")).assertExists()
    }

    @Test fun offline_badge_only_while_pending() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, exercise), badge = SyncBadge(offlinePending = true))
        onNode(hasTestTag("widgetOffline")).assertExists()
    }

    @Test fun offline_badge_hides_while_syncing_like_the_list_and_row() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, exercise), badge = SyncBadge(syncing = true, offlinePending = true))
        onNode(hasTestTag("widgetOffline")).assertDoesNotExist()
    }

    @Test fun no_offline_badge_when_synced() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, exercise))
        onNode(hasTestTag("widgetOffline")).assertDoesNotExist()
    }
}
