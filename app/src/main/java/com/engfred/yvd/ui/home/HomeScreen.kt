package com.engfred.yvd.ui.home

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.yvd.ui.components.ConfirmationDialog
import com.engfred.yvd.ui.components.DownloadProgressCard
import com.engfred.yvd.ui.components.FormatSelectionSheet
import com.engfred.yvd.ui.components.ThemeSelectionDialog
import com.engfred.yvd.ui.components.VideoCard
import com.engfred.yvd.util.openYoutube

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Permission launcher ────────────────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "Enable notifications to track downloads in the background",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Request notification permission once on first compose (Android 13+).
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Surface error messages as snackbars.
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
            viewModel.clearError()
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────

    if (state.isCancelDialogVisible) {
        ConfirmationDialog(
            title = "Cancel Download?",
            text = "The partial download will be saved. You can resume it later.",
            confirmText = "Yes, Cancel",
            onConfirm = { viewModel.cancelDownload() },
            onDismiss = { viewModel.hideCancelDialog() }
        )
    }

    if (state.isThemeDialogVisible) {
        ThemeSelectionDialog(
            currentTheme = currentTheme,
            onThemeSelected = { viewModel.updateTheme(it) },
            onDismiss = { viewModel.hideThemeDialog() }
        )
    }

    // ── Root layout via Scaffold ───────────────────────────────────────────
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Badge shows count of active/queued downloads (from all sessions).
            BadgedBox(
                badge = {
                    if (state.activeDownloadCount > 1) {
                        Badge {
                            Text(
                                text = state.activeDownloadCount.toString(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            ) {
                ExtendedFloatingActionButton(
                    onClick = { openYoutube(context) },
                    containerColor = Color(0xFFFF0000),
                    contentColor = Color.White
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SmartDisplay,
                        modifier = Modifier.size(32.dp),
                        contentDescription = "Open YouTube"
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("YV Downloader") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { viewModel.showThemeDialog() }) {
                        Icon(
                            imageVector = Icons.Rounded.SettingsBrightness,
                            contentDescription = "Change theme",
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── URL input ─────────────────────────────────────────────────
            OutlinedTextField(
                value = state.urlInput,
                onValueChange = { viewModel.onUrlInputChanged(it) },
                label = { Text("Paste YouTube link") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.urlError != null,
                supportingText = {
                    // Inline validation error — shown below the field, not as a snackbar.
                    AnimatedVisibility(visible = state.urlError != null) {
                        Text(
                            text = state.urlError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = clipboardManager.getText()?.text?.toString()
                        if (!clip.isNullOrBlank()) {
                            keyboardController?.hide()
                            viewModel.loadVideoInfo(clip)
                        }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste from clipboard")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── "Get Video Info" button — hidden once metadata is loaded ──
            AnimatedVisibility(
                visible = state.videoMetadata == null && !state.isDownloading &&
                        !state.downloadComplete && !state.downloadFailed
            ) {
                Button(
                    onClick = {
                        keyboardController?.hide()
                        viewModel.loadVideoInfo(state.urlInput)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                    enabled = !state.isLoading && state.urlInput.isNotBlank()
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Loading…")
                    } else {
                        Text("Get Video Info")
                    }
                }
            }

            // ── Empty / instruction state ─────────────────────────────────
            AnimatedVisibility(
                visible = state.videoMetadata == null && !state.isDownloading &&
                        !state.downloadComplete && !state.downloadFailed && !state.isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp, horizontal = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "How to Download",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Tap the red button to open YouTube\n" +
                                "2. Copy a video link\n" +
                                "3. Paste it above and tap Get Video Info",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Video metadata card ───────────────────────────────────────
            state.videoMetadata?.let { metadata ->
                Spacer(modifier = Modifier.height(16.dp))
                VideoCard(
                    metadata = metadata,
                    isDownloading = state.isDownloading,
                    onDownloadClick = { viewModel.showFormatDialog() }
                )
            }

            // ── Download progress / result card ───────────────────────────
            AnimatedVisibility(
                visible = state.isDownloading || state.downloadComplete || state.downloadFailed,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(20.dp))
                    DownloadProgressCard(
                        statusText = state.downloadStatusText,
                        progress = state.downloadProgress,
                        isDownloading = state.isDownloading,
                        isComplete = state.downloadComplete,
                        isFailed = state.downloadFailed,
                        isAudio = state.isAudio,
                        onCancel = { viewModel.showCancelDialog() },
                        onPlay = { viewModel.openMediaFile() },
                        onShare = { viewModel.shareMediaFile() },
                        onRetry = { viewModel.retryDownload() }
                    )
                }
            }

            // Extra bottom padding so content isn't obscured by the FAB.
            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    // ── Format selection bottom sheet ──────────────────────────────────────
    if (state.isFormatDialogVisible && state.videoMetadata != null) {
        FormatSelectionSheet(
            metadata = state.videoMetadata!!,
            onDismiss = { viewModel.hideFormatDialog() },
            onFormatSelected = { formatId, isAudio ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.downloadMedia(formatId, isAudio)
            }
        )
    }
}