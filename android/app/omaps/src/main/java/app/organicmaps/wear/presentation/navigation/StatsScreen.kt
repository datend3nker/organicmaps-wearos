package app.organicmaps.wear.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import app.organicmaps.wear.NavigationState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StatsScreen(navState: NavigationState) {
    Scaffold(
        timeText = { TimeText() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Stats",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.secondary
            )

            // Speed
            val speedKmH = if (navState.speedMps >= 0) (navState.speedMps * 3.6).roundToInt() else 0
            Text(
                text = "$speedKmH km/h",
                style = MaterialTheme.typography.title1.copy(fontSize = 28.sp),
                color = if (navState.speedLimitMps > 0 && navState.speedMps > navState.speedLimitMps) 
                            MaterialTheme.colors.error 
                        else 
                            MaterialTheme.colors.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
            
            if (navState.speedLimitMps > 0) {
                Text(
                    text = "Limit: ${(navState.speedLimitMps * 3.6).roundToInt()}",
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.padding(top = 4.dp)
                )
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
