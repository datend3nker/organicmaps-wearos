package app.organicmaps.wear

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

object NavigationIcons {
    fun getTurnIcon(carDirectionOrdinal: Int): ImageVector {
        return when (carDirectionOrdinal) {
            0, 1 -> Icons.Default.ArrowUpward
            2, 3, 4 -> Icons.AutoMirrored.Filled.ArrowForward
            5, 6, 7 -> Icons.AutoMirrored.Filled.ArrowBack
            8, 9 -> Icons.Default.Refresh // Fallback for U-Turns
            10, 11, 12 -> Icons.Default.Refresh
            14 -> Icons.Default.Flag
            else -> Icons.Default.ArrowUpward
        }
    }
}
