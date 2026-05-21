package app.organicmaps.wear.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearCommandService
import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.sdk.settings.RoadType

@Composable
fun RoutingOptionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("wear_prefs", android.content.Context.MODE_PRIVATE) }

    val toggleOption: (RoadType, Boolean) -> Unit = { roadType, checked ->
        NavigationStateHolder.update { current ->
            if (checked) RoutingOptions.addOption(roadType) else RoutingOptions.removeOption(roadType)
            
            val key = when(roadType) {
                RoadType.Toll -> "avoid_tolls"
                RoadType.Motorway -> "avoid_motorways"
                RoadType.Ferry -> "avoid_ferries"
                RoadType.Dirty -> "avoid_dirty_roads"
                else -> ""
            }
            if (key.isNotEmpty()) prefs.edit().putBoolean(key, checked).apply()
            
            WearCommandService.syncPreferences(context)
            
            when(roadType) {
                RoadType.Toll -> current.copy(avoidTolls = checked, lastSettingsInteractionTime = System.currentTimeMillis())
                RoadType.Motorway -> current.copy(avoidMotorways = checked, lastSettingsInteractionTime = System.currentTimeMillis())
                RoadType.Ferry -> current.copy(avoidFerries = checked, lastSettingsInteractionTime = System.currentTimeMillis())
                RoadType.Dirty -> current.copy(avoidUnpaved = checked, lastSettingsInteractionTime = System.currentTimeMillis())
                else -> current
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Routing Options",
                style = MaterialTheme.typography.title3,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("Back")
            }
        }

        val options = listOf(
            "Avoid Tolls" to RoadType.Toll,
            "Avoid Motorways" to RoadType.Motorway,
            "Avoid Ferries" to RoadType.Ferry,
            "Avoid Unpaved" to RoadType.Dirty
        )

        items(options) { (label, type) ->
            val isChecked = when(type) {
                RoadType.Toll -> navState.avoidTolls
                RoadType.Motorway -> navState.avoidMotorways
                RoadType.Ferry -> navState.avoidFerries
                RoadType.Dirty -> navState.avoidUnpaved
                else -> false
            }
            ToggleChip(
                checked = isChecked,
                onCheckedChange = { toggleOption(type, it) },
                label = { Text(label) },
                toggleControl = {
                    Checkbox(checked = isChecked)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
        }
    }
}
