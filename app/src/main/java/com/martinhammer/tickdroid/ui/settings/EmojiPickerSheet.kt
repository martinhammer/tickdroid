package com.martinhammer.tickdroid.ui.settings

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView

/**
 * Claims every touch that starts on the picker for the picker. Compose hands a hosted View its
 * move events first but lets an ancestor take them unless the View has asked it not to, and
 * RecyclerView only asks once it is already scrolling — one event after the sheet's own drag has
 * crossed the same touch slop. So the sheet won the race, and drags on the picker moved the
 * sheet (or dismissed it) instead of scrolling. Asking on the down event settles it; the sheet
 * still drags by its handle and title row.
 */
private class TouchClaimingHost(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * "⋯": every emoji, from AndroidX's `EmojiPickerView` (recently used first, category tabs, skin
 * tones). Picking one, or "No icon", calls back; the caller saves and hides the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(
    sheetState: SheetState,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnPick by rememberUpdatedState(onPick)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            ) {
                Text(
                    "Choose icon",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onPick(null) }) { Text("No icon") }
            }

            // The activity theme is Theme.Material.Light in every mode; the app's dark mode is
            // applied only in Compose (TickdroidTheme). A View would therefore always draw light,
            // so give it a context themed from the app's own Theme setting, and rebuild it when
            // that changes.
            val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
            val pickerHeight = min(420.dp, windowHeight * 0.6f)
            key(dark) {
                AndroidView(
                    factory = { ctx ->
                        val theme = if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
                        val themed = ContextThemeWrapper(ctx, theme)
                        TouchClaimingHost(themed).apply {
                            addView(EmojiPickerView(themed).apply {
                                setOnEmojiPickedListener { currentOnPick(it.emoji) }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(pickerHeight),
                )
            }
        }
    }
}
