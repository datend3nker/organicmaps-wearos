package app.organicmaps.wear.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.sdk.settings.RoadType
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearCommandService

@Composable
fun RoutingOptionsRow(
    avoidTolls: Boolean,
    avoidMotorways: Boolean,
    avoidFerries: Boolean,
    avoidUnpaved: Boolean,
    onOptionToggled: (RoadType, Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        val options = listOf(
            Icons.Default.Paid to RoadType.Toll,
            Icons.Default.DirectionsCar to RoadType.Motorway,
            Icons.Default.DirectionsBoat to RoadType.Ferry,
            Icons.Default.Terrain to RoadType.Dirty
        )
        options.forEach { (icon, type) ->
            val isChecked = when(type) {
                RoadType.Toll -> avoidTolls
                RoadType.Motorway -> avoidMotorways
                RoadType.Ferry -> avoidFerries
                RoadType.Dirty -> avoidUnpaved
                else -> false
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isChecked) Color.Red.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.15f))
                    .clickable {
                        onOptionToggled(type, !isChecked)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (isChecked) Color.White else Color.LightGray)
            }
        }
    }
}
