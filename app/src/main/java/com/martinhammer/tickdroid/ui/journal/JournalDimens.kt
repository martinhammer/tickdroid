package com.martinhammer.tickdroid.ui.journal

import androidx.compose.ui.unit.dp

// Float twins for the Compose-free sizing maths in TracksDownLayout.kt (JVM unit-tested). The
// Dp values below are derived from these so the two can never drift.
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
