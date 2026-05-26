package app.organicmaps.wear

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.organicmaps.sdk.location.BaseLocationProvider
import app.organicmaps.sdk.location.LocationProviderFactory
import app.organicmaps.sdk.OrganicMaps
import app.organicmaps.sdk.settings.StoragePathManager
import app.organicmaps.sdk.util.ConnectionState
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.Framework
import app.organicmaps.wear.BluetoothWearDataListenerService
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlin.math.hypot

class WearApplication : Application() {
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

    override fun onCreate() {
        super.onCreate()
        if (isInitializing || isFullyInitialized) return
        isInitializing = true
        
        System.loadLibrary("organicmaps")
        ConnectionState.INSTANCE.initialize(this)
        
        val nativeLocationFactory = object : LocationProviderFactory {
            override fun isGoogleLocationAvailable(context: Context): Boolean {
                return false
            }
            override fun getProvider(context: Context, listener: BaseLocationProvider.Listener): BaseLocationProvider {
                // Use GMS Fused location if available on the watch
                if (isGoogleLocationAvailable(context)) {
                    try {
                        val cls = Class.forName("app.organicmaps.location.GoogleFusedLocationProvider")
                        val ctor = cls.getDeclaredConstructor(Context::class.java, BaseLocationProvider.Listener::class.java)
                        ctor.isAccessible = true
                        return ctor.newInstance(context, listener) as BaseLocationProvider
                    } catch (_: Exception) {}
                }
                return app.organicmaps.sdk.location.AndroidNativeProvider(context, listener)
            }
        }
        
        organicMaps = OrganicMaps(
            applicationContext, 
            BuildConfig.FLAVOR, 
            BuildConfig.APPLICATION_ID, 
            251123, // Matches countries.txt version
            BuildConfig.VERSION_NAME, 
            BuildConfig.APPLICATION_ID + ".provider",
            nativeLocationFactory
        )

        copyCountriesFileToWritableStorage()
        
        organicMaps.locationHelper.onEnteredIntoFirstRun()

        try {
            val asyncContinue = organicMaps.init { 
                isFullyInitialized = true 
                if (NavigationStateHolder.state.value.allowMobileData) {
                    app.organicmaps.sdk.downloader.MapManager.nativeEnableDownloadOn3g()
                }
                app.organicmaps.sdk.search.SearchEngine.INSTANCE.initialize()
                organicMaps.locationHelper.onExitFromFirstRun()
                setupLocalNavigationListener()
            }
            if (!asyncContinue) {
                isFullyInitialized = true
                if (NavigationStateHolder.state.value.allowMobileData) {
                    app.organicmaps.sdk.downloader.MapManager.nativeEnableDownloadOn3g()
                }
                app.organicmaps.sdk.search.SearchEngine.INSTANCE.initialize()
                organicMaps.locationHelper.onExitFromFirstRun()
                setupLocalNavigationListener()
            }
        } catch (e: Throwable) {
            initError = e.stackTraceToString()
            e.printStackTrace()
        }

        // Start appropriate communication backend
        WearCommandService.initBackend(this)
        
        NavigationStateHolder.loadFromPrefs(this)
        
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
        val selectedBackend = prefs.getString("pref_wear_os_backend", "GMS")
        if (BuildConfig.FLAVOR == "oss" || selectedBackend == "BLUETOOTH") {
            startService(Intent(this, BluetoothWearDataListenerService::class.java))
        }

        startPingLoop()
        setupLifecycleAwareUpdates()
    }

