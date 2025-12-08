package com.engfred.yvd.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.engfred.yvd.ui.components.ConfirmationDialog
import com.engfred.yvd.ui.components.FileThumbnail

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadFiles()
    }

    // Handle Back Press to exit selection mode
    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    // --- Dialog Logic ---
    if (uiState.deleteMode != DeleteMode.NONE) {
        val (title, text) = when (uiState.deleteMode) {
            DeleteMode.SINGLE -> {
                val fileName = uiState.singleItemToDelete?.fileName ?: "file"
                Pair(
                    "Delete File?",
                    "Are you sure you want to delete '$fileName'?"
                )
            }
            DeleteMode.SELECTED -> {
                val count = uiState.selectedItems.size
                val itemString = if (count == 1) "Item" else "Items"
                val fileString = if (count == 1) "file" else "files"
                val theseString = if (count == 1) "this" else "these"

                Pair(
                    "Delete $count $itemString?",
                    "Are you sure you want to delete $theseString $count $fileString? This cannot be undone."
                )
            }
            DeleteMode.ALL -> Pair(
                "Delete All Files?",
                "Are you sure you want to delete ALL downloaded files? This is permanent."
            )
            else -> Pair("", "")
        }

        ConfirmationDialog(
            title = title,
            text = text,
            confirmText = "Delete",
            onConfirm = { viewModel.confirmDelete() },
            onDismiss = { viewModel.dismissDeleteDialog() }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                if (uiState.isSelectionMode) {
                    Text("${uiState.selectedItems.size} Selected")
                } else {
                    Text("My Downloads")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = if (uiState.isSelectionMode) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                titleContentColor = if (uiState.isSelectionMode) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                actionIconContentColor = if (uiState.isSelectionMode) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            navigationIcon = {
                if (uiState.isSelectionMode) {
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close Selection")
                    }
                }
            },
            actions = {
                if (uiState.isSelectionMode) {
                    IconButton(onClick = { viewModel.showDeleteSelectedDialog() }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete Selected")
                    }
                } else {
                    if (uiState.files.isNotEmpty()) {
                        IconButton(onClick = { viewModel.showDeleteAllDialog() }) {
                            Icon(Icons.Rounded.DeleteForever, contentDescription = "Delete All")
                        }
                    }
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (uiState.files.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Downloads Yet",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your downloaded videos and music will appear here safe and sound.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.files) { item ->
                        val isSelected = uiState.selectedItems.contains(item)

                        ListItem(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(item)
                                        } else {
                                            viewModel.playFile(item)
                                        }
                                    },
                                    onLongClick = {
                                        if (!uiState.isSelectionMode) {
                                            viewModel.selectSingleItemForLongPress(item)
                                        } else {
                                            viewModel.toggleSelection(item)
                                        }
                                    }
                                )
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else Color.Transparent
                                ),
                            headlineContent = {
                                Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text("${item.file.extension.uppercase()} • ${item.sizeLabel}")
                            },
                            leadingContent = {
                                Box(contentAlignment = Alignment.Center) {
                                    FileThumbnail(
                                        file = item.file,
                                        isAudio = item.isAudio,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    if(isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .background(
                                                    Color.Black.copy(alpha = 0.5f),
                                                    RoundedCornerShape(8.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                if (!uiState.isSelectionMode) {
                                    Row {
                                        IconButton(onClick = { viewModel.playFile(item) }) {
                                            Icon(
                                                Icons.Rounded.PlayArrow,
                                                contentDescription = "play",
                                                Modifier.size(34.dp)
                                            )
                                        }
                                        IconButton(onClick = { viewModel.shareFile(item) }) {
                                            Icon(Icons.Rounded.Share, contentDescription = "Share")
                                        }
                                        IconButton(onClick = {
                                            viewModel.showDeleteSingleDialog(item)
                                        }) {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                } else {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null // Handled by ListItem click
                                    )
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}