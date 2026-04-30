package com.martinhammer.tickdroid.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.martinhammer.tickdroid.ui.common.CompactHeightThresholdDp
import com.martinhammer.tickdroid.ui.common.MaxContentWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onOpenAbout: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var showHelp by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val compactHeight = LocalConfiguration.current.screenHeightDp < CompactHeightThresholdDp

    Scaffold(
        topBar = {
            val title: @Composable () -> Unit = { Text("Connect with Tickbuddy") }
            val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                IconButton(onClick = { showHelp = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = "Help",
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menuExpanded = false
                                onOpenAbout()
                            },
                        )
                    }
                }
            }
            if (compactHeight) {
                TopAppBar(title = title, actions = actions)
            } else {
                LargeTopAppBar(title = title, actions = actions)
            }
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Nextcloud server URL") },
                placeholder = { Text("https://cloud.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.login,
                onValueChange = viewModel::onLoginChange,
                label = { Text("User") },
                singleLine = true,
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.appPassword,
                onValueChange = viewModel::onAppPasswordChange,
                label = { Text("App password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = viewModel::signIn,
                enabled = !state.probing,
                modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            ) {
                if (state.probing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("  Testing connection…")
                    }
                } else {
                    Text("Connect")
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }

        if (showHelp) {
            HelpSheet(onDismiss = { showHelp = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "About Tickdroid",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tickdroid is a companion app for Tickbuddy, a daily habit tracker for Nextcloud. Please ensure that Tickbuddy is installed on your Nextcloud server before using Tickdroid.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))

            HelpField(
                label = "Nextcloud server URL",
                body = "The full URL of your Nextcloud server, including https://.",
            )
            Spacer(Modifier.height(16.dp))
            HelpField(
                label = "User",
                body = "Your Nextcloud username, the same one you use to sign in to the web interface.",
            )
            Spacer(Modifier.height(16.dp))
            HelpField(
                label = "App password",
                body = "A device-specific password generated in Nextcloud under Settings > Security > Devices & sessions.",
            )
        }
    }
}

@Composable
private fun HelpField(label: String, body: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
