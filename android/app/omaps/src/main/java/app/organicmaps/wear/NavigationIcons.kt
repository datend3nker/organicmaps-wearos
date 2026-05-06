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
            2, 3 -> Icons.AutoMirrored.Filled.ArrowForward // TurnRight, TurnSharpRight
            4 -> Icons.Default.NorthEast // TurnSlightRight
            5, 6 -> Icons.AutoMirrored.Filled.ArrowBack // TurnLeft, TurnSharpLeft
            7 -> Icons.Default.NorthWest // TurnSlightLeft
            8, 9, 10, 11, 12 -> Icons.Default.Refresh // UTurn and Roundabout variants
            14 -> Icons.Default.Place // ReachedYourDestination
            15 -> Icons.Default.West // ExitHighwayToLeft
            16 -> Icons.Default.East // ExitHighwayToRight
            else -> Icons.Default.ArrowUpward
        }
    }
}
