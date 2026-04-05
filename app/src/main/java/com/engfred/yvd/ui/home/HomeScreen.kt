package com.engfred.yvd.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.yvd.ui.components.*
import com.engfred.yvd.util.NetworkUtil
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

    // State to pause downloads while we ask for permissions or data warnings
    var pendingSingleFormat by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingPlaylistFormat by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showDataWarningDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Enable notifications to track downloads", Toast.LENGTH_LONG).show()
        }

        // 1. Resume single download after permission prompt closes
        pendingSingleFormat?.let { (formatId, isAudio) ->
            viewModel.downloadMedia(formatId, isAudio)
            pendingSingleFormat = null
        }

        // 2. Resume playlist download after permission prompt closes
        pendingPlaylistFormat?.let { (formatId, isAudio) ->
            if (NetworkUtil.isUsingMobileData(context)) {
                showDataWarningDialog = true // Trigger data warning next if on cellular
            } else {
                viewModel.downloadEntirePlaylist(formatId, isAudio)
                pendingPlaylistFormat = null
            }
        }
    }

    // FIX: try...finally ensures the error state is ALWAYS cleared, even if you navigate away quickly
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            try {
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
            } finally {
                viewModel.clearError()
            }
        }
    }

    // FIX: try...finally prevents the "Added to queue" snackbar from repeating when navigating back
    LaunchedEffect(state.queuedSnackbarMessage) {
        state.queuedSnackbarMessage?.let { message ->
            try {
                snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            } finally {
                viewModel.clearQueuedMessage()
            }
        }
    }

    if (state.isThemeDialogVisible) {
        ThemeSelectionDialog(
            currentTheme = currentTheme,
            onThemeSelected = { viewModel.updateTheme(it) },
            onDismiss = { viewModel.hideThemeDialog() }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            BadgedBox(
                modifier = Modifier.padding(bottom = 88.dp),
                badge = {
                    if (state.activeDownloadCount > 1) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(state.activeDownloadCount.toString()) }
                    }
                }
            ) {
                FloatingActionButton(
                    onClick = { openYoutube(context) },
                    containerColor = Color(0xFFFF0000), // YouTube Red
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(Icons.Rounded.SmartDisplay, contentDescription = "Open YouTube", modifier = Modifier.size(28.dp))
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("YV Downloader", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                actions = {
                    IconButton(
                        onClick = { viewModel.showThemeDialog() },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Rounded.Palette, contentDescription = "Change theme", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
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
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                TextField(
                    value = state.urlInput,
                    onValueChange = { viewModel.onUrlInputChanged(it) },
                    placeholder = { Text("Paste YouTube link here...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    trailingIcon = {
                        Row {
                            if (state.urlInput.isNotBlank()) {
                                IconButton(onClick = { viewModel.onUrlInputChanged("") }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    keyboardController?.hide()
                                    viewModel.loadVideoInfo(clip)
                                }
                            }) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                )
            }

            AnimatedVisibility(visible = state.urlError != null) {
                Text(
                    text = state.urlError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp).fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(visible = state.videoMetadata == null && state.playlistMetadata == null) {
                Button(
                    onClick = {
                        keyboardController?.hide()
                        viewModel.loadVideoInfo(state.urlInput)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp, pressedElevation = 2.dp),
                    enabled = !state.isLoading && state.urlInput.isNotBlank()
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Fetching Magic...", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Get Video Info", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            AnimatedVisibility(
                visible = state.videoMetadata == null && state.playlistMetadata == null && !state.isLoading,
                enter = fadeIn(), exit = fadeOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.RocketLaunch, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Ready to Download?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Tap the red button to open YouTube, grab a link, and paste it above to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }

            state.videoMetadata?.let { metadata ->
                Spacer(modifier = Modifier.height(16.dp))
                VideoCard(
                    metadata = metadata,
                    onDownloadClick = { viewModel.showFormatDialog() }
                )
            }

            state.playlistMetadata?.let { playlist ->
                Spacer(modifier = Modifier.height(16.dp))
                PlaylistCard(
                    metadata = playlist,
                    onDownloadClick = { viewModel.showPlaylistFormatDialog() }
                )
            }

            Spacer(modifier = Modifier.height(120.dp))
        }
    }

    if (state.isFormatDialogVisible && state.videoMetadata != null) {
        FormatSelectionSheet(
            metadata = state.videoMetadata!!,
            onDismiss = { viewModel.hideFormatDialog() },
            onFormatSelected = { formatId, isAudio ->
                // FIX: Check permission before downloading a single video
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingSingleFormat = formatId to isAudio
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.downloadMedia(formatId, isAudio)
                }
            }
        )
    }

    if (state.showActiveDownloadGuardDialog) {
        ConfirmationDialog(
            title = "Downloads in Progress",
            text = "You have active download(s) in the background. Load the new link anyway?",
            confirmText = "Proceed",
            onConfirm = { viewModel.confirmReplaceWithPendingUrl() },
            onDismiss  = { viewModel.dismissGuardDialog() }
        )
    }

    if (state.isPlaylistFormatDialogVisible && state.playlistMetadata != null) {
        PlaylistFormatSheet(
            playlistMetadata = state.playlistMetadata!!,
            onDismiss = { viewModel.hidePlaylistFormatDialog() },
            onFormatSelected = { formatId, isAudio ->
                viewModel.hidePlaylistFormatDialog()

                // FIX: Check permission before downloading a playlist
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingPlaylistFormat = formatId to isAudio
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    if (NetworkUtil.isUsingMobileData(context)) {
                        pendingPlaylistFormat = formatId to isAudio
                        showDataWarningDialog = true
                    } else {
                        viewModel.downloadEntirePlaylist(formatId, isAudio)
                    }
                }
            }
        )
    }

    if (showDataWarningDialog && pendingPlaylistFormat != null) {
        ConfirmationDialog(
            title = "Mobile Data Warning",
            text = "You are on mobile data. Downloading an entire playlist (${state.playlistMetadata?.videoCount ?: 0} videos) may consume significant data.\n\nDo you want to proceed?",
            confirmText = "Proceed",
            onConfirm = {
                viewModel.downloadEntirePlaylist(pendingPlaylistFormat!!.first, pendingPlaylistFormat!!.second)
                showDataWarningDialog = false
                pendingPlaylistFormat = null
            },
            onDismiss = {
                showDataWarningDialog = false
                pendingPlaylistFormat = null
            }
        )
    }
}