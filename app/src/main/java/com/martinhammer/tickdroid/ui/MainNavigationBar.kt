package com.martinhammer.tickdroid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The two main screens, in the bar's order. */
enum class MainTab { Today, Journal }

/**
 * The bottom bar of Today and the Journal, with the web's icons (a ticked box, a month
 * calendar), filled when selected. Each screen passes it as its own Scaffold's `bottomBar`; it
 * takes the navigation-bar inset itself (`NavigationBarDefaults.windowInsets`).
 */
@Composable
internal fun MainNavigationBar(selected: MainTab, onToday: () -> Unit, onJournal: () -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.Today,
            onClick = onToday,
            icon = {
                Icon(
                    if (selected == MainTab.Today) Icons.Filled.CheckBox else Icons.Outlined.CheckBox,
                    contentDescription = null,
                )
            },
            label = { Text("Today") },
            alwaysShowLabel = true,
            modifier = Modifier.testTag("tab-today"),
        )
        NavigationBarItem(
            selected = selected == MainTab.Journal,
            onClick = onJournal,
            icon = {
                Icon(
                    if (selected == MainTab.Journal) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                )
            },
            label = { Text("Journal") },
            alwaysShowLabel = true,
            modifier = Modifier.testTag("tab-journal"),
        )
    }
}
