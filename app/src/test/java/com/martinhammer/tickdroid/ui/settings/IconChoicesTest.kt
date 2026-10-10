package com.martinhammer.tickdroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class IconChoicesTest {

    private val lifter = "🏋️" // 🏋️
    private val lifterBare = "🏋" // 🏋 without U+FE0F
    private val books = "📚" // 📚
    private val target = "🎯" // 🎯, not a habit

    @Test fun `null selects the letters`() {
        val choices = iconChoices(null)
        assertEquals(12, choices.size)
        assertEquals(IconChoice.Letters, choices[0])
        assertEquals(0, selectedIndex(choices, null))
    }

    @Test fun `more is second, never selected`() {
        val choices = iconChoices(target)
        assertEquals(IconChoice.More, choices[1])
        assertEquals(1, choices.count { it == IconChoice.More })
    }

    @Test fun `a habit selects its circle and adds nothing`() {
        val choices = iconChoices(books)
        assertEquals(12, choices.size)
        assertEquals(4, selectedIndex(choices, books))
    }

    @Test fun `a habit stored without the variation selector still selects its circle`() {
        val choices = iconChoices(lifterBare)
        assertEquals(12, choices.size)
        assertEquals(2, selectedIndex(choices, lifterBare))
        assertEquals(IconChoice.Emoji(lifter), choices[2])
    }

    @Test fun `a custom emoji sits after more, selected`() {
        val choices = iconChoices(target)
        assertEquals(13, choices.size)
        assertEquals(IconChoice.Emoji(target), choices[2])
        assertEquals(2, selectedIndex(choices, target))
    }

    @Test fun `the habits are exactly the ten, in order`() {
        assertEquals(
            listOf("🏋️", "💧", "📚", "🧘", "🚶", "✍️", "😴", "🚫", "🤸", "💊"),
            HABIT_EMOJIS,
        )
    }
}
