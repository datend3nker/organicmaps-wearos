package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.search.SearchEngine
import app.organicmaps.sdk.search.SearchListener
import app.organicmaps.sdk.search.SearchResult
import kotlinx.coroutines.*

object WearCommandService {
    private var backend: IWearSyncBackend? = null

    @Synchronized
    private fun getBackend(context: Context): IWearSyncBackend {
        if (backend == null) {
            initBackend(context)
        }
        return backend!!
    }

    @Synchronized
    fun initBackend(context: Context) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val selectedBackend = prefs.getString("pref_wear_os_backend", "GMS")
        val isOss = BuildConfig.FLAVOR == "oss"

        // Properly dispose of previous backend if it exists
        backend?.stop()
        backend = null 
        
        // Reset connection status during transition
        NavigationStateHolder.update { it.copy(isPhoneConnected = false) }
        
        val isStandalone = selectedBackend == "STANDALONE"
        val useBluetooth = (selectedBackend == "BLUETOOTH" || isOss) && !isStandalone
        
        backend = if (useBluetooth) {
            BluetoothWearSyncBackend()
        } else if (isStandalone) {
            // Optional: dedicated Standalone backend that does nothing for remote calls
            BluetoothWearSyncBackend() 
        } else {
            try {
                Class.forName("app.organicmaps.wear.GmsWearSyncBackend")
                    .getDeclaredConstructor().newInstance() as IWearSyncBackend
            } catch (e: Exception) {
                BluetoothWearSyncBackend()
            }
        }

        // Ensure background listener service is running for Bluetooth
        val serviceIntent = Intent(context, BluetoothWearDataListenerService::class.java)
        if (useBluetooth) {
            context.startService(serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    fun stopNavigation(context: Context) = getBackend(context).stopNavigation(context)
    
    fun search(context: Context, query: String) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || !navState.isPhoneConnected) {
            ensureSearchInitialized(context)
            val hasLocation = navState.lat != 0.0
            SearchEngine.INSTANCE.search(
                context, query, false, System.currentTimeMillis(), 
                hasLocation, navState.lat, navState.lon
            )
        } else {
            getBackend(context).search(context, query, navState.lat, navState.lon)
        }
    }

    private var isSearchInitialized = false
    private fun ensureSearchInitialized(context: Context) {
        if (isSearchInitialized) return
        val searchEngine = SearchEngine.INSTANCE
        searchEngine.addListener(object : SearchListener {
            override fun onResultsUpdate(results: Array<out SearchResult>, timestamp: Long) {
                val mapped = results.map {
                    SearchResultItem(
                        name = it.getTitle(context) ?: "",
                        description = it.description?.localizedFeatureType ?: it.description?.region ?: "",
                        lat = it.lat,
                        lon = it.lon,
                        type = it.type
                    )
                }
                NavigationStateHolder.update { it.copy(searchResults = mapped, isSearching = true) }
            }

            override fun onResultsEnd(timestamp: Long) {
                NavigationStateHolder.update { it.copy(isSearching = false) }
            }
        })
        isSearchInitialized = true
    }

    fun requestSearchHistory(context: Context) = getBackend(context).requestSearchHistory(context)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val navState = NavigationStateHolder.state.value
        val isStandalone = navState.standaloneMode || !navState.isPhoneConnected
        
        if (!isStandalone) {
            // Attempt to wake up phone for calculation
            launchPhoneApp(context)
            
            // Start a timeout to fallback to standalone if phone doesn't respond/connect
            CoroutineScope(Dispatchers.Main).launch {
                delay(4000) // Give phone 4s to wake up and connect
                val latestState = NavigationStateHolder.state.value
                if (!latestState.isPhoneConnected && latestState.isActive && !latestState.isNavigating && !latestState.watchLocalMode) {
                    Log.d("WearCommand", "Phone failed to connect for routing - falling back to standalone")
                    android.widget.Toast.makeText(context, "Phone unavailable. Calculating locally.", android.widget.Toast.LENGTH_LONG).show()
                    NavigationStateHolder.update { it.copy(watchLocalMode = true) }
                    // Re-trigger with standalone logic
                    selectSearchResult(context, result, routerType)
                }
            }
        }

