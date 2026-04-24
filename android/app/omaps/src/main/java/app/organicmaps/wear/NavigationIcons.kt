package app.organicmaps.wear

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

object NavigationIcons {
    fun getTurnIcon(carDirection: Int, pedestrianDirection: Int): ImageVector {
        // If pedestrian direction is not NoTurn/GoStraight, use it
        if (pedestrianDirection != 0 && pedestrianDirection != 1) {
            return when (pedestrianDirection) {
                2 -> Icons.AutoMirrored.Filled.ArrowForward
                3 -> Icons.AutoMirrored.Filled.ArrowBack
                4 -> Icons.Default.Place
                else -> Icons.Default.ArrowUpward
            }
        }

        // Mapping based on app.organicmaps.sdk.routing.CarDirection enum
        return when (carDirection) {
            0, 1, 13 -> Icons.Default.ArrowUpward // NoTurn, GoStraight, StartAtEndOfStreet
            2, 3, 4 -> Icons.AutoMirrored.Filled.ArrowForward // TurnRight variants
            5, 6, 7 -> Icons.AutoMirrored.Filled.ArrowBack // TurnLeft variants
            8, 9 -> Icons.Default.Refresh // UTurn variants (Refresh as fallback)
            10, 11, 12 -> Icons.Default.Refresh // Roundabout
            14 -> Icons.Default.Place // ReachedYourDestination
            else -> Icons.Default.ArrowUpward
        }
    }
}
