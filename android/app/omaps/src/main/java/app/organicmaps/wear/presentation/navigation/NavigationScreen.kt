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
    deviceRotation: Float = 0f,
) {
    Scaffold(
        timeText = { TimeText() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Distance text
            Text(
                text = distanceToNextTurn.ifEmpty { "Proceed" },
                style = MaterialTheme.typography.title1.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )

            // Large turn icon
            Icon(
                imageVector = turnIcon,
                contentDescription = "Turn icon",
                modifier = Modifier
                    .size(64.dp)
                    .rotate(deviceRotation),
                tint = MaterialTheme.colors.onBackground
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Street name
                Text(
                    text = remainingTime,
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                
                Spacer(modifier = Modifier.height(6.dp))

                // Cancel button
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
        distanceToNextTurn = "150 m",
        turnIcon = Icons.Default.ArrowUpward,
        remainingTime = "Avenue de l'Opéra",
        onCancelClick = { },
        deviceRotation = 45f
    )
}
