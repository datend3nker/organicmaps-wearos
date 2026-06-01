package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import app.organicmaps.wear.presentation.Omaps
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object WearMessageRouter {
    private const val TAG = "WearMessageRouter"
    private const val PATH_PONG = "/pong"
    private val bookmarkOutputStreams = mutableMapOf<Long, java.io.FileOutputStream>()

    fun onMessageReceived(context: Context, path: String, data: ByteArray, sourceNodeId: String) {
        Log.d(TAG, "DEBUG_GMS: Watch routing message: $path from $sourceNodeId")
        
        NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
        (context.applicationContext as WearApplication).onActivityReceived()
        NavigationStateHolder.update { it.copy(isPhoneConnected = true) }
        
        if (path == PATH_PONG) {
            return
        }

        if (path == "/launch") {
            launchOmaps(context)
            return
        }

        val currentState = NavigationStateHolder.state.value

        when (path) {
            "/navigation/start" -> {
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isActive = true, isMapUnlockedBeforeNav = currentState.isMapUnlocked, isMapUnlocked = false))
                launchOmaps(context)
            }
            "/map/download/request" -> {
                val countryId = String(data)
                Log.d(TAG, "Phone explicitly requested map download: $countryId")
                handleMapDownloadRequest(context, countryId)
            }
            "/map/download/not_found" -> {
                val mapId = String(data)
                Log.w(TAG, "Phone reported map NOT FOUND: $mapId")
                WearMapDownloader.onMapMissingOnPhone(context, mapId)
            }
            "/backend/switch" -> {
                val newBackend = String(data)
                handleBackendSwitch(context, newBackend)
            }
            "/bookmark/file" -> {
                handleBookmarkFile(context, data)
            }
            "/virtual_mwm/data" -> {
                handleVirtualMwmData(data)
            }
            "/virtual_mwm/mount" -> {
                handleVirtualMwmMount(context, data)
            }
            "/search/results" -> {
                handleSearchResults(data)
            }
            "/search/history" -> {
                handleSearchHistory(data)
            }
            "/navigation/status" -> {
                handleNavigationStatus(context, data)
            }
            "/track/recording" -> {
                handleTrackRecording(data)
            }
            "/bookmarks" -> {
                handleBookmarks(data)
            }
            "/preferences/trigger" -> {
                Log.d(TAG, "DEBUG_GMS: Remote preferences trigger received")
            }
            "/ping" -> {
                Log.d(TAG, "DEBUG_GMS: Ping received, sending pong")
                WearCommandService.sendPong(context, sourceNodeId)
            }
        }
    }

    private fun handleTrackRecording(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        if (buffer.remaining() < 9) return
        val isRecording = buffer.get().toInt() == 1
        val startTime = buffer.long
        NavigationStateHolder.update { it.copy(isTrackRecording = isRecording, trackRecordingStartTime = startTime) }
    }

    private fun handleBookmarks(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        if (buffer.remaining() < 4) return
        val count = buffer.int
        val currentState = NavigationStateHolder.state.value
        val categories = mutableListOf<BookmarkCategoryItem>()
        
        for (i in 0 until count) {
            if (buffer.remaining() < 8) break
            val id = buffer.long
            val nameLen = buffer.int
            if (buffer.remaining() < nameLen + 9) break
            val name = ByteArray(nameLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
            val isVisible = buffer.get().toInt() == 1
            val bookmarksCount = buffer.int
            val tracksCount = buffer.int
            
            val oldCat = currentState.bookmarkCategories.find { it.id == id }
            categories.add(BookmarkCategoryItem(id, name, isVisible, bookmarksCount, tracksCount, oldCat?.isSyncing ?: false))
        }
        
        NavigationStateHolder.update { it.copy(bookmarkCategories = categories) }
    }


    private fun handleNavigationStatus(context: Context, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        if (!buffer.hasRemaining()) return
        
        val isActive = buffer.get().toInt() == 1
        if (!isActive) {
            NavigationStateHolder.update { it.copy(isActive = false, isNavigating = false) }
            return
        }

        if (buffer.remaining() < 63) return // Minimal header size
        
        val carDirection = buffer.get().toInt()
        val pedestrianDirection = buffer.get().toInt()
        val exitNum = buffer.get().toInt()
        val completionPercent = buffer.float.toDouble()
        val lat = buffer.double
        val lon = buffer.double
        val turnLat = buffer.double
        val turnLon = buffer.double
        val bearing = buffer.float
        val speedMps = buffer.float.toDouble()
        val speedLimitMps = buffer.float.toDouble()
        
        val routeLen = buffer.int
        val streetLen = buffer.int
        val distLen = buffer.int
        
        val nextStreet = if (streetLen > 0 && buffer.remaining() >= streetLen) {
            ByteArray(streetLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
        } else ""
        
        val distToTurn = if (distLen > 0 && buffer.remaining() >= distLen) {
            ByteArray(distLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
        } else ""
        
        val routePoints = mutableListOf<Pair<Double, Double>>()
        if (routeLen > 0 && buffer.remaining() >= routeLen * 8) {
            for (i in 0 until routeLen) {
                val rLat = buffer.float.toDouble()
                val rLon = buffer.float.toDouble()
                routePoints.add(rLat to rLon)
            }
        }

        val currentState = NavigationStateHolder.state.value
        val newState = currentState.copy(
            isActive = true,
            isNavigating = true,
            carDirection = carDirection,
            pedestrianDirection = pedestrianDirection,
            exitNum = exitNum,
            completionPercent = completionPercent,
            lat = lat,
            lon = lon,
            turnLat = turnLat,
            turnLon = turnLon,
            bearing = bearing,
            speedMps = speedMps,
            speedLimitMps = speedLimitMps,
            nextStreet = nextStreet,
            distToTurn = distToTurn,
            routePoints = if (routePoints.isNotEmpty()) routePoints else currentState.routePoints
        )
        
        // Update native location
        val wearApp = context.applicationContext as WearApplication
        if (!newState.standaloneMode && lat != 0.0 && wearApp.isFullyInitialized) {
            try {
                app.organicmaps.sdk.location.LocationState.nativeLocationUpdated(
                    System.currentTimeMillis(), lat, lon, 5.0f, 0.0, speedMps.toFloat(), bearing
                )
            } catch (_: Throwable) {}
        }

        NavigationStateHolder.update(newState)
        if (!currentState.isActive) launchOmaps(context)
    }


    private fun handleSearchResults(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        if (!buffer.hasRemaining()) return
        
        val isSearching = buffer.get().toInt() == 1
        val results = mutableListOf<SearchResultItem>()
        
        while (buffer.remaining() >= 4) {
            try {
                val nameLen = buffer.int
                if (buffer.remaining() < nameLen) break
                val name = ByteArray(nameLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
                
                val descLen = buffer.int
                if (buffer.remaining() < descLen) break
                val desc = ByteArray(descLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
                
                val lat = buffer.double
                val lon = buffer.double
                
                val distLen = buffer.int
                if (buffer.remaining() < distLen) break
                val distance = ByteArray(distLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
                
                val featureLen = buffer.int
                if (buffer.remaining() < featureLen) break
                val featureType = ByteArray(featureLen).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
                
                results.add(SearchResultItem(
                    name = name,
                    description = desc,
                    lat = lat,
                    lon = lon,
                    type = 2,
                    distance = distance,
                    featureType = featureType
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing search result item", e)
                break
            }
        }
        
        NavigationStateHolder.update { it.copy(searchResults = results, isSearching = isSearching) }
    }

    private fun handleSearchHistory(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        if (buffer.remaining() < 4) return
        
        val count = buffer.int
        val history = mutableListOf<String>()
        
        for (i in 0 until count) {
            if (buffer.remaining() < 4) break
            val len = buffer.int
            if (buffer.remaining() < len) break
            val item = ByteArray(len).also { buffer.get(it) }.toString(StandardCharsets.UTF_8)
            history.add(item)
        }
        
        NavigationStateHolder.update { it.copy(searchHistory = history) }
    }


    private fun handleMapDownloadRequest(context: Context, countryId: String) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mapDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
        
        if (mapDownloadMode == "PHONE_SYNC") {
            Log.d(TAG, "Watch is in PHONE_SYNC mode, requesting streaming from phone for $countryId")
            WearCommandService.sendMapDownloadRequest(context, countryId)
            return
        }

        if (mapDownloadMode != "DIRECT_DOWNLOAD") {
            Log.d(TAG, "Ignoring map download request from phone due to sync mode: $mapDownloadMode")
            return
        }

        prefs.edit().putBoolean("forceWatchLocalMode", true).apply()
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(openMapManager = true, watchLocalMode = true))

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                System.loadLibrary("organicmaps")
                val wearApp = context.applicationContext as WearApplication
                wearApp.waitForInitializationBlocking()
                
                app.organicmaps.sdk.downloader.MapManager.startDownload(countryId)
                app.organicmaps.sdk.downloader.MapManager.startDownload("World")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        launchOmaps(context)
    }

    private fun handleBackendSwitch(context: Context, newBackend: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("pref_wear_os_backend", newBackend).apply()
            WearCommandService.initBackend(context)
            if (newBackend == "BLUETOOTH") {
                context.startService(Intent(context, BluetoothWearDataListenerService::class.java))
            } else if (BuildConfig.FLAVOR != "oss") {
                context.stopService(Intent(context, BluetoothWearDataListenerService::class.java))
            }
        }
    }

    private fun handleBookmarkFile(context: Context, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        if (buffer.remaining() < 10) return
        val catId = buffer.long
        val isLast = buffer.get().toInt() == 1
        val fileNameLen = buffer.get().toInt()
        if (buffer.remaining() < fileNameLen) return
        val fileNameBytes = ByteArray(fileNameLen)
        buffer.get(fileNameBytes)
        val fileName = String(fileNameBytes, StandardCharsets.UTF_8)
        
        val chunk = ByteArray(buffer.remaining())
        buffer.get(chunk)
        
        try {
            val fos = bookmarkOutputStreams.getOrPut(catId) {
                val file = java.io.File(context.cacheDir, fileName + ".tmp")
                WearMapDownloader.setStreamingMap("Bookmarks: $catId")
                java.io.FileOutputStream(file)
            }
            fos.write(chunk)
            if (isLast) {
                fos.close()
                bookmarkOutputStreams.remove(catId)
                val tmpFile = java.io.File(context.cacheDir, fileName + ".tmp")
                val finalFile = java.io.File(context.cacheDir, fileName)
                tmpFile.renameTo(finalFile)
                WearMapDownloader.onDownloadCompleted()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
                        // Fix duplicates: check if a category with the same name already exists
                        val catName = fileName.removeSuffix(".kml").removeSuffix(".kmz").removeSuffix(".gpx")
                        val existing = manager.categories.find { it.name.equals(catName, ignoreCase = true) }
                        if (existing != null) {
                            Log.d(TAG, "Deleting existing category '$catName' before importing update")
                            manager.deleteCategory(existing.id)
                        }

                        manager.loadBookmarksFile(finalFile.absolutePath, true)
                        android.widget.Toast.makeText(context, "Bookmarks synchronized", android.widget.Toast.LENGTH_SHORT).show()
                        // Refresh
                        NavigationStateHolder.update { current ->
                             val updatedCats = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories().map { cat ->
                                 val oldCat = current.bookmarkCategories.find { it.id == cat.id }
                                 BookmarkCategoryItem(
                                     cat.id, cat.name, cat.isVisible, cat.bookmarksCount, cat.tracksCount,
                                     isSyncing = if (cat.id == catId) false else (oldCat?.isSyncing ?: false)
                                 )
                             }
                             current.copy(bookmarkCategories = updatedCats)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load imported bookmarks", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bookmark chunk", e)
            bookmarkOutputStreams.remove(catId)?.close()
        }
    }

    private fun handleVirtualMwmData(data: ByteArray) {
        val wearApp = WearApplication.instance
        if (!wearApp.isFullyInitialized) {
            Log.w(TAG, "Ignoring MWM data: framework not initialized")
            return
        }
        val buffer = ByteBuffer.wrap(data)
        val nameLen = buffer.int
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val mwmName = String(nameBytes, StandardCharsets.UTF_8)
        val offset = buffer.long
        val mwmData = ByteArray(buffer.remaining())
        buffer.get(mwmData)

        VirtualMwmManager.onBytesReceived(mwmName, offset, mwmData)
    }

    private fun handleVirtualMwmMount(context: Context, data: ByteArray) {
        val wearApp = context.applicationContext as WearApplication
        if (!wearApp.isFullyInitialized) {
            Log.w(TAG, "Ignoring MWM mount: framework not initialized")
            return
        }
        val buffer = ByteBuffer.wrap(data)
        val nameLen = buffer.int
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val mwmName = String(nameBytes, StandardCharsets.UTF_8)
        val totalSize = buffer.long
        VirtualMwmManager.mount(context, mwmName, totalSize)
    }

    private fun launchOmaps(context: Context) {
        val intent = Intent(context, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}
