package com.martinhammer.tickdroid.domain

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Fixed palette of named colors users can assign to tracks. The [key] is
 * what gets persisted; the [container] color is used as the cell tint.
 */
enum class TrackColor(val key: String, val container: Color) {
    RED("red", Color(0xFFFFCDD2)),
    ORANGE("orange", Color(0xFFFFCC80)),
    YELLOW("yellow", Color(0xFFFFF59D)),
    GREEN("green", Color(0xFFC5E1A5)),
    TEAL("teal", Color(0xFF80CBC4)),
    BLUE("blue", Color(0xFF90CAF9)),
    INDIGO("indigo", Color(0xFF9FA8DA)),
    PURPLE("purple", Color(0xFFCE93D8)),
    PINK("pink", Color(0xFFF48FB1)),
    GRAY("gray", Color(0xFFCFD8DC));

    /** Black/white foreground picked for adequate contrast against [container]. */
    val onContainer: Color
        get() = if (container.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White

    companion object {
        fun fromKey(key: String?): TrackColor? =
            key?.let { k -> values().firstOrNull { it.key == k } }
    }
}
