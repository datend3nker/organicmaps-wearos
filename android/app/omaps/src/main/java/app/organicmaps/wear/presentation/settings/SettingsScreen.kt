package app.organicmaps.wear.presentation.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE) }
    var autoDownload by remember { mutableStateOf(prefs.getBoolean("autoDownloadRouteMaps", true)) }
    var backend by remember { mutableStateOf(prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS") }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
            item {
                Text(
                    "Communication Backend",
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                ToggleChip(
                    checked = backend == "GMS",
                    onCheckedChange = {
                        val newBackend = if (it) "GMS" else "BLUETOOTH"
                        backend = newBackend
                        prefs.edit().putString("pref_wear_os_backend", newBackend).apply()
                        
                        val intent = Intent(context, app.organicmaps.wear.BluetoothWearDataListenerService::class.java)
                        if (newBackend == "BLUETOOTH") {
                            context.startService(intent)
                        } else if (app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
                            // Only stop if we are not in OSS flavor where Bluetooth is mandatory
                            context.stopService(intent)
                        }
                        // Re-init command service backend
                        app.organicmaps.wear.WearCommandService.initBackend(context)
                    },
                    label = { Text("Use Google Play Services") },
                    secondaryLabel = { Text(if (backend == "GMS") "Recommended" else "Using Bluetooth") },
                    toggleControl = {
                        Checkbox(checked = backend == "GMS", enabled = true)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
        }

        item {
            ToggleChip(
                checked = autoDownload,
                onCheckedChange = { 
                    autoDownload = it
                    prefs.edit().putBoolean("autoDownloadRouteMaps", it).apply()
                },
                label = { Text("Auto-download Route Maps") },
                toggleControl = {
                    Switch(checked = autoDownload, enabled = true)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
    }
}