    private fun setupLifecycleAwareUpdates() {
        val routingController = RoutingController.get()
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            while (true) {
                yield()
                
                // POWER SAVING: Check lifecycle state
                if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    delay(5000)
                    continue
                }

                val state = NavigationStateHolder.state.value
                val isAmbient = state.isAmbient
                
                // POWER SAVING: Hardware management based on visibility and navigation state
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
                    // Disable local GPS when phone is providing location or we are actively navigating (companion mode)
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
                
                // Adaptive delay to save battery
                val delayMs = when {
                    routingController.isNavigating -> 1000L
                    isAmbient -> 10000L
                    else -> 5000L
                }
                delay(delayMs)
            }
        }
    }

    private var lastPongTime = System.currentTimeMillis()
    private fun startPingLoop() {
        MainScope().launch {
            kotlinx.coroutines.delay(3000)
            while (true) {
                try {
                    val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                    val disconnected = prefs.getBoolean("disconnectFromPhone", false)
                    
                    if (!disconnected) {
                        WearCommandService.sendPing(this@WearApplication)
                        // No automatic connection update here - wait for actual Pong/Message
                        WearCommandService.checkConnection(this@WearApplication) { /* node exists, but app might not be running */ }
                    } else {
                         val currentState = NavigationStateHolder.state.value
                         if (currentState.isPhoneConnected) {
                             NavigationStateHolder.update(currentState.copy(isPhoneConnected = false))
                         }
                    }
                } catch (e: Exception) {
                    Log.e("WearApp", "Failed to send ping", e)
                }
                kotlinx.coroutines.delay(5000) // Fast ping
                
                val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                val disconnected = prefs.getBoolean("disconnectFromPhone", false)

                if (disconnected || System.currentTimeMillis() - lastPongTime > 15000) { // Fast timeout
                    val currentState = NavigationStateHolder.state.value
                    if (currentState.isPhoneConnected) {
                        Log.d("WearApp", "Phone connection timeout - marking as disconnected")
                        var newState = currentState.copy(isPhoneConnected = false)
                        
                        // If we were navigating in companion mode, stop it
                        if (newState.isActive && !newState.watchLocalMode && !newState.standaloneMode) {
                            Log.d("WearApp", "Companion connection lost while navigating - resetting state")
                            newState = newState.copy(
                                isActive = false,
                                isNavigating = false,
                                isRouteBuilt = false,
                                isRouteBuilding = false
                            )
                        }
                        NavigationStateHolder.update(newState)
                    }
                }
            }
        }
    }
    
    fun onPongReceived() {
        lastPongTime = System.currentTimeMillis()
        val currentState = NavigationStateHolder.state.value
        if (!currentState.isPhoneConnected) {
            NavigationStateHolder.update(currentState.copy(isPhoneConnected = true))
        }
    }

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
                NavigationStateHolder.update { it.copy(isRouteBuilding = false, isRouteReady = false, isRouteBuilt = false) }
            }
            override fun onBuiltRoute() {
                Log.d("WearApp", "Routing: Route built successfully")
                NavigationStateHolder.update { it.copy(isRouteBuilding = false, isRouteReady = true, isRouteBuilt = true, isMapUnlocked = false) }
            }
            override fun onNavigationStarted() {
                Log.d("WearApp", "Routing: Navigation started")
                NavigationStateHolder.update { it.copy(isNavigating = true, isRouteReady = false, isActive = true, isRouteBuilding = false, isMapUnlocked = false) }
            }
            override fun onNavigationCancelled() {
                Log.d("WearApp", "Routing: Navigation cancelled")
                val state = NavigationStateHolder.state.value
                NavigationStateHolder.update(state.copy(
                    isNavigating = false, 
                    isActive = false, 
                    isRouteBuilt = false, 
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
                
                getSharedPreferences("wear_prefs", Context.MODE_PRIVATE).edit()
                    .putFloat("last_known_lat", location.latitude.toFloat())
                    .putFloat("last_known_lon", location.longitude.toFloat())
                    .putFloat("last_known_bearing", location.bearing)
                    .apply()
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
            kotlinx.coroutines.delay(100)
            retries++
        }
    }
    
    fun waitForInitializationBlocking() {
        var retries = 0
        while (!isFullyInitialized) {
            if (initError != null) throw java.lang.RuntimeException(initError)
            if (retries > 300) throw java.lang.RuntimeException("Timeout waiting for init (30s)")
            Thread.sleep(100)
            retries++
        }
    }

    private fun copyCountriesFileToWritableStorage() {
        try {
            val storagePath = StoragePathManager.findMapsStorage(this)
            val versionedPath = File(storagePath, "251123")
            if (!versionedPath.exists()) versionedPath.mkdirs()

            val filesToCopyRoot = listOf(
                "countries.txt", "classificator.txt", "types.txt", "categories.txt", "packed_polygons.bin",
                "drules_proto.bin", "drules_proto_default_dark.bin", "drules_proto_default_light.bin",
                "drules_proto_vehicle_dark.bin", "drules_proto_vehicle_light.bin",
                "drules_proto_outdoors_dark.bin", "drules_proto_outdoors_light.bin"
            )
            val filesToCopyVersioned = listOf(
                "World.mwm", "WorldCoasts.mwm"
            )
            
            val assetList = assets.list("")?.toSet() ?: emptySet()
            Log.d("WearApplication", "Assets present: $assetList")

            for (fileName in filesToCopyRoot) {
                copySingleAsset(fileName, storagePath, assetList)
            }
            for (fileName in filesToCopyVersioned) {
                copySingleAsset(fileName, versionedPath.absolutePath, assetList)
            }
            
            // Critical for routing: native core needs these registered
            if (isFullyInitialized) {
                Framework.nativeReloadWorldMaps()
            }
        } catch (e: Throwable) {
            Log.w("WearApplication", "Couldn't pre-copy resources to writable storage", e)
        }
    }

    private fun copySingleAsset(fileName: String, targetDir: String, assetList: Set<String>) {
        if (!assetList.contains(fileName)) {
            Log.w("WearApplication", "Asset $fileName missing from APK!")
            return
        }

        val targetFile = File(targetDir, fileName)
        if (targetFile.exists() && targetFile.length() > 0) return
        
        try {
            Log.d("WearApplication", "Copying $fileName to $targetDir...")
            assets.open(fileName).use { input ->
                FileOutputStream(targetFile, false).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("WearApplication", "Successfully copied $fileName")
        } catch (e: Exception) {
            Log.w("WearApplication", "Failed to copy $fileName: ${e.message}")
            if (targetFile.exists() && targetFile.length() == 0L) targetFile.delete()
        }
    }
}
