package com.martinhammer.tickdroid.ui.settings

import android.view.View
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withResourceName
import com.martinhammer.tickdroid.ui.theme.TickdroidTheme
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real MotionEvents (Espresso) on the hosted EmojiPickerView inside its ModalBottomSheet. Compose's
 * own touch injection doesn't reproduce the race between the sheet's drag and the picker's
 * RecyclerView, so these swipe through the window like a finger does.
 */
@OptIn(ExperimentalMaterial3Api::class)
class EmojiPickerSheetTest {

    @get:Rule val rule = createComposeRule()

    private var dismissed = false
    private lateinit var sheet: SheetState

    private fun show() {
        rule.setContent {
            sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            TickdroidTheme { EmojiPickerSheet(sheetState = sheet, onPick = {}, onDismiss = { dismissed = true }) }
        }
        rule.waitForIdle()
        Thread.sleep(SETTLE_MS) // the sheet's opening animation and the picker's first layout
    }

    private fun body() = onView(withResourceName("emoji_picker_body")).inRoot(isDialog())

    private fun scrollOffset(): Int {
        var offset = -1
        body().perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun getDescription() = "read scroll offset"
            override fun perform(uiController: UiController, view: View) {
                offset = view.javaClass.getMethod("computeVerticalScrollOffset").invoke(view) as Int
            }
        })
        return offset
    }

    private fun swipeBody(dyFraction: Float) {
        val from = GeneralLocation.CENTER
        val to = CoordinatesProvider { v ->
            from.calculateCoordinates(v).let { floatArrayOf(it[0], it[1] + v.height * dyFraction) }
        }
        body().perform(GeneralSwipeAction(Swipe.SLOW, from, to, Press.FINGER))
        Thread.sleep(SETTLE_MS)
    }

    @Test fun dragUpOnPicker_scrollsIt_andLeavesTheSheetOpen() {
        show()
        swipeBody(-0.4f)
        assertTrue("picker didn't scroll", scrollOffset() > 0)
        assertEquals(SheetValue.Expanded, sheet.currentValue)
    }

    @Test fun dragDownOnPicker_atTop_doesNotDismiss() {
        show()
        swipeBody(0.4f)
        assertFalse(dismissed)
        assertEquals(SheetValue.Expanded, sheet.currentValue)
    }

    @Test fun dragDownOnTitleRow_stillDismisses() {
        show()
        // Just above the hosted view (its top is the category tabs, not the body).
        val from = CoordinatesProvider { body ->
            var picker: View = body
            while (picker !is EmojiPickerView) picker = picker.parent as View
            val loc = IntArray(2).also { picker.getLocationOnScreen(it) }
            floatArrayOf(loc[0] + picker.width / 2f, loc[1] - 60f)
        }
        val to = CoordinatesProvider { v -> from.calculateCoordinates(v).let { floatArrayOf(it[0], it[1] + 900f) } }
        body().perform(GeneralSwipeAction(Swipe.FAST, from, to, Press.FINGER))
        Thread.sleep(SETTLE_MS)
        assertTrue(dismissed)
    }

    private companion object {
        const val SETTLE_MS = 1500L
    }
}
