package app.organicmaps.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.wear.compose.material.*
import app.organicmaps.wear.WearMapDownloader
import app.organicmaps.wear.NavigationStateHolder

@Composable
fun MapDownloadOverlay() {
    val navState by NavigationStateHolder.state.collectAsState()
    val downloadState by WearMapDownloader.downloadState.collectAsState()
    val progress by WearMapDownloader.downloadProgress.collectAsState()
    val currentMap by WearMapDownloader.currentMap.collectAsState()
    val context = LocalContext.current

    if (downloadState == WearMapDownloader.DownloadState.IDLE || 
        downloadState == WearMapDownloader.DownloadState.COMPLETED ||
        downloadState == WearMapDownloader.DownloadState.CANCELLED) {
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(enabled = true, onClick = {}) // Consume touches
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            // Smoothly animate progress so the ring/bar glide rather than jump between chunks.
            val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
                label = "downloadProgress"
            )

            val title = when (downloadState) {
                WearMapDownloader.DownloadState.DOWNLOADING ->
                    if (progress <= 0f) "Connecting (Internet)…" else "Downloading (Internet)"
                WearMapDownloader.DownloadState.STREAMING_FROM_PHONE ->
                    if (progress <= 0f) "Connecting to phone…" else if (progress >= 0.99f) "Finishing…" else "Copying from Phone"
                WearMapDownloader.DownloadState.FAILED -> "Sync Failed"
                else -> "Synchronizing..."
            }
            
            val titleColor = if (downloadState == WearMapDownloader.DownloadState.FAILED) Color.Red else Color(0xFF00E5FF)
            
            Text(
                text = title,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                color = titleColor
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            currentMap?.let { name ->
                Text(
                    text = name.replace("_", " "),
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (downloadState == WearMapDownloader.DownloadState.FAILED) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                val isIndeterminate = progress <= 0f
                Box(contentAlignment = Alignment.Center) {
                    if (isIndeterminate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            indicatorColor = MaterialTheme.colors.primary,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            strokeWidth = 3.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = animatedProgress,
                            modifier = Modifier.size(48.dp),
                            indicatorColor = MaterialTheme.colors.primary,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            strokeWidth = 3.dp
                        )
                        // Percentage inside the ring for at-a-glance reading on the small display.
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.caption3,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Slim determinate bar underneath — easier to gauge at a glance than the ring alone.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (isIndeterminate) 0f else animatedProgress)
                            .fillMaxHeight()
                            .background(MaterialTheme.colors.primary, RoundedCornerShape(2.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            val backendName = if (navState.backend == "GMS") "Google Play" else "Bluetooth"
            val progressText = if (downloadState == WearMapDownloader.DownloadState.FAILED) "Map not found on phone" 
                              else if (progress > 0) "${(progress * 100).toInt()}%" 
                              else "Starting..."
            
            Text(
                text = if (downloadState == WearMapDownloader.DownloadState.FAILED) progressText else "via $backendName ($progressText)",
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
                color = if (downloadState == WearMapDownloader.DownloadState.FAILED) Color.Red.copy(alpha = 0.8f) else Color.LightGray
            )

            Spacer(modifier = Modifier.height(12.dp))

            CompactChip(
                onClick = { 
                    if (downloadState == WearMapDownloader.DownloadState.FAILED) {
                        WearMapDownloader.onDownloadCancelled() // Reset state to IDLE
                    } else {
                        WearMapDownloader.cancel(context)
                    }
                },
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text(if (downloadState == WearMapDownloader.DownloadState.FAILED) "Close" else "Cancel", style = MaterialTheme.typography.caption2) },
                modifier = Modifier.height(32.dp)
            )
        }
    }
}
