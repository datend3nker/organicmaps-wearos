package app.organicmaps.wear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

val OrganicGreen = Color(0xFF4CAF50)
val OrganicBlue = Color(0xFF2196F3)
val DarkBackground = Color(0xFF121212)
val SurfaceColor = Color(0xFF1E1E1E)

private val WearColors = Colors(
    primary = OrganicGreen,
    primaryVariant = Color(0xFF388E3C),
    secondary = OrganicBlue,
    background = DarkBackground,
    surface = SurfaceColor,
    error = Color(0xFFCF6679),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onError = Color.Black
)

@Composable
fun OrganicMapsTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colors = WearColors,
        content = content
    )
}
