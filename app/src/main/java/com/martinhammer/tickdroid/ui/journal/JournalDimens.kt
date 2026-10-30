package com.martinhammer.tickdroid.ui.journal

import androidx.compose.ui.unit.dp

// Float twins for the Compose-free sizing maths in TracksDownLayout.kt and below (JVM
// unit-tested). The Dp values are derived from these so the two can never drift.
internal const val GridSizingMaxWidthDp = 480f
internal const val RightPadDp = 16f
internal const val MinCellSizeDp = 28f
internal const val MaxCellSizeDp = 64f
internal const val CellGapDp = 6f

internal val DayLabelWidth = 92.dp
internal val CellGap = CellGapDp.dp
internal val RightPad = RightPadDp.dp
internal val MinCellSize = MinCellSizeDp.dp
internal val MaxCellSize = MaxCellSizeDp.dp

// Cap the screen width used for cell sizing so landscape doesn't blow cells out to MaxCellSize
// (which would erase the half-cell peek and make the density setting a no-op). Beyond this
// width the grid stays at its portrait-equivalent size and trailing whitespace fills the rest.
internal val GridSizingMaxWidth = GridSizingMaxWidthDp.dp

/** Below this cell size the note dot shrinks (High density: about 28dp cells in both layouts). */
internal const val SmallCellDp = 32f
internal const val NoteDotDp = 6f
internal const val SmallNoteDotDp = 4f

/**
 * The note dot's size for a cell of [cellSizeDp]: 6dp, or 4dp in a cell under 32dp. It follows
 * the cell, not the density setting, so it holds in both layouts and at any screen width. The
 * dot stays 4dp in from the corner, so a smaller one moves further from the ✓ and, with its
 * ring, still sits inside the cell's 12dp rounded corner.
 */
internal fun noteDotSizeDp(cellSizeDp: Float): Float = if (cellSizeDp < SmallCellDp) SmallNoteDotDp else NoteDotDp
