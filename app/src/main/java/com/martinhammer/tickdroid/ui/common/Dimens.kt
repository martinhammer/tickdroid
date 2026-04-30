package com.martinhammer.tickdroid.ui.common

import androidx.compose.ui.unit.dp

// Max width for form controls (text fields, buttons, switches, segmented selectors). Keeps
// inputs at a comfortable size on landscape phones / tablets instead of stretching edge-to-edge.
val MaxContentWidth = 480.dp

// Below this screen height (typical landscape on a phone), screens drop the LargeTopAppBar in
// favor of a small TopAppBar to recover vertical space.
const val CompactHeightThresholdDp = 480
