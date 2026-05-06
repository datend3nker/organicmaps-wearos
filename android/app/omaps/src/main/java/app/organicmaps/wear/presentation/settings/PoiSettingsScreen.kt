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

@Composable
fun PoiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    
    var mask by remember { mutableIntStateOf(navState.poiCategoriesMask) }

    val updateMask: (Int, Boolean) -> Unit = { bit, checked ->
        val newMask = if (checked) mask or bit else mask and bit.inv()
        mask = newMask
        prefs.edit().putInt("poiCategoriesMask", newMask).apply()
        NavigationStateHolder.update(navState.copy(poiCategoriesMask = newMask))
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
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("Back")
            }
        }

        val categories = listOf(
            "Eat & Drink" to 1,
            "Transportation" to 2,
            "Hotel" to 4,
            "ATM" to 8,
            "Main POIs" to 16,
            "All Details" to 32
        )

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
    }
}
