package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.bookmarks.data.BookmarkManager
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.search.SearchEngine
import app.organicmaps.sdk.search.SearchListener
import app.organicmaps.sdk.search.SearchResult
import app.organicmaps.sdk.search.SearchRecents
import app.organicmaps.sdk.sync.WearLog
import app.organicmaps.sdk.location.TrackRecorder
import app.organicmaps.sdk.Framework
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

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
        val selectedBackend = prefs.getString("pref_wear_os_backend", defaultBackend) ?: defaultBackend

        backend?.stop()
        backend = null 
        
        val isStandalone = selectedBackend == "STANDALONE"
        // Keep the UI state's backend authoritative for the active transport, so the
        // connection indicator stays correct across every caller of initBackend
        // (settings UI, phone-driven handleBackendSwitch, fallbacks), not just the
        // settings screen.
        NavigationStateHolder.update {
            it.copy(
                backend = selectedBackend,
                standaloneMode = isStandalone,
                isPhoneConnected = false,
                isConnecting = !isStandalone
            )
        }
        
        val useBluetooth = selectedBackend == "BLUETOOTH" && !isStandalone
        
        backend = if (useBluetooth || isStandalone) {
            BluetoothWearSyncBackend()
        } else {
            BackendProvider.getGmsBackend()
        }

        if (!isStandalone) {
            // Immediate handshake attempt on switch
            getBackend(context).sendPing(context)
            getBackend(context).sendHandshake(context)
        }

        val serviceIntent = Intent(context, BluetoothWearDataListenerService::class.java)
        if (useBluetooth) {
            context.startService(serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    fun stopNavigation(context: Context) = getBackend(context).stopNavigation(context)

    // Watchdog so the search spinner never hangs forever: cleared on results-end / fresh search,
    // fired if neither the local engine nor the phone produces a result in time.
    private val searchWatchdogHandler = Handler(Looper.getMainLooper())
    private val searchWatchdogRunnable = Runnable {
        if (NavigationStateHolder.state.value.isSearching) {
            Log.w(TAG, "DEBUG_WEAR_SEARCH: search timed out; clearing spinner")
            NavigationStateHolder.update { it.copy(isSearching = false) }
        }
    }

    private fun armSearchWatchdog() {
        searchWatchdogHandler.removeCallbacks(searchWatchdogRunnable)
        searchWatchdogHandler.postDelayed(searchWatchdogRunnable, 12000)
    }

    fun cancelSearchWatchdog() {
        searchWatchdogHandler.removeCallbacks(searchWatchdogRunnable)
    }

    fun search(context: Context, query: String) {
        val navState = NavigationStateHolder.state.value
        armSearchWatchdog()
        WearLog.logState("WATCH", "UI Search Request: '$query'. Standalone=${navState.standaloneMode}, Connected=${navState.isPhoneConnected}")
        
        ensureSearchInitialized(context)

        // Context-aware: search on-watch data whenever the watch is the data source
        // (standalone, Local Maps enabled, or phone unreachable); otherwise ask the phone.
        if (navState.standaloneMode || navState.watchLocalMode || !navState.isPhoneConnected) {
            val hasLocation = navState.lat != 0.0
            val lat = navState.lat
            val lon = navState.lon
            
            WearLog.logState("WATCH", "Standalone search at $lat, $lon (hasLocation=$hasLocation)")
            
            val zoom = if (hasLocation) 13 else 1
            Framework.nativeSetSearchViewport(lat, lon, zoom)
            
            SearchEngine.INSTANCE.initialize()
            val success = SearchEngine.INSTANCE.search(
                context, query, false, System.nanoTime(),
                hasLocation, lat, lon
            )
            Log.d(TAG, "DEBUG_WEAR_SEARCH: SearchEngine.search returned $success")
            if (!success) {
                // The engine refused to start the query (e.g. busy/empty) — no onResultsEnd will
                // ever fire, so clear the spinner now instead of hanging.
                cancelSearchWatchdog()
                NavigationStateHolder.update { it.copy(isSearching = false) }
            }
        } else {
            WearLog.logState("WATCH", "Requesting phone search at ${navState.lat}, ${navState.lon}")
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
                WearLog.logState("WATCH", "Received local results. Count: ${mapped.size}")
                NavigationStateHolder.update { it.copy(searchResults = mapped, isSearching = true) }
            }

            override fun onResultsEnd(timestamp: Long) {
                WearLog.logState("WATCH", "Local results END")
                cancelSearchWatchdog()
                NavigationStateHolder.update { it.copy(isSearching = false) }
            }
        })
        isSearchInitialized = true
    }

    fun requestSearchHistory(context: Context) = getBackend(context).requestSearchHistory(context)
    fun requestDownloadedMaps(context: Context) = getBackend(context).requestDownloadedMaps(context)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) {
        val navState = NavigationStateHolder.state.value
        // Treat Local Maps mode as a local (watch-resident) routing source too, so we
        // don't launch the phone app while also routing locally.
        val isStandalone = navState.standaloneMode || navState.watchLocalMode || !navState.isPhoneConnected

        if (!isStandalone) {
            launchPhoneApp(context)
            
            CoroutineScope(Dispatchers.Main).launch {
                delay(4000.milliseconds)
                val latestState = NavigationStateHolder.state.value
                if (!latestState.isPhoneConnected && latestState.isActive && !latestState.isNavigating && !latestState.watchLocalMode) {
                    Log.d("WearCommand", "Phone failed to connect for routing - falling back to standalone")
                    NavigationStateHolder.emitEvent(UiEvent.ShowToast("Phone unavailable. Calculating locally."))
                    NavigationStateHolder.update { it.copy(watchLocalMode = true) }
                    selectSearchResult(context, result, routerType)
                }
            }
        }

        if (isStandalone) {
            NavigationStateHolder.update(navState.copy(
                destinationName = result.name,
                routerType = routerType,
                isRouteBuilding = true,
                isRouteReady = false,
                isActive = true
            ))
            
            val wearApp = context.applicationContext as WearApplication
            val routing = RoutingController.get()
            val router = Router.entries.getOrNull(routerType) ?: Router.Vehicle
            
            val myPos = wearApp.organicMaps.locationHelper.savedLocation?.let { 
                MapObject.createMapObject(MapObject.POI, "My Location", "", it.latitude, it.longitude)
            }
            val startPoint = myPos
            
            val endPoint = MapObject.createMapObject(MapObject.SEARCH, result.name, result.description, result.lat, result.lon)
            
            SearchRecents.add(result.name, context)

            routing.prepare(startPoint, endPoint, router)
        } else {
            getBackend(context).selectSearchResult(context, result, routerType)
        }
    }


    fun sendPong(context: Context, nodeId: String) = getBackend(context).sendPong(context, nodeId)

    fun requestMwmMetadata(context: Context, mwmName: String) = getBackend(context).requestMwmMetadata(context, mwmName)

    private val syncHandler = Handler(Looper.getMainLooper())
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
    fun syncPreferences(context: Context? = null) {
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.postDelayed(syncRunnable, 100) 
    }
    fun requestPreferences(context: Context) = getBackend(context).requestPreferences(context)
    fun syncSearchHistory(context: Context) = getBackend(context).syncSearchHistory(context)
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
            val isRecording = TrackRecorder.nativeIsTrackRecordingEnabled()
            if (isRecording) {
                if (!TrackRecorder.nativeIsTrackRecordingEmpty()) {
                    TrackRecorder.nativeSaveTrackRecordingWithName("")
                }
                TrackRecorder.nativeStopTrackRecording()
                NavigationStateHolder.update { it.copy(isTrackRecording = false, trackRecordingStartTime = 0L) }
            } else {
                TrackRecorder.nativeStartTrackRecording()
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
                BookmarkManager.INSTANCE.showBookmarkOnMap(bmkId)
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

    fun updateBookmark(context: Context, bmkId: Long, name: String, color: Int, categoryId: Long = -1) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || navState.watchLocalMode) {
            val info = BookmarkManager.INSTANCE.getBookmarkInfo(bmkId)
            info?.update(name, null, "")
            if (categoryId != -1L && info != null && info.categoryId != categoryId) {
                info.changeCategory(categoryId)
            }
            WatchBookmarkSyncManager.onLocalBookmarksChanged(context, true)
            WatchBookmarkSyncManager.requestSync(context)
        } else {
            getBackend(context).updateBookmarkOnPhone(context, bmkId, name, color, categoryId)
        }
    }

    fun createBookmarkCategory(context: Context, name: String) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || navState.watchLocalMode) {
            BookmarkManager.INSTANCE.createCategory(name)
            WatchBookmarkSyncManager.onLocalBookmarksChanged(context, true)
            WatchBookmarkSyncManager.requestSync(context)
        } else {
            getBackend(context).createBookmarkCategory(context, name)
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
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || navState.watchLocalMode) {
            val manager = BookmarkManager.INSTANCE
            manager.getCategories().find { it.name.equals(oldName, ignoreCase = true) }?.name = newName
            WatchBookmarkSyncManager.onLocalBookmarksChanged(context, true)
            WatchBookmarkSyncManager.requestSync(context)
        } else {
            getBackend(context).renameBookmarkCategory(context, oldName, newName)
        }
    }

    fun deleteBookmarkCategory(context: Context, name: String) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || navState.watchLocalMode) {
            val manager = BookmarkManager.INSTANCE
            val category = manager.getCategories().find { it.name.equals(name, ignoreCase = true) }
            category?.let { manager.deleteCategory(it.id) }
            WatchBookmarkSyncManager.onLocalBookmarksChanged(context, true)
            WatchBookmarkSyncManager.requestSync(context)
        } else {
            getBackend(context).deleteBookmarkCategory(context, name)
        }
    }

    fun sendBookmarkFile(context: Context, categoryName: String, data: ByteArray, isLast: Boolean, merge: Boolean = false) {
        getBackend(context).sendBookmarkFile(context, categoryName, data, isLast, merge)
    }

    fun sendBookmarksMetadata(context: Context, payload: ByteArray) {
        getBackend(context).sendBookmarksMetadata(context, payload)
    }

    fun sendBookmarkUpsert(context: Context, payload: ByteArray) {
        getBackend(context).sendBookmarkUpsert(context, payload)
    }

    fun sendBookmarkTombstone(context: Context, payload: ByteArray) {
        getBackend(context).sendBookmarkTombstone(context, payload)
    }

    fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int) = getBackend(context).requestMwmBytes(context, mwmName, offset, size)
    fun launchPhoneApp(context: Context) = getBackend(context).launchPhoneApp(context)
}
