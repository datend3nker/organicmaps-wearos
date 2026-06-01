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
            val title = when (downloadState) {
                WearMapDownloader.DownloadState.DOWNLOADING -> "Downloading (Internet)"
                WearMapDownloader.DownloadState.STREAMING_FROM_PHONE -> "Serving from Phone"
                else -> "Synchronizing..."
            }
            
            Text(
                text = title,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                color = Color(0xFF00E5FF)
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
            
            val isIndeterminate = progress <= 0f
            if (isIndeterminate) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = Color.White.copy(alpha = 0.1f),
                    strokeWidth = 3.dp
                )
            } else {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(40.dp),
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = Color.White.copy(alpha = 0.1f),
                    strokeWidth = 3.dp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            val backendName = if (navState.backend == "GMS") "Google Play" else "Bluetooth"
            val progressText = if (progress > 0) "${(progress * 100).toInt()}%" else "Starting..."
            
            Text(
                text = "via $backendName ($progressText)",
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
                color = Color.LightGray
            )

            Spacer(modifier = Modifier.height(12.dp))

            CompactChip(
                onClick = { WearMapDownloader.cancel(context) },
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Cancel", style = MaterialTheme.typography.caption2) },
                modifier = Modifier.height(32.dp)
            )
        }
    }
}
