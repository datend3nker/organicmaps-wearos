package app.organicmaps.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

@Composable
fun MapDownloadOverlay() {
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
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            val title = if (downloadState == WearMapDownloader.DownloadState.DOWNLOADING) {
                "Downloading Map (Internet)"
            } else {
                "Streaming from Phone"
            }
            
            Text(
                text = title,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (currentMap != null) {
                Text(
                    text = currentMap ?: "",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (downloadState == WearMapDownloader.DownloadState.DOWNLOADING || 
                downloadState == WearMapDownloader.DownloadState.STREAMING_FROM_PHONE) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(48.dp),
                    indicatorColor = if (downloadState == WearMapDownloader.DownloadState.DOWNLOADING) MaterialTheme.colors.secondary else MaterialTheme.colors.primary,
                    trackColor = Color.DarkGray
                )
                if (downloadState == WearMapDownloader.DownloadState.STREAMING_FROM_PHONE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val progressText = if (progress > 0) "${(progress * 100).toInt()}%" else "Syncing..."
                    Text(
                        text = "Syncing via Bluetooth ($progressText)...",
                        style = MaterialTheme.typography.caption3,
                        textAlign = TextAlign.Center,
                        color = Color.LightGray
                    )
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Syncing via Bluetooth...",
                    style = MaterialTheme.typography.caption3,
                    textAlign = TextAlign.Center,
                    color = Color.LightGray
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { WearMapDownloader.cancel(context) },
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.height(32.dp).width(100.dp)
            ) {
                Text("Cancel", style = MaterialTheme.typography.caption2)
            }
        }
    }
}
