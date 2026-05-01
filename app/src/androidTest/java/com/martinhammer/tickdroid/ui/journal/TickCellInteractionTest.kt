package com.martinhammer.tickdroid.ui.journal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.martinhammer.tickdroid.domain.Tick
import com.martinhammer.tickdroid.domain.Track
import com.martinhammer.tickdroid.domain.TrackType
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class TickCellInteractionTest {

    @get:Rule val rule = createComposeRule()

    private val today = LocalDate.parse("2026-04-30")
    private val booleanTrack = Track(localId = 1L, serverId = 10L, name = "Meditate", type = TrackType.BOOLEAN, sortOrder = 0, private = false)
    private val counterTrack = Track(localId = 2L, serverId = 11L, name = "Pages", type = TrackType.COUNTER, sortOrder = 1, private = false)

    @Test fun tap_emptyBoolean_invokesToggle() {
        var toggled = 0
        rule.setContent {
            TickdroidTheme {
                TickCell(
                    track = booleanTrack,
                    tick = null,
                    prefs = null,
                    cellSize = 56.dp,
                    editable = true,
                    onToggleBoolean = { toggled++ },
                    onAdjustCounter = { fail("counter not expected") },
                )
            }
        }
        rule.onRoot().performClick()
        assertEquals(1, toggled)
    }

    @Test fun tap_counter_increments() {
        var lastDelta: Int? = null
        rule.setContent {
            TickdroidTheme {
                TickCell(
                    track = counterTrack,
                    tick = null,
                    prefs = null,
                    cellSize = 56.dp,
                    editable = true,
                    onToggleBoolean = { fail("boolean not expected") },
                    onAdjustCounter = { lastDelta = it },
                )
            }
        }
        rule.onRoot().performClick()
        assertEquals(1, lastDelta)
    }

    @Test fun longPress_counter_atValueAboveZero_decrements() {
        var lastDelta: Int? = null
        rule.setContent {
            TickdroidTheme {
                TickCell(
                    track = counterTrack,
                    tick = Tick(trackLocalId = 2L, date = today, value = 3),
                    prefs = null,
                    cellSize = 56.dp,
                    editable = true,
                    onToggleBoolean = { fail("boolean not expected") },
                    onAdjustCounter = { lastDelta = it },
                )
            }
        }
        rule.onRoot().performTouchInput { longClick() }
        assertEquals(-1, lastDelta)
    }

    @Test fun longPress_counter_atValueZero_isNoOp() {
        var lastDelta: Int? = null
        rule.setContent {
            TickdroidTheme {
                TickCell(
                    track = counterTrack,
                    tick = null,
                    prefs = null,
                    cellSize = 56.dp,
                    editable = true,
                    onToggleBoolean = { fail("boolean not expected") },
                    onAdjustCounter = { lastDelta = it },
                )
            }
        }
        rule.onRoot().performTouchInput { longClick() }
        assertNull(lastDelta)
    }

    @Test fun tap_lockedDay_doesNotInvokeCallbacks() {
        // Locked-cell taps surface a Toast. We don't assert the toast text here (asserting on
        // android.widget.Toast from instrumentation is brittle); we cover the policy by
        // confirming neither callback fires when editable=false.
        var toggled = 0
        var counter: Int? = null
        rule.setContent {
            TickdroidTheme {
                TickCell(
                    track = booleanTrack,
                    tick = null,
                    prefs = null,
                    cellSize = 56.dp,
                    editable = false,
                    onToggleBoolean = { toggled++ },
                    onAdjustCounter = { counter = it },
                )
            }
        }
        rule.onRoot().performClick()
        assertEquals(0, toggled)
        assertNull(counter)
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
