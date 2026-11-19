package com.martinhammer.tickdroid.ui.common

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The keyboard going from shown to hidden closes a card docked over it (the note card, the
 * Number card): Android's first Back closes the keyboard and never reaches the app, so without
 * this it would take two. Only while the screen is resumed: going to the home screen also hides the keyboard, and the card must stay then. A
 * physical keyboard never shows the soft one, so the card then stays until Done, Back or a tap
 * outside.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CloseWithKeyboard(onClosed: () -> Unit) {
    val imeVisible = WindowInsets.isImeVisible
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var wasVisible by remember { mutableStateOf(false) }
    val currentOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(imeVisible) {
        when {
            imeVisible -> wasVisible = true
            wasVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> currentOnClosed()
            else -> wasVisible = false
        }
    }
}