        if (navState.standaloneMode || !navState.isPhoneConnected || navState.watchLocalMode) {
            NavigationStateHolder.update(navState.copy(
                destinationName = result.name,
                routerType = routerType,
                isRouteBuilding = true,
                isRouteReady = false,
                isActive = true // Transition to map/nav view
            ))
            
            val wearApp = context.applicationContext as WearApplication
            val routing = RoutingController.get()
            val router = Router.values().getOrNull(routerType) ?: Router.Vehicle
            
            // Force POI type for fallback coordinates to ensure native engine uses them immediately
            // and doesn't wait for internal native LocationState fix (unblocks NO_POSITION error)
            val myPos = wearApp.organicMaps.locationHelper.savedLocation?.let { 
                MapObject.createMapObject(MapObject.POI, "My Location", "", it.latitude, it.longitude)
            }
            val startPoint = myPos
            
            val endPoint = MapObject.createMapObject(MapObject.SEARCH, result.name, result.description, result.lat, result.lon)
            
            // Add to local history
            app.organicmaps.sdk.search.SearchRecents.add(result.name, context)

            // CRITICAL: Use prepare to initialize everything correctly on the native side
            routing.prepare(startPoint, endPoint, router)
        } else {
            getBackend(context).selectSearchResult(context, result, routerType)
        }
    }


    fun sendPong(context: Context, nodeId: String) = getBackend(context).sendPong(context, nodeId)

    fun requestMwmMetadata(context: Context, mwmName: String) = getBackend(context).requestMwmMetadata(context, mwmName)

    fun sendPing(context: Context) = getBackend(context).sendPing(context)
    fun syncPreferences(context: Context) = getBackend(context).syncPreferences(context)
    fun requestPreferences(context: Context) = getBackend(context).requestPreferences(context)
    fun syncSearchHistory(context: Context) = getBackend(context).syncSearchHistory(context)
    fun checkConnection(context: Context, callback: (Boolean) -> Unit) = getBackend(context).checkConnection(context, callback)
    fun startNavigation(context: Context) = getBackend(context).startNavigation(context)
    fun showOnPhone(context: Context, result: SearchResultItem) = getBackend(context).showOnPhone(context, result)
    fun cancelMapSync(context: Context, mapId: String) = getBackend(context).cancelMapSync(context, mapId)
    fun sendBackendSwitch(context: Context, newBackend: String) = getBackend(context).sendBackendSwitch(context, newBackend)
    fun sendMapDownloadRequest(context: Context, mapId: String) {
        val navState = NavigationStateHolder.state.value
        if (navState.mapDownloadMode == "PHONE_SYNC" && !navState.standaloneMode && navState.isPhoneConnected) {
            getBackend(context).sendMapDownloadRequest(context, mapId)
        } else {
            // DIRECT_DOWNLOAD or Standalone
            app.organicmaps.sdk.downloader.MapManager.startDownload(mapId)
        }
    }
    fun sendMapProgress(context: Context, mapId: String, progress: Int) = getBackend(context).sendMapProgress(context, mapId, progress)
    fun toggleTrackRecording(context: Context) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || !navState.isPhoneConnected) {
            val isRecording = app.organicmaps.sdk.location.TrackRecorder.nativeIsTrackRecordingEnabled()
            if (isRecording) {
                if (!app.organicmaps.sdk.location.TrackRecorder.nativeIsTrackRecordingEmpty()) {
                    app.organicmaps.sdk.location.TrackRecorder.nativeSaveTrackRecordingWithName("")
                }
                app.organicmaps.sdk.location.TrackRecorder.nativeStopTrackRecording()
                NavigationStateHolder.update { it.copy(isTrackRecording = false, trackRecordingStartTime = 0L) }
            } else {
                app.organicmaps.sdk.location.TrackRecorder.nativeStartTrackRecording()
                NavigationStateHolder.update { it.copy(isTrackRecording = true, trackRecordingStartTime = System.currentTimeMillis()) }
            }
        }
 else {
            getBackend(context).toggleTrackRecording(context)
        }
    }
    fun requestBookmarks(context: Context) = getBackend(context).requestBookmarks(context)
    fun toggleBookmarkCategory(context: Context, categoryId: Long) = getBackend(context).toggleBookmarkCategory(context, categoryId)
    fun showBookmark(context: Context, bmkId: Long) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || navState.watchLocalMode) {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.showBookmarkOnMap(bmkId)
            NavigationStateHolder.update { it.copy(openMap = true) }
        } else {
            getBackend(context).showBookmarkOnPhone(context, bmkId)
        }
    }

    fun updateBookmark(context: Context, bmkId: Long, name: String, color: Int) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || navState.watchLocalMode) {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getBookmarkInfo(bmkId)?.update(name, null, "")
            // Note: color update is a bit more complex in native, we'd need to convert index
        } else {
            getBackend(context).updateBookmarkOnPhone(context, bmkId, name, color)
        }
    }

    fun syncCategory(context: Context, categoryId: Long) {
        NavigationStateHolder.update { current ->
            val updated = current.bookmarkCategories.map {
                if (it.id == categoryId) it.copy(isSyncing = true) else it
            }
            current.copy(bookmarkCategories = updated)
        }
        getBackend(context).syncCategory(context, categoryId)
    }
    fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int) = getBackend(context).requestMwmBytes(context, mwmName, offset, size)
    fun launchPhoneApp(context: Context) = getBackend(context).launchPhoneApp(context)
}
