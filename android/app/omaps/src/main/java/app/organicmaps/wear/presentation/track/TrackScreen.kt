package app.organicmaps.wear.presentation.track

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.presentation.bookmarks.TextInputDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Track Recording management screen. Replaces the old map long-press QuickMenu (the long-press
 * conflicted with the native engine's own long-tap pin-drop and rarely fired). Reachable as a
 * dedicated pager page, modelled on [app.organicmaps.wear.presentation.bookmarks.BookmarksScreen].
 *
 * Controls only: Start / Save (named) / Discard, with a live distance + elapsed header while
 * recording. Saved tracks are browsable as the "trk" counts on the Bookmarks page.
 */
@Composable
fun TrackScreen(isVisible: Boolean) {
    val context = LocalContext.current
    val trackRecording by NavigationStateHolder.trackRecordingFlow.collectAsState(initial = null)
    val navState by NavigationStateHolder.state.collectAsState()

    val isRecording = trackRecording?.isRecording == true

    var showSaveDialog by remember { mutableStateOf(false) }

    // Live elapsed time ticker (wall-clock from startTime), same logic the old QuickMenu used.
    var elapsedTime by remember { mutableStateOf("") }
    LaunchedEffect(isRecording, trackRecording?.startTime) {
        val recording = trackRecording
        if (recording != null && recording.isRecording && recording.startTime > 0) {
            while (true) {
                val ms = System.currentTimeMillis() - recording.startTime
                val sec = (ms / 1000) % 60
                val min = (ms / 60000) % 60
                val hr = ms / 3600000
                elapsedTime = if (hr > 0) String.format(Locale.US, "%d:%02d:%02d", hr, min, sec)
                else String.format(Locale.US, "%02d:%02d", min, sec)
                delay(1.seconds)
            }
        } else {
            elapsedTime = ""
        }
    }

    if (showSaveDialog) {
        TextInputDialog(
            title = "Save Track As",
            onDismiss = { showSaveDialog = false },
            onDone = { name ->
                WearCommandService.saveTrackRecording(context, name)
                showSaveDialog = false
            }
        )
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0)
    ) {
        item {
            ListHeader { Text("Track Recording", textAlign = TextAlign.Center) }
        }

        if (isRecording) {
            item {
                val distM = trackRecording?.distance ?: 0.0
                val distLabel = if (distM >= 1000) String.format(Locale.US, "%.2f km", distM / 1000)
                else String.format(Locale.US, "%d m", distM.toInt())
                Chip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Recording…", color = Color.White) },
                    secondaryLabel = {
                        Text(
                            listOf(distLabel, elapsedTime).filter { it.isNotEmpty() }.joinToString("  •  "),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    },
                    icon = { Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color.Red) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color(0xFFD32F2F).copy(alpha = 0.35f),
                        contentColor = Color.White,
                        disabledBackgroundColor = Color(0xFFD32F2F).copy(alpha = 0.35f),
                        disabledContentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            item {
                Chip(
                    onClick = { showSaveDialog = true },
                    label = { Text("Save Recording") },
                    icon = { Icon(Icons.Default.Stop, contentDescription = null) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            item {
                Chip(
                    onClick = { WearCommandService.discardTrackRecording(context) },
                    label = { Text("Discard") },
                    icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF8B0000), contentColor = Color.White, iconColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
        } else {
            item {
                Text(
                    text = if (navState.isPhoneConnected || navState.standaloneMode)
                        "Record a track of your trip. Saved to your bookmark lists."
                    else "Phone disconnected.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            item {
                Chip(
                    onClick = { WearCommandService.toggleTrackRecording(context) },
                    label = { Text("Start Recording") },
                    icon = { Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color.Red) },
                    colors = ChipDefaults.primaryChipColors(),
                    enabled = navState.isPhoneConnected || navState.standaloneMode,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
        }
    }
}
