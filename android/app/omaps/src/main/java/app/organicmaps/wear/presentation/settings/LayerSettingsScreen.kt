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
import app.organicmaps.sdk.Framework

@Composable
fun LayerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }

    val updateLayer: (Boolean, (Boolean) -> Unit, String) -> Unit = { checked, nativeCall, prefKey ->
        nativeCall(checked)
        prefs.edit().putBoolean(prefKey, checked).commit()
        WearCommandService.syncPreferences(context)
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Map Layers",
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

        item {
            ToggleLayerChip(
                label = "Underground",
                checked = navState.transitEnabled,
                onCheckedChange = { checked ->
                    NavigationStateHolder.updateSettings { it.copy(transitEnabled = checked) }
                    updateLayer(checked, { Framework.nativeSetTransitSchemeEnabled(it) }, "pref_wear_os_transit")
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                }
            )
        }

        item {
            ToggleLayerChip(
                label = "Bikeroutes",
                checked = navState.bikingEnabled,
                onCheckedChange = { checked ->
                    NavigationStateHolder.updateSettings { it.copy(bikingEnabled = checked) }
                    updateLayer(checked, { Framework.nativeSetCyclingLayerEnabled(it) }, "pref_wear_os_biking")
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                }
            )
        }

        item {
            ToggleLayerChip(
                label = "Hiking",
                checked = navState.hikingEnabled,
                onCheckedChange = { checked ->
                    NavigationStateHolder.updateSettings { it.copy(hikingEnabled = checked) }
                    updateLayer(checked, { Framework.nativeSetHikingLayerEnabled(it) }, "pref_wear_os_hiking")
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                }
            )
        }

        item {
            ToggleLayerChip(
                label = "Contours",
                checked = navState.isolinesEnabled,
                onCheckedChange = { checked ->
                    NavigationStateHolder.updateSettings { it.copy(isolinesEnabled = checked) }
                    updateLayer(checked, { Framework.nativeSetIsolinesLayerEnabled(it) }, "pref_wear_os_isolines")
                    app.organicmaps.wear.WearCommandService.syncPreferences(context)
                }
            )
        }
    }
}

@Composable
private fun ToggleLayerChip(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = { Text(label) },
        toggleControl = { Checkbox(checked = checked) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}
