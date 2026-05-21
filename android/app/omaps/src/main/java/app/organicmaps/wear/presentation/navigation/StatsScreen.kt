package app.organicmaps.wear.presentation.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import app.organicmaps.wear.NavigationState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StatsScreen(navState: NavigationState) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            item {
                Text(
                    text = "Stats",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.secondary
                )
            }

            // Speed
            item {
                val speedKmH = if (navState.speedMps >= 0) (navState.speedMps * 3.6).roundToInt() else 0
                Text(
                    text = "$speedKmH km/h",
                    style = MaterialTheme.typography.title1.copy(fontSize = 28.sp),
                    color = if (navState.speedLimitMps > 0 && navState.speedMps > navState.speedLimitMps) 
                                MaterialTheme.colors.error 
                            else 
                                MaterialTheme.colors.primary
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Distance", style = MaterialTheme.typography.caption2)
                        Text(navState.distToTarget, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ETA", style = MaterialTheme.typography.caption2)
                        Text(formatEta(navState.eta), fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            if (navState.speedLimitMps > 0) {
                item {
                    Text(
                        text = "Limit: ${(navState.speedLimitMps * 3.6).roundToInt()}",
                        style = MaterialTheme.typography.caption2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private fun formatEta(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
    } else {
        String.format(Locale.getDefault(), "%d min", minutes)
    }
}
