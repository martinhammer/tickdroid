package com.martinhammer.tickdroid.domain

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Fixed palette of named colors users can assign to tracks. The [key] is
 * what gets persisted; the [container] color is used as the cell tint. [strong] is the same hue
 * darker, for Today's ticked squares in light theme, where a 10dp square has no ✓ or number to
 * tell it apart and the pale [container] is too close to an empty square (decision row 19).
 */
enum class TrackColor(val key: String, val container: Color, val strong: Color) {
    RED("red", Color(0xFFFFCDD2), Color(0xFFD32F2F)),
    ORANGE("orange", Color(0xFFFFCC80), Color(0xFFC75000)),
    YELLOW("yellow", Color(0xFFFFF59D), Color(0xFF8D6E00)),
    GREEN("green", Color(0xFFC5E1A5), Color(0xFF388E3C)),
    TEAL("teal", Color(0xFF80CBC4), Color(0xFF00796B)),
    BLUE("blue", Color(0xFF90CAF9), Color(0xFF1976D2)),
    INDIGO("indigo", Color(0xFF9FA8DA), Color(0xFF3949AB)),
    PURPLE("purple", Color(0xFFCE93D8), Color(0xFF8E24AA)),
    PINK("pink", Color(0xFFF48FB1), Color(0xFFC2185B)),
    GRAY("gray", Color(0xFFCFD8DC), Color(0xFF607D8B));

    /** Black/white foreground picked for adequate contrast against [container]. */
    val onContainer: Color
        get() = if (container.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White

    companion object {
        fun fromKey(key: String?): TrackColor? =
            key?.let { k -> values().firstOrNull { it.key == k } }
    }
}
