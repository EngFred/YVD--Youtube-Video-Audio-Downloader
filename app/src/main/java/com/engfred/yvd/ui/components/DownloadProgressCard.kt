package com.engfred.yvd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Card that represents the lifecycle of a single download: in-progress, complete, or failed.
 *
 * States:
 * - **Downloading** — animated progress bar, cancel button.
 * - **Complete**    — full progress bar, Play + Share buttons.
 * - **Failed**      — error icon, status message, Retry button.
 *
 * @param statusText    Human-readable text shown next to the progress bar.
 * @param progress      Download progress 0–100. Ignored when [isFailed] is true.
 * @param isDownloading True while the download worker is active.
 * @param isComplete    True when the download has succeeded.
 * @param isFailed      True when the download has failed terminally.
 * @param isAudio       Determines label on the Play button ("Play Audio" vs "Play Video").
 * @param onCancel      Called when the user taps Cancel during an active download.
 * @param onPlay        Called when the user taps Play after a successful download.
 * @param onShare       Called when the user taps Share after a successful download.
 * @param onRetry       Called when the user taps Retry after a failed download.
 */
@Composable
fun DownloadProgressCard(
    modifier: Modifier = Modifier,
    statusText: String,
    progress: Float,
    isDownloading: Boolean,
    isComplete: Boolean,
    isFailed: Boolean,
    isAudio: Boolean,
    onCancel: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit
) {
    // Choose the card accent color based on state.
    val containerColor = when {
        isFailed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header row: status text + cancel button ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Failed state gets an error icon prefix.
                if (isFailed) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp)
                    )
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        isFailed -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )

                // Cancel button — only visible while actively downloading.
                AnimatedVisibility(visible = isDownloading && !isComplete && !isFailed) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Cancel download",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Progress bar ───────────────────────────────────────────────
            // Hidden in the failed state (nothing meaningful to show).
            AnimatedVisibility(visible = !isFailed) {
                val animatedProgress by animateFloatAsState(
                    targetValue = (progress / 100f).coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 300),
                    label = "DownloadProgress"
                )

                if (isDownloading && progress <= 0f) {
                    // Indeterminate bar during the "Initializing…" phase.
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceDim
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceDim
                    )
                }
            }

            // ── Action buttons: shown when complete or failed ──────────────
            AnimatedContent(
                targetState = when {
                    isComplete -> DownloadCardState.COMPLETE
                    isFailed -> DownloadCardState.FAILED
                    else -> DownloadCardState.DOWNLOADING
                },
                transitionSpec = {
                    (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 })
                        .togetherWith(fadeOut(tween(150)))
                },
                label = "CardActionButtons"
            ) { state ->
                when (state) {
                    DownloadCardState.COMPLETE -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onPlay,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF43A047), // accessible green
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isAudio) "Play Audio" else "Play Video")
                            }

                            OutlinedButton(
                                onClick = onShare,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Rounded.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share")
                            }
                        }
                    }

                    DownloadCardState.FAILED -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                    }

                    DownloadCardState.DOWNLOADING -> {
                        // No action buttons while downloading — only the cancel icon above.
                    }
                }
            }
        }
    }
}

// Internal state enum used only for AnimatedContent target state.
private enum class DownloadCardState { DOWNLOADING, COMPLETE, FAILED }