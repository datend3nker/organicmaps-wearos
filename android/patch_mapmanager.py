import re
import os

filepath = 'android/app/omaps/src/main/java/app/organicmaps/wear/presentation/downloads/MapManagerScreen.kt'

with open(filepath, 'r') as f:
    content = f.read()

old_block = """    LaunchedEffect(centerLat, centerLon) {
        withContext(Dispatchers.IO) {
            try {
                // Ensure native is loaded
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as app.organicmaps.wear.WearApplication
                wearApp.waitForInitializationSuspend()
                val countryId = MapManager.nativeFindCountry(centerLat, centerLon)"""

new_block = """    LaunchedEffect(centerLat, centerLon) {
        withContext(Dispatchers.IO) {
            try {
                statusText = "Wait native..."
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as app.organicmaps.wear.WearApplication
                statusText = "Wait App init..."
                wearApp.waitForInitializationSuspend()

                statusText = "Find map..."
                val countryId = MapManager.nativeFindCountry(centerLat, centerLon)"""

content = content.replace(old_block, new_block)

with open(filepath, 'w') as f:
    f.write(content)
