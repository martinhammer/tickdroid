package com.martinhammer.tickdroid.widget

import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.martinhammer.tickdroid.data.local.TickdroidDatabase
import com.martinhammer.tickdroid.data.local.TrackEntity
import com.martinhammer.tickdroid.data.repository.TrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.addOneParams
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.coffee
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.exercise
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.floss
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.mood
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.readOnlyParams
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.today
import com.martinhammer.tickdroid.widget.WidgetTestFixtures.toggleParams
import org.junit.Test

class SingleTrackWidgetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun GlanceAppWidgetUnitTest.render(model: ButtonModel, badge: SyncBadge = SyncBadge(), background: Int = 100) {
        setContext(context)
        setAppWidgetSize(DpSize(72.dp, 72.dp))
        provideComposable {
            GlanceTheme {
                CompositionLocalProvider(LocalWidgetBackground provides background) {
                    SingleTrackContent(model, badge, appWidgetId = 3)
                }
            }
        }
    }

    @Test fun background_layer_is_drawn_only_when_translucent_and_the_badge_disc_follows() {
        // Offline, so the badge's disc is drawn too: at 50 it gets its own layer.
        for ((percent, layers) in listOf(100 to 0, 50 to 2, 0 to 0)) {
            runGlanceAppWidgetUnitTest {
                render(ButtonModel.Content(today, exercise), SyncBadge(offlinePending = true), background = percent)
                onAllNodes(hasTestTag("widgetBackgroundLayer")).assertCountEquals(layers)
                onNode(hasTestTag("widgetOffline")).assertExists()
                onNode(hasTestTag("widgetButton"))
                    .assert(hasRunCallbackClickAction<ToggleTickAction>(toggleParams(exercise)))
            }
        }
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

    @Test fun unknown_type_is_read_only_with_a_question_mark_when_ticked() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, mood))
        onNode(hasTextEqualTo("MO")).assertExists()
        onNode(hasTextEqualTo("?")).assertExists()
        onNode(hasText("Choose track")).assertDoesNotExist()
        onNode(hasTestTag("widgetButton"))
            .assert(hasRunCallbackClickAction<ReadOnlyTapAction>(readOnlyParams(mood)))
            .assert(hasContentDescription("Mood, Tickdroid update required"))
    }

    @Test fun unknown_type_without_a_value_draws_an_empty_cell() = runGlanceAppWidgetUnitTest {
        render(ButtonModel.Content(today, mood.copy(value = 0)))
        onNode(hasTextEqualTo("MO")).assertExists()
        onNode(hasTextEqualTo("?")).assertDoesNotExist()
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

    @Test fun archived_in_room_draws_choose_track_restored_draws_the_track() {
        val db = Room.inMemoryDatabaseBuilder(context, TickdroidDatabase::class.java).build()
        try {
            val repo = TrackRepository(db.trackDao())
            val config = WidgetConfig.Button(trackServerId = 101)
            fun model(archivedAt: String?): ButtonModel = runBlocking {
                val existing = db.trackDao().findByServerId(101)
                if (existing == null) {
                    db.trackDao().insert(
                        TrackEntity(serverId = 101, name = "Exercise", type = "boolean", sortOrder = 0, private = false, archivedAt = archivedAt),
                    )
                } else {
                    db.trackDao().update(existing.copy(archivedAt = archivedAt))
                }
                // What WidgetRefresher feeds the models: observeTracks(), active tracks only.
                buildButtonModel(true, today, repo.observeTracks().first(), emptyMap(), emptyMap(), config)
            }

            val archived = model("2026-10-01")
            runGlanceAppWidgetUnitTest {
                render(archived)
                onNode(hasText("Choose track")).assertExists()
            }

            val restored = model(null)
            runGlanceAppWidgetUnitTest {
                render(restored)
                onNode(hasText("Choose track")).assertDoesNotExist()
                onNode(hasTextEqualTo("EX")).assertExists()
            }
        } finally {
            db.close()
        }
    }
}
