package app.organicmaps.wear

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.organicmaps.sdk.location.LocationProviderFactory
import app.organicmaps.sdk.OrganicMaps
import app.organicmaps.sdk.settings.StoragePathManager
import app.organicmaps.sdk.util.ConnectionState
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.Framework
import app.organicmaps.wear.BluetoothWearDataListenerService
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WearApplication : Application() {
    companion object {
        lateinit var instance: WearApplication
            private set
    }

    lateinit var organicMaps: OrganicMaps
        private set
        
    @Volatile
    var isFullyInitialized = false
        internal set
        
    @Volatile
    var initError: String? = null
        internal set

    @Volatile
    var isInitializing = false

    private lateinit var heartbeatManager: HeartbeatManager

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        val manager = SettingsSyncManager.getInstance(this)
        if (manager.isApplyingRemoteUpdates) return@OnSharedPreferenceChangeListener
        
        val value = prefs.all[key] ?: return@OnSharedPreferenceChangeListener
        manager.onSettingChanged(key, value, true)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("WearApp", "DEBUG_WEAR: WearApplication.onCreate - UI THREAD INIT FORCED")
        Log.d("WearApp", "DEBUG_GMS: WearApplication onCreate. Flavor: ${BuildConfig.FLAVOR}")
        
        PlatformHelperImpl.onApplicationCreate(this)

        if (isInitializing || isFullyInitialized) return
        isInitializing = true
        
        System.loadLibrary("organicmaps")
        VirtualMwmManager.setNativeLibraryLoaded()
        
        ConnectionState.INSTANCE.initialize(this)
        
        val nativeLocationFactory = LocationProviderFactoryImpl.create()
        
        organicMaps = OrganicMaps(
            applicationContext, 
            BuildConfig.FLAVOR, 
            BuildConfig.APPLICATION_ID, 
            251123, 
            BuildConfig.VERSION_NAME, 
            BuildConfig.APPLICATION_ID + ".fileprovider.wear",
            nativeLocationFactory
        )

        // Restore mounts only AFTER OrganicMaps (and thus native SetSettingsDir) is initialized.
        VirtualMwmManager.restoreMountsEarly(this)

        organicMaps.locationHelper.onEnteredIntoFirstRun()

        app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
        app.organicmaps.sdk.routing.RoutingController.get()
        app.organicmaps.sdk.search.SearchEngine.INSTANCE

        try {
            val asyncContinue = organicMaps.init {
                onCoreInitialized()
            }
            if (!asyncContinue) {
                onCoreInitialized()
            }
        } catch (e: Throwable) {
            initError = e.stackTraceToString()
            Log.e("WearApp", "Initialization failed", e)
        }

        NavigationStateHolder.loadFromPrefs(this)
        
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
        
        val isEmulator = android.os.Build.PRODUCT.contains("sdk") || android.os.Build.PRODUCT.contains("vbox")
        val currentBackend = prefs.getString("pref_wear_os_backend", null)
        val shouldResetToGms = isEmulator && currentBackend == "BLUETOOTH" && BuildConfig.FLAVOR != "oss" && !prefs.getBoolean("gms_migration_done", false)

        if (shouldResetToGms || currentBackend == null) {
            val defaultBackend = if (BuildConfig.FLAVOR == "oss") "BLUETOOTH" else "GMS"
            Log.d("WearApp", "Backend migration/default logic: resetting to $defaultBackend (Emulator=$isEmulator, Current=$currentBackend)")
            prefs.edit {
                putString("pref_wear_os_backend", defaultBackend)
                putBoolean("gms_migration_done", true)
            }
        }
        
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        
        heartbeatManager = HeartbeatManager(this)
        heartbeatManager.start()

        val selectedBackend = prefs.getString("pref_wear_os_backend", "GMS")
        if (selectedBackend == "BLUETOOTH") {
            Log.d("WearApp", "Starting BluetoothWearDataListenerService")
            startService(Intent(this, BluetoothWearDataListenerService::class.java))
        } else {
            Log.d("WearApp", "GMS backend selected, stopping Bluetooth service if running")
            stopService(Intent(this, BluetoothWearDataListenerService::class.java))
        }

        setupLifecycleAwareUpdates()
        WearNotificationManager.createNotificationChannel(this)

        // Initialize Bookmark Sync
        app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addSharingListener(WatchBookmarkSyncManager.sharingListener)
        app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addCategoriesUpdatesListener {
            WatchBookmarkSyncManager.onLocalBookmarksChanged(this)
        }
        // After every bookmark file merge, purge any bookmark the union-merge resurrected but that is
        // tombstoned locally (a merge never removes), so deletions stay deleted across syncs.
        app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addLoadingListener(
            object : app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksLoadingListener {
                override fun onBookmarksLoadingFinished() {
                    WatchBookmarkSyncManager.onBookmarksFileMergeFinished(this@WearApplication)
                }
            })
    }

    private fun setupLifecycleAwareUpdates() {
        val routingController = RoutingController.get()
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            while (true) {
                yield()
                
                if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    delay(5.seconds)
                    continue
                }

                val state = NavigationStateHolder.state.value
                val isAmbient = state.isAmbient
                
                if (state.isEffectivelyStandalone) {
                    if (!organicMaps.locationHelper.isActive) {
                        try { 
                            if (app.organicmaps.sdk.util.LocationUtils.checkFineLocationPermission(this@WearApplication)) {
                                withContext(Dispatchers.Main) { 
                                    Log.d("WearApp", "Auto-starting local GPS (Standalone/Disconnected)")
                                    organicMaps.locationHelper.start() 
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } else if (state.locationSource == "PHONE_ONLY" || state.isActive) {
                    if (organicMaps.locationHelper.isActive) {
                        withContext(Dispatchers.Main) { organicMaps.locationHelper.stop() }
                    }
                }

                if (state.watchLocalMode && (routingController.isNavigating || routingController.isBuilt || routingController.isBuilding)) {
                    val (info, routePoints) = withContext(Dispatchers.Main) {
                        val info = Framework.nativeGetRouteFollowingInfo()
                        val routeJunctions = Framework.nativeGetRouteJunctionPoints(if (isAmbient) 100.0 else 50.0)
                        val points = routeJunctions?.map { it.mLat to it.mLon } ?: emptyList()
                        info to points
                    }
                    
                    if (info != null) {
                        NavigationStateHolder.update { current ->
                            current.copy(
                                isActive = true,
                                isNavigating = routingController.isNavigating,
                                isRouteReady = routingController.isBuilt,
                                isRouteBuilt = routingController.isBuilt,
                                routePoints = routePoints,
                                distToTurn = info.distToTurn?.toString(this@WearApplication) ?: "",
                                nextStreet = info.nextStreet ?: "",
                                carDirection = info.carDirection.ordinal,
                                pedestrianDirection = info.pedestrianDirection.ordinal,
                                exitNum = info.exitNum,
                                distToTarget = info.distToTarget?.toString(this@WearApplication) ?: "",
                                eta = info.totalTimeInSeconds,
                                completionPercent = info.completionPercent,
                                turnLat = info.turnLat,
                                turnLon = info.turnLon,
                                isRecalculating = false
                            )
                        }
                    } else if (routingController.isNavigating) {
                        NavigationStateHolder.update { current ->
                            current.copy(
                                distToTurn = "Recalculating...",
                                nextStreet = "",
                                routePoints = emptyList(),
                                isRecalculating = true
                            )
                        }
                    }
                }
                
                val delayMs = when {
                    routingController.isNavigating -> if (isAmbient) 5.seconds else 1.seconds
                    isAmbient -> 30.seconds 
                    else -> 10.seconds 
                }
                delay(delayMs)
            }
        }
    }

    fun onActivityReceived() {
        heartbeatManager.onActivityReceived()
    }

    @Deprecated("Use onActivityReceived", ReplaceWith("onActivityReceived()"))
    fun onPongReceived() = onActivityReceived()

    private fun setupLocalNavigationListener() {
        val routingController = RoutingController.get()
        routingController.initialize(organicMaps.locationHelper)
        routingController.attach(object : RoutingController.Container {
            override fun showRoutePlan(show: Boolean, completion: Runnable?) {}
            override fun onPlanningStarted() {
                NavigationStateHolder.update { it.copy(isRouteBuilding = true, isRouteReady = false) }
            }
            override fun onPlanningCancelled() {
                Log.d("WearApp", "Routing: Planning cancelled")
                NavigationStateHolder.update { it.copy(
                    isRouteBuilding = false, 
                    isRouteReady = false, 
                    isRouteBuilt = false,
                    isActive = false
                ) }
            }
            override fun onBuiltRoute() {
                Log.d("WearApp", "Routing: Route built successfully")
                NavigationStateHolder.update { it.copy(isRouteBuilding = false, isRouteReady = true, isRouteBuilt = true, isMapUnlocked = false) }
            }
            override fun onNavigationStarted() {
                Log.d("WearApp", "Routing: Navigation started")
                NavigationStateHolder.update { it.copy(isNavigating = true, isRouteReady = false, isActive = true, isRouteBuilding = false, isMapUnlocked = false) }
                // Engage FOLLOW_AND_ROTATE so the map tracks the live location instead of sitting
                // frozen at the last viewport (#1). Runs on the routing callback (main) thread.
                try {
                    repeat(6) {
                        if (app.organicmaps.sdk.location.LocationState.getMode() ==
                            app.organicmaps.sdk.location.LocationState.FOLLOW_AND_ROTATE) return@repeat
                        app.organicmaps.sdk.location.LocationState.nativeSwitchToNextMode()
                    }
                } catch (_: Throwable) {}
            }
            override fun onNavigationCancelled() {
                Log.d("WearApp", "Routing: Navigation cancelled")
                val state = NavigationStateHolder.state.value
                NavigationStateHolder.update(state.copy(
                    isNavigating = false, 
                    isActive = false, 
                    isRouteBuilt = false, 
                    isRouteReady = false,
                    isRouteBuilding = false
                ), force = true)
            }
            override fun updateBuildProgress(progress: Int, router: app.organicmaps.sdk.Router) {
                NavigationStateHolder.update { it.copy(routeBuildProgress = progress) }
            }
            override fun onCommonBuildError(lastRouteError: Int, lastMissingMaps: Array<out String>) {
                Log.e("WearApp", "Routing: Common build error: $lastRouteError")
                NavigationStateHolder.update { it.copy(isRouteBuilding = false, lastRouteError = lastRouteError) }
            }
            override fun onDrivingOptionsBuildError() {
                Log.e("WearApp", "Routing: Driving options build error")
                NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
            }
            override fun onDrivingOptionsWarning() {
                Log.w("WearApp", "Routing: Driving options warning")
                NavigationStateHolder.update { it.copy(isRouteBuilding = false, isRouteReady = true, isRouteBuilt = true) }
            }
            override fun onStartRouteBuilding() {
                NavigationStateHolder.update { it.copy(isRouteBuilding = true) }
            }
        })

        app.organicmaps.sdk.location.LocationState.nativeSetListener(object : app.organicmaps.sdk.location.LocationState.ModeChangeListener {
            override fun onMyPositionModeChanged(newMode: Int) {
                val isUnlocked = newMode == app.organicmaps.sdk.location.LocationState.NOT_FOLLOW || 
                                newMode == app.organicmaps.sdk.location.LocationState.NOT_FOLLOW_NO_POSITION
                Log.d("WearApp", "Native location mode changed: $newMode (isUnlocked=$isUnlocked)")
                NavigationStateHolder.update { it.copy(isMapUnlocked = isUnlocked) }
            }
        })

        organicMaps.locationHelper.addListener(object : app.organicmaps.sdk.location.LocationListener {
            override fun onLocationUpdated(location: android.location.Location) {
                val state = NavigationStateHolder.state.value
                if (state.isEffectivelyStandalone) {
                    NavigationStateHolder.update(state.copy(
                        lat = location.latitude,
                        lon = location.longitude,
                        bearing = location.bearing,
                        lastFixTime = System.currentTimeMillis()
                    ))
                }
                
                getSharedPreferences("wear_prefs", Context.MODE_PRIVATE).edit {
                    putFloat("last_known_lat", location.latitude.toFloat())
                    putFloat("last_known_lon", location.longitude.toFloat())
                    putFloat("last_known_bearing", location.bearing)
                }
            }
            override fun onLocationResolutionRequired(pendingIntent: android.app.PendingIntent) {}
            override fun onLocationDisabled() {}
        })
        
        try {
            organicMaps.locationHelper.start()
        } catch (_: SecurityException) {
            Log.e("WearApplication", "Location permission missing at startup")
        }
    }
    
    suspend fun waitForInitializationSuspend() {
        var retries = 0
        while (!isFullyInitialized) {
            if (initError != null) throw RuntimeException(initError)
            if (retries > 300) throw RuntimeException("Timeout waiting for init (30s)")
            delay(100.milliseconds)
            retries++
        }
    }
    
    @Deprecated("Use waitForInitializationSuspend", ReplaceWith("waitForInitializationSuspend()"))
    fun waitForInitializationBlocking() {
        var retries = 0
        while (!isFullyInitialized) {
            if (initError != null) throw java.lang.RuntimeException(initError)
            if (retries > 300) throw java.lang.RuntimeException("Timeout waiting for init (30s)")
            Thread.sleep(100)
            retries++
        }
    }

    private fun onCoreInitialized() {
        CoroutineScope(Dispatchers.Main).launch {
            if (isFullyInitialized) return@launch
            isFullyInitialized = true
            copyCountriesFileToWritableStorage()

            WearCommandService.initBackend(this@WearApplication)
            WearCommandService.syncPreferences(this@WearApplication)

            val state = NavigationStateHolder.state.value
            if (state.allowMobileData) {
                app.organicmaps.sdk.downloader.MapManager.nativeEnableDownloadOn3g()
            }
            app.organicmaps.sdk.search.SearchEngine.INSTANCE.initialize()
            // The KMZ importer extracts the inner KML into files/bookmarks/; on a fresh watch that
            // directory doesn't exist yet, so unzip fails ("No such file or directory") and the
            // import silently aborts (no onBookmarksFileLoaded callback) — imported bookmarks never
            // land and the phone re-pulls forever (sync storm). Create it so extraction succeeds.
            // NOTE: do NOT call BookmarkManager.loadBookmarks() here — the framework already calls
            // Framework::LoadBookmarks during init, and a second call aborts natively
            // (bookmark_manager.cpp CHECK(!m_loadBookmarksCalled)).
            java.io.File(filesDir, "bookmarks").mkdirs()
            if (organicMaps.locationHelper.isInFirstRun) {
                organicMaps.locationHelper.onExitFromFirstRun()
            }
            ReloadWorldMapsDebouncer.reloadImmediate()

            Framework.nativeSet3dMode(state.is3dEnabled, state.is3dBuildingsEnabled)
            Framework.nativeSetAutoZoomEnabled(state.isAutoZoomEnabled)
            Framework.nativeSetTransitSchemeEnabled(state.transitEnabled)
            Framework.nativeSetCyclingLayerEnabled(state.bikingEnabled)
            Framework.nativeSetHikingLayerEnabled(state.hikingEnabled)
            Framework.nativeSetIsolinesLayerEnabled(state.isolinesEnabled)

            if (state.avoidTolls) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Toll)
            if (state.avoidMotorways) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Motorway)
            if (state.avoidFerries) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Ferry)
            if (state.avoidUnpaved) app.organicmaps.sdk.routing.RoutingOptions.addOption(app.organicmaps.sdk.settings.RoadType.Dirty)

            setupLocalNavigationListener()
        }
    }

    private suspend fun copyCountriesFileToWritableStorage() {
        try {
            val storagePath = StoragePathManager.findMapsStorage(this)
            val dataVersion = withContext(Dispatchers.Main) { Framework.nativeGetDataVersion() }
            val versionedPath = File(storagePath, dataVersion.toString())
            if (!versionedPath.exists()) {
                withContext(Dispatchers.IO) {
                    versionedPath.mkdirs()
                }
            }
            
            if (isFullyInitialized) {
                ReloadWorldMapsDebouncer.reloadImmediate()
            }
        } catch (e: Throwable) {
            Log.w("WearApplication", "Couldn't pre-copy resources to writable storage", e)
        }
    }

    private fun copyAssetsRecursively(path: String, targetDir: String) {
        val assetList = assets.list(path) ?: return
        if (assetList.isEmpty()) {
            copySingleAsset(path, targetDir)
        } else {
            val newTargetDir = if (path.isEmpty()) targetDir else File(targetDir, path).absolutePath
            val dirFile = File(newTargetDir)
            if (!dirFile.exists()) dirFile.mkdirs()
            
            for (asset in assetList) {
                val subPath = if (path.isEmpty()) asset else "$path/$asset"
                copyAssetsRecursively(subPath, targetDir)
            }
        }
    }

    private fun copySingleAsset(fileName: String, targetDir: String) {
        val targetFile = File(targetDir, fileName)
        if (targetFile.exists() && targetFile.length() > 0) return
        targetFile.parentFile?.mkdirs()
        
        try {
            assets.open(fileName).use { input ->
                FileOutputStream(targetFile, false).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
        }
    }
}
