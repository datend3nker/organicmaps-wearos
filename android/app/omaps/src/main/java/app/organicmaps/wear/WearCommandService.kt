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

    /**
     * Single-press navigation cancel. Cancels the watch's local routing controller, tells the phone
     * to stop (companion mode), clears the local nav state, and arms a guard window so stale
     * in-flight active=true status frames from the phone don't bounce the UI back into navigation
     * (the old "press X twice" bug). Use everywhere the user cancels/stops navigation.
     */
    fun cancelNavigation(context: Context) {
        val navState = NavigationStateHolder.state.value
        // Arm first so any status frame racing in during this call is already suppressed.
        NavigationStateHolder.navCancelGuardUntil = System.currentTimeMillis() + 4000L
        try { app.organicmaps.sdk.routing.RoutingController.get().cancel() } catch (_: Throwable) {}
        if (!navState.standaloneMode) {
            stopNavigation(context)
        }
        try { app.organicmaps.sdk.Framework.nativeRemoveRouteLine() } catch (_: Throwable) {}
        NavigationStateHolder.update(
            navState.copy(
                isActive = false,
                isNavigating = false,
                isRouteBuilding = false,
                isRouteBuilt = false,
                isRouteReady = false,
                lastRouteError = 0,
                isMapUnlocked = false
            ),
            force = true
        )
    }

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
                val saved = !TrackRecorder.nativeIsTrackRecordingEmpty()
                if (saved) {
                    TrackRecorder.nativeSaveTrackRecordingWithName("")
                }
                TrackRecorder.nativeStopTrackRecording()
                NavigationStateHolder.update { it.copy(isTrackRecording = false, trackRecordingStartTime = 0L) }
                if (saved) WatchTrackSyncManager.onLocalTracksChanged(context)
            } else {
                TrackRecorder.nativeStartTrackRecording()
                NavigationStateHolder.update { it.copy(isTrackRecording = true, trackRecordingStartTime = System.currentTimeMillis()) }
            }
        } else {
            getBackend(context).toggleTrackRecording(context)
        }
    }

    /** Stop the current recording and save it (optional name). Local when standalone, else phone. */
    fun saveTrackRecording(context: Context, name: String = "") {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || !navState.isPhoneConnected) {
            var saved = false
            if (TrackRecorder.nativeIsTrackRecordingEnabled()) {
                if (!TrackRecorder.nativeIsTrackRecordingEmpty()) {
                    TrackRecorder.nativeSaveTrackRecordingWithName(name)
                    saved = true
                }
                TrackRecorder.nativeStopTrackRecording()
            }
            NavigationStateHolder.update { it.copy(isTrackRecording = false, trackRecordingStartTime = 0L, trackRecordingDistance = 0.0, trackRecordingDuration = 0.0) }
            if (saved) WatchTrackSyncManager.onLocalTracksChanged(context)
        } else {
            getBackend(context).saveTrackRecording(context, name)
        }
    }

    // ---- saved-track sync + management -------------------------------------

    fun sendTrackManifest(context: Context, payload: ByteArray) = getBackend(context).sendTrackManifest(context, payload)
    fun sendTrackTombstone(context: Context, payload: ByteArray) = getBackend(context).sendTrackTombstone(context, payload)
    fun requestTrackBlob(context: Context, payload: ByteArray) = getBackend(context).requestTrackBlob(context, payload)
    fun sendTrackBlob(context: Context, payload: ByteArray) = getBackend(context).sendTrackBlob(context, payload)

    /** Pull saved tracks from the phone (manifest-driven) and push ours. Phone reconciles on connect too. */
    fun requestTracks(context: Context) {
        getBackend(context).requestBookmarks(context) // phone answers with bookmark + track manifests
        WatchTrackSyncManager.requestSync(context)
    }

    /** Rename a saved track on the watch, then propagate via the track manifest (LWW). */
    fun renameTrack(context: Context, trackId: Long, name: String) {
        val mgr = BookmarkManager.INSTANCE
        val t = try { mgr.getTrack(trackId) } catch (e: Exception) { null } ?: return
        // Preserve the embedded sync uuid (it lives in the description) on rename.
        t.update(name, t.color, t.description)
        WatchTrackSyncManager.onLocalTracksChanged(context)
    }

    /** Delete a saved track on the watch, then propagate a tombstone. */
    fun deleteTrack(context: Context, trackId: Long) {
        try { BookmarkManager.INSTANCE.deleteTrack(trackId) } catch (e: Exception) { Log.e(TAG, "deleteTrack failed", e) }
        WatchTrackSyncManager.onLocalTracksChanged(context)
    }

    /** Center the watch's own map on a saved track. */
    fun showTrackOnWatch(context: Context, trackId: Long) {
        try {
            BookmarkManager.INSTANCE.showBookmarkCategoryOnMap(BookmarkManager.INSTANCE.getTrack(trackId).categoryId)
            NavigationStateHolder.emitEvent(UiEvent.OpenMap)
            NavigationStateHolder.update { it.copy(isMapUnlocked = false) }
        } catch (e: Exception) {
            Log.e(TAG, "showTrackOnWatch failed", e)
        }
    }

    /** Stop the current recording and discard it (clear, no save). Local when standalone, else phone. */
    fun discardTrackRecording(context: Context) {
        val navState = NavigationStateHolder.state.value
        if (navState.standaloneMode || !navState.isPhoneConnected) {
            if (TrackRecorder.nativeIsTrackRecordingEnabled()) {
                TrackRecorder.nativeClearTrackRecording()
                TrackRecorder.nativeStopTrackRecording()
            }
            NavigationStateHolder.update { it.copy(isTrackRecording = false, trackRecordingStartTime = 0L, trackRecordingDistance = 0.0, trackRecordingDuration = 0.0) }
        } else {
            getBackend(context).discardTrackRecording(context)
        }
    }
    fun requestBookmarks(context: Context) = getBackend(context).requestBookmarks(context)
    fun toggleBookmarkCategory(context: Context, categoryName: String) {
        // Apply on the watch's own native DB first so visibility flips even when the phone transport
        // is unreachable, then forward to the phone (best-effort) when actually connected.
        val cat = BookmarkManager.INSTANCE.getCategories().find { it.name.equals(categoryName, ignoreCase = true) }
        cat?.toggleVisibility()
        NavigationStateHolder.update { current ->
            current.copy(bookmarkCategories = current.bookmarkCategories.map {
                if (it.name.equals(categoryName, ignoreCase = true)) it.copy(isVisible = cat?.isVisible ?: !it.isVisible) else it
            })
        }
        if (!NavigationStateHolder.state.value.isEffectivelyStandalone)
            getBackend(context).toggleBookmarkCategory(context, categoryName)
    }
    fun showBookmark(context: Context, bmkId: Long) {
        val navState = NavigationStateHolder.state.value
        val effectivelyStandalone = navState.isEffectivelyStandalone
        if (effectivelyStandalone) {
            try {
                // If native framework is not ready, we might need a fallback or wait, 
                // but usually it's initialized if we are seeing bookmarks locally.
                val info = BookmarkManager.INSTANCE.getBookmarkInfo(bmkId)
                if (info != null) {
                    BookmarkManager.INSTANCE.showBookmarkOnMap(bmkId)
                    NavigationStateHolder.emitEvent(UiEvent.OpenMap)
                    // Force lock map to center on bookmark
                    NavigationStateHolder.update { it.copy(isMapUnlocked = false) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show bookmark $bmkId locally", e)
            }
        } else {
            // Companion mode: Request phone to show it
            val info = BookmarkManager.INSTANCE.getBookmarkInfo(bmkId)
            if (info != null) {
                val catName = BookmarkManager.INSTANCE.categories.find { it.id == info.categoryId }?.name ?: ""
                getBackend(context).showBookmarkOnPhone(context, info.name, catName, info.lat, info.lon)
            }
        }
    }

    fun updateBookmark(context: Context, bmkId: Long, name: String, color: Int, categoryId: Long = -1) {
        // Apply locally first so the edit sticks even when the phone is unreachable.
        val info = BookmarkManager.INSTANCE.getBookmarkInfo(bmkId)
        // Preserve the existing description — update("", …) would wipe it on a rename/recolor.
        info?.update(name, app.organicmaps.sdk.bookmarks.data.Icon(color, info.icon.type), info.description)
        if (categoryId != -1L && info != null) {
            // IDs from the NavigationState may be phone IDs (private to the phone's engine).
            // Resolve the destination to a local ID by name before passing it to our engine.
            val catName = NavigationStateHolder.state.value.bookmarkCategories.find { it.id == categoryId }?.name
            if (catName != null) {
                val manager = BookmarkManager.INSTANCE
                val localCatId = manager.categories.find { it.name.equals(catName, ignoreCase = true) }?.id
                    ?: manager.createCategory(catName)
                if (info.categoryId != localCatId) {
                    info.changeCategory(localCatId)
                }
            } else {
                // If we can't find it by ID in our UI state, try using the ID as-is (might already be local)
                if (info.categoryId != categoryId) {
                    info.changeCategory(categoryId)
                }
            }
        }
        // Propagate through the content-addressed channel (tombstone the old identity + upsert the new
        // one), NOT the legacy PATH_BOOKMARK_UPDATE: that carried the watch's local bookmark id, which
        // is meaningless on the phone (ids differ per engine), so the phone renamed nothing and pushed
        // the stale old-name bookmark back — the rename appeared to revert. The name is part of the
        // sync identity, so a rename = delete(old) + add(new), which onLocalBookmarksChanged +
        // requestSync express correctly for both connected and standalone.
        WatchBookmarkSyncManager.onLocalBookmarksChanged(context, true)
        WatchBookmarkSyncManager.requestSync(context)
    }

    fun createBookmarkCategory(context: Context, name: String) {
        // Skip if a same-named category already exists — OM uniquifies a collision into "nameN",
        // which is the category-explosion symptom we must avoid.
        val manager = BookmarkManager.INSTANCE
        if (manager.getCategories().none { it.name.equals(name, ignoreCase = true) })
            manager.createCategory(name)
        WatchBookmarkSyncManager.onLocalBookmarksChanged(context, true)
        if (NavigationStateHolder.state.value.isEffectivelyStandalone)
            WatchBookmarkSyncManager.requestSync(context)
        else
            getBackend(context).createBookmarkCategory(context, name)
    }

    fun syncCategory(context: Context, categoryName: String) {
        NavigationStateHolder.update { current ->
            val updated = current.bookmarkCategories.map {
                if (it.name.equals(categoryName, ignoreCase = true)) it.copy(isSyncing = true) else it
            }
            current.copy(bookmarkCategories = updated)
        }
        getBackend(context).syncCategory(context, categoryName)
        // Safety valve: reset isSyncing if KMZ file never arrives (e.g. phone has 0 bookmarks).
        CoroutineScope(Dispatchers.Main).launch {
            delay(30_000)
            NavigationStateHolder.update { current ->
                current.copy(bookmarkCategories = current.bookmarkCategories.map {
                    if (it.name.equals(categoryName, ignoreCase = true) && it.isSyncing)
                        it.copy(isSyncing = false)
                    else it
                })
            }
        }
    }

    fun renameBookmarkCategory(context: Context, oldName: String, newName: String) {
        // Rename the watch's own category first (visible immediately), remap the UI entry, then
        // forward to the phone when connected.
        val manager = BookmarkManager.INSTANCE
        val renamed = manager.getCategories().find { it.name.equals(oldName, ignoreCase = true) }
        renamed?.name = newName
        if (renamed != null) {
            app.organicmaps.sdk.sync.BookmarkSyncCore.migrateCategoryRename(context, manager, oldName, newName)
            WatchBookmarkSyncManager.migrateCategoryRenameState(context, oldName, newName)
        }
        NavigationStateHolder.update { current ->
            current.copy(bookmarkCategories = current.bookmarkCategories.map {
                if (it.name.equals(oldName, ignoreCase = true)) it.copy(name = newName) else it
            }.distinctBy { it.name })
        }
        if (NavigationStateHolder.state.value.isEffectivelyStandalone)
            WatchBookmarkSyncManager.requestSync(context)
        else
            getBackend(context).renameBookmarkCategory(context, oldName, newName)
    }

    fun deleteBookmarkCategory(context: Context, name: String) {
        val manager = BookmarkManager.INSTANCE
        manager.getCategories().find { it.name.equals(name, ignoreCase = true) }?.let { manager.deleteCategory(it.id) }
        NavigationStateHolder.update { current ->
            current.copy(bookmarkCategories = current.bookmarkCategories.filterNot { it.name.equals(name, ignoreCase = true) })
        }
        WatchBookmarkSyncManager.onLocalBookmarksChanged(context, true)
        if (NavigationStateHolder.state.value.isEffectivelyStandalone)
            WatchBookmarkSyncManager.requestSync(context)
        else
            getBackend(context).deleteBookmarkCategory(context, name)
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
