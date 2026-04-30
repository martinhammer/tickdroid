package com.martinhammer.tickdroid.ui.about

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.martinhammer.tickdroid.ui.common.MaxContentWidth

private const val GITHUB_URL = "https://github.com/martinhammer/tickdroid"
private const val LICENCE_URL = "https://github.com/martinhammer/tickdroid/blob/main/LICENCE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    val iconSizeDp = 96.dp
    val iconBitmap = remember(context, density) {
        val px = with(density) { iconSizeDp.roundToPx() }
        context.packageManager.getApplicationIcon(context.packageName)
            .toBitmap(width = px, height = px)
            .asImageBitmap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(
                    WindowInsets.navigationBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal)
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(iconSizeDp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Tickdroid", style = MaterialTheme.typography.headlineSmall)
            if (versionName.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Version $versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Companion Android app for Tickbuddy, a daily habit tracker for Nextcloud.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))

            Text(
                text = "© 2026 Martin Hammer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Licensed under GPL-3.0",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
                modifier = Modifier
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, LICENCE_URL.toUri()))
                    }
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(32.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()))
                },
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            ) {
                Text("View source on GitHub")
            }
        }
    }
}
