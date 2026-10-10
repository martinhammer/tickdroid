package com.martinhammer.tickdroid.ui.settings

// Pure model of the track screen's icon row. JVM-tested.

/**
 * The ten habit emojis, fixed, in rank order. Written fully qualified (with U+FE0F where the
 * emoji has one); compare with [sameEmoji], since icons saved by the old text field or by the
 * picker may lack the variation selector.
 */
internal val HABIT_EMOJIS = listOf(
    "🏋️", // 🏋️
    "💧", // 💧
    "📚", // 📚
    "🧘", // 🧘
    "🚶", // 🚶
    "✍️", // ✍️
    "😴", // 😴
    "🚫", // 🚫
    "🤸", // 🤸
    "💊", // 💊
)

internal sealed interface IconChoice {
    /** "No icon": the track's two letters. */
    data object Letters : IconChoice
    /** "⋯": opens the full emoji picker. Second, so it's in view without scrolling. Never selected. */
    data object More : IconChoice
    data class Emoji(val emoji: String) : IconChoice
}

/** Letters, "⋯", then [current] if it's set and not one of the habits, then the ten habits. */
internal fun iconChoices(current: String?): List<IconChoice> = buildList {
    add(IconChoice.Letters)
    add(IconChoice.More)
    if (current != null && HABIT_EMOJIS.none { sameEmoji(it, current) }) add(IconChoice.Emoji(current))
    HABIT_EMOJIS.forEach { add(IconChoice.Emoji(it)) }
}

/** Index of the choice matching [current] in [choices]: the letters for null. */
internal fun selectedIndex(choices: List<IconChoice>, current: String?): Int {
    if (current == null) return choices.indexOf(IconChoice.Letters)
    return choices.indexOfFirst { it is IconChoice.Emoji && sameEmoji(it.emoji, current) }
}

/** Equal ignoring the emoji variation selector (U+FE0F). */
internal fun sameEmoji(a: String, b: String): Boolean =
    a.replace(VARIATION_SELECTOR, "") == b.replace(VARIATION_SELECTOR, "")

private const val VARIATION_SELECTOR = "️"
