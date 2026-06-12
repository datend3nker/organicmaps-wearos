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
    private const val TAG = "WearCommandService"
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
        val defaultBackend = if (BuildConfig.FLAVOR == "oss") "BLUETOOTH" else "GMS"
        val selectedBackend = prefs.getString("pref_wear_os_backend", defaultBackend)

        backend?.stop()
        backend = null 
        
        NavigationStateHolder.update { it.copy(isPhoneConnected = false) }
        
        val isStandalone = selectedBackend == "STANDALONE"
        val useBluetooth = selectedBackend == "BLUETOOTH" && !isStandalone
        
        backend = if (useBluetooth || isStandalone) {
            BluetoothWearSyncBackend()
        } else {
            BackendProvider.getGmsBackend()
        }

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
        app.organicmaps.sdk.sync.WearLog.logState("WATCH", "UI Search Request: '$query'. Standalone=${navState.standaloneMode}, Connected=${navState.isPhoneConnected}")
        
        ensureSearchInitialized(context)
        
        if (navState.standaloneMode || !navState.isPhoneConnected) {
            val hasLocation = navState.lat != 0.0
            val lat = navState.lat
            val lon = navState.lon
            
            app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Standalone search at $lat, $lon (hasLocation=$hasLocation)")
            
            val zoom = if (hasLocation) 13 else 1
            app.organicmaps.sdk.Framework.nativeSetSearchViewport(lat, lon, zoom)
            
            SearchEngine.INSTANCE.initialize()
            val success = SearchEngine.INSTANCE.search(
                context, query, false, System.nanoTime(), 
                hasLocation, lat, lon
            )
            Log.d(TAG, "DEBUG_WEAR_SEARCH: SearchEngine.search returned $success")
        } else {
            app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Requesting phone search at ${navState.lat}, ${navState.lon}")
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
                app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Received local results. Count: ${mapped.size}")
                NavigationStateHolder.update { it.copy(searchResults = mapped, isSearching = true) }
            }

            override fun onResultsEnd(timestamp: Long) {
                app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Local results END")
                NavigationStateHolder.update { it.copy(isSearching = false) }
            }
        })
        isSearchInitialized = true
    }

    fun requestSearchHistory(context: Context) = getBackend(context).requestSearchHistory(context)
    fun requestDownloadedMaps(context: Context) = getBackend(context).requestDownloadedMaps(context)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val navState = NavigationStateHolder.state.value
        val isStandalone = navState.standaloneMode || !navState.isPhoneConnected
        
        if (!isStandalone) {
            launchPhoneApp(context)
            
            CoroutineScope(Dispatchers.Main).launch {
                delay(4000)
                val latestState = NavigationStateHolder.state.value
                if (!latestState.isPhoneConnected && latestState.isActive && !latestState.isNavigating && !latestState.watchLocalMode) {
                    Log.d("WearCommand", "Phone failed to connect for routing - falling back to standalone")
                    NavigationStateHolder.emitEvent(UiEvent.ShowToast("Phone unavailable. Calculating locally."))
                    NavigationStateHolder.update { it.copy(watchLocalMode = true) }
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
                isActive = true
            ))
            
            val wearApp = context.applicationContext as WearApplication
            val routing = RoutingController.get()
            val router = Router.values().getOrNull(routerType) ?: Router.Vehicle
            
            val myPos = wearApp.organicMaps.locationHelper.savedLocation?.let { 
                MapObject.createMapObject(MapObject.POI, "My Location", "", it.latitude, it.longitude)
            }
            val startPoint = myPos
            
            val endPoint = MapObject.createMapObject(MapObject.SEARCH, result.name, result.description, result.lat, result.lon)
            
            app.organicmaps.sdk.search.SearchRecents.add(result.name, context)

            routing.prepare(startPoint, endPoint, router)
        } else {
            getBackend(context).selectSearchResult(context, result, routerType)
        }
    }


    fun sendPong(context: Context, nodeId: String) = getBackend(context).sendPong(context, nodeId)

    fun requestMwmMetadata(context: Context, mwmName: String) = getBackend(context).requestMwmMetadata(context, mwmName)

    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncRunnable = Runnable {
        val ctx = WearApplication.instance
        val manager = SettingsSyncManager.getInstance(ctx)
        val dirty = manager.getDirtyUpdates()
        if (dirty.isNotEmpty()) {
            getBackend(ctx).syncPreferenceUpdates(ctx, dirty)
        } else {
            getBackend(ctx).syncPreferences(ctx)
        }
    }

    fun sendPing(context: Context) = getBackend(context).sendPing(context)
    fun sendHandshake(context: Context) = getBackend(context).sendHandshake(context)
    fun syncPreferences(context: Context) {
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.postDelayed(syncRunnable, 100) 
    }
    fun requestPreferences(context: Context) = getBackend(context).requestPreferences(context)
    fun syncSearchHistory(context: Context) = getBackend(context).syncSearchHistory(context)
    fun checkConnection(context: Context, callback: (Boolean, String?) -> Unit) = getBackend(context).checkConnection(context, callback)
    fun startNavigation(context: Context) = getBackend(context).startNavigation(context)
    fun showOnPhone(context: Context, result: SearchResultItem) = getBackend(context).showOnPhone(context, result)
    fun cancelMapSync(context: Context, mapId: String) = getBackend(context).cancelMapSync(context, mapId)
    fun sendBackendSwitch(context: Context, newBackend: String) = getBackend(context).sendBackendSwitch(context, newBackend)
    fun sendMapDownloadRequest(context: Context, mapId: String, offset: Long = 0, checksum: Long = 0) {
        val navState = NavigationStateHolder.state.value
        if (navState.mapDownloadMode == "PHONE_SYNC" && !navState.standaloneMode && navState.isPhoneConnected) {
            getBackend(context).sendMapDownloadRequest(context, mapId, offset, checksum)
        } else {
            app.organicmaps.sdk.downloader.MapManager.startDownload(mapId)
        }
    }
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
        } else {
            getBackend(context).toggleTrackRecording(context)
        }
    }
    fun requestBookmarks(context: Context) = getBackend(context).requestBookmarks(context)
    fun toggleBookmarkCategory(context: Context, categoryName: String) = getBackend(context).toggleBookmarkCategory(context, categoryName)
    fun showBookmark(context: Context, bmkId: Long) {
        val navState = NavigationStateHolder.state.value
        val effectivelyStandalone = navState.isEffectivelyStandalone
        if (effectivelyStandalone) {
            try {
                // If native framework is not ready, we might need a fallback or wait, 
                // but usually it's initialized if we are seeing bookmarks locally.
                app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.showBookmarkOnMap(bmkId)
                NavigationStateHolder.emitEvent(UiEvent.OpenMap)
                // Force lock map to center on bookmark
                NavigationStateHolder.update { it.copy(isMapUnlocked = false) }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show bookmark $bmkId locally", e)
            }
        } else {
            // Companion mode: Request phone to show it
            getBackend(context).showBookmarkOnPhone(context, bmkId)
        }
    }

    fun updateBookmark(context: Context, bmkId: Long, name: String, color: Int) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || navState.watchLocalMode) {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getBookmarkInfo(bmkId)?.update(name, null, "")
        } else {
            getBackend(context).updateBookmarkOnPhone(context, bmkId, name, color)
        }
    }

    fun syncCategory(context: Context, categoryName: String) {
        NavigationStateHolder.update { current ->
            val updated = current.bookmarkCategories.map {
                if (it.name.equals(categoryName, ignoreCase = true)) it.copy(isSyncing = true) else it
            }
            current.copy(bookmarkCategories = updated)
        }
        getBackend(context).syncCategory(context, categoryName)
    }

    fun renameBookmarkCategory(context: Context, oldName: String, newName: String) {
        getBackend(context).renameBookmarkCategory(context, oldName, newName)
    }

    fun deleteBookmarkCategory(context: Context, name: String) {
        getBackend(context).deleteBookmarkCategory(context, name)
    }

    fun sendBookmarkFile(context: Context, categoryName: String, data: ByteArray, isLast: Boolean, merge: Boolean = false) {
        getBackend(context).sendBookmarkFile(context, categoryName, data, isLast, merge)
    }

    fun sendBookmarksMetadata(context: Context, payload: ByteArray) {
        getBackend(context).sendBookmarksMetadata(context, payload)
    }

    fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int) = getBackend(context).requestMwmBytes(context, mwmName, offset, size)
    fun launchPhoneApp(context: Context) = getBackend(context).launchPhoneApp(context)
}
