package app.organicmaps.wear.presentation.settings

import android.content.Context
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
import app.organicmaps.wear.MapTileStateHolder

@Composable
fun PoiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    
    var mask by remember { mutableIntStateOf(navState.poiCategoriesMask) }

    val categories = listOf(
        "Eat & Drink" to (1 shl 0),
        "Hotel" to (1 shl 1),
        "ATM" to (1 shl 2),
        "Parking" to (1 shl 3),
        "Hiking Peaks" to (1 shl 4),
        "Camping" to (1 shl 5),
        "Wi-Fi" to (1 shl 6),
        "Railway" to (1 shl 7),
        "Subway" to (1 shl 8),
        "Airport" to (1 shl 9),
        "Post Office" to (1 shl 10),
        "Toilets" to (1 shl 11),
        "Amenities" to (1 shl 12),
        "Attractions" to (1 shl 13),
        "Health" to (1 shl 14),
        "Shopping" to (1 shl 15),
        "Entertainment" to (1 shl 16),
        "Water" to (1 shl 17),
        "All Others" to (1 shl 18)
    )

    val allMask = (1 shl categories.size) - 1

    val updateMask: (Int, Boolean) -> Unit = { bit, checked ->
        val newMask = if (checked) mask or bit else mask and bit.inv()
        mask = newMask
        prefs.edit().putInt("poiCategoriesMask", newMask).apply()
        NavigationStateHolder.update(navState.copy(poiCategoriesMask = newMask))
        MapTileStateHolder.clearCache()
        WearCommandService.syncPreferences(context)
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Map Details",
                style = MaterialTheme.typography.title3,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            val isAllChecked = (mask and allMask) == allMask
            ToggleChip(
                checked = isAllChecked,
                onCheckedChange = { checked ->
                    val newMask = if (checked) allMask else 0
                    mask = newMask
                    prefs.edit().putInt("poiCategoriesMask", newMask).apply()
                    NavigationStateHolder.update(navState.copy(poiCategoriesMask = newMask))
                    MapTileStateHolder.clearCache()
                    WearCommandService.syncPreferences(context)
                },
                label = { Text("All POIs") },
                toggleControl = { Checkbox(checked = isAllChecked) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
        }

        items(categories) { (name, bit) ->
            val isChecked = (mask and bit) != 0
            ToggleChip(
                checked = isChecked,
                onCheckedChange = { updateMask(bit, it) },
                label = { Text(name) },
                toggleControl = {
                    Checkbox(checked = isChecked)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("Back")
            }
        }
    }
}
