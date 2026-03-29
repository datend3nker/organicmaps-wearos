package app.organicmaps.wear.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices

@Composable
fun NavigationScreen(
    distanceToNextTurn: String,
    turnIcon: ImageVector,
    remainingTime: String,
    onCancelClick: () -> Unit,
    deviceRotation: Float,
) {
    Scaffold(
        timeText = { TimeText() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp, bottom = 12.dp), // Avoid overlap with TimeText
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Larger distance text
            Text(
                text = distanceToNextTurn,
                style = MaterialTheme.typography.title1.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )

            // Turn icon rotated by device orientation
            Icon(
                imageVector = turnIcon,
                contentDescription = "Turn icon",
                modifier = Modifier
                    .size(56.dp) // Adjusted size
                    .rotate(deviceRotation),
                tint = MaterialTheme.colors.onBackground
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Street name / remaining time
                Text(
                    text = remainingTime,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                
                Spacer(modifier = Modifier.height(4.dp))

                // Smaller cancel button
                Button(
                    onClick = onCancelClick,
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun NavigationScreenPreview() {
    NavigationScreen(
        distanceToNextTurn = "100 m",
        turnIcon = Icons.Default.ArrowUpward,
        remainingTime = "Main Street",
        onCancelClick = { },
        deviceRotation = 45f
    )
}
