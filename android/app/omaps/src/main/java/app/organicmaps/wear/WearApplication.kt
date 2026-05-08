package app.organicmaps.wear

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class WearApplication : Application() {
    lateinit var organicMaps: OrganicMaps
        private set
        
    @Volatile
    var isFullyInitialized = false
        internal set
        
    @Volatile
    var initError: String? = null
        internal set

    override fun onCreate() {
        super.onCreate()
        
        System.loadLibrary("organicmaps")
        ConnectionState.INSTANCE.initialize(this)
        
        val dummyLocationFactory = object : LocationProviderFactory {
            override fun isGoogleLocationAvailable(context: Context): Boolean = false
            override fun getProvider(context: Context, listener: BaseLocationProvider.Listener): BaseLocationProvider {
                return object : BaseLocationProvider(listener) {
                    override fun start(interval: Long) {}
                    override fun stop() {}
                }
            }
        }
        
        organicMaps = OrganicMaps(
            applicationContext, 
            BuildConfig.FLAVOR, 
            BuildConfig.APPLICATION_ID, 
            1, 
            BuildConfig.VERSION_NAME, 
            BuildConfig.APPLICATION_ID + ".provider",
            dummyLocationFactory
        )

        copyCountriesFileToWritableStorage()
        
        try {
            val asyncContinue = organicMaps.init { 
                isFullyInitialized = true 
                setupLocalNavigationListener()
            }
            if (!asyncContinue) {
                isFullyInitialized = true
                setupLocalNavigationListener()
            }
        } catch (e: Throwable) {
            initError = e.stackTraceToString()
            e.printStackTrace()
        }

        // Start appropriate communication backend
        WearCommandService.initBackend(this)
        
        val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
        val selectedBackend = prefs.getString("pref_wear_os_backend", "GMS")
        if (BuildConfig.FLAVOR == "oss" || selectedBackend == "BLUETOOTH") {
            startService(Intent(this, BluetoothWearDataListenerService::class.java))
        }

        startPingLoop()
    }

    private var lastPongTime = System.currentTimeMillis()
    private fun startPingLoop() {
        MainScope().launch {
            // Initial delay to allow connection setup
            kotlinx.coroutines.delay(3000)
            while (true) {
                try {
                    val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                    val disconnected = prefs.getBoolean("disconnectFromPhone", false)
                    
                    if (!disconnected) {
                        WearCommandService.sendPing(this@WearApplication)
                        // Trigger connection check via service
                        WearCommandService.checkConnection(this@WearApplication) { connected ->
                            if (connected) onPongReceived()
                        }
                    } else {
                         val currentState = NavigationStateHolder.state.value
                         if (currentState.isPhoneConnected) {
                             NavigationStateHolder.update(currentState.copy(isPhoneConnected = false))
                         }
                    }
                } catch (e: Exception) {
                    Log.e("WearApp", "Failed to send ping", e)
                }
                kotlinx.coroutines.delay(10000)
                
                val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                val disconnected = prefs.getBoolean("disconnectFromPhone", false)

                // If no pong for 35 seconds, mark as disconnected
                if (disconnected || System.currentTimeMillis() - lastPongTime > 35000) {
                    val currentState = NavigationStateHolder.state.value
                    if (currentState.isPhoneConnected) {
                        NavigationStateHolder.update(currentState.copy(isPhoneConnected = false))
                    }
                }
            }
        }
    }
    
    // Called from listeners
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
            override fun onPlanningStarted() {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    isRouteBuilding = true,
                    isRouteReady = false,
                    routeBuildProgress = 0,
                    routePoints = emptyList(),
                    distToTurn = "",
                    nextStreet = "",
                    distToTarget = "",
                    eta = 0,
                    completionPercent = 0.0,
                    turnLat = 0.0,
                    turnLon = 0.0
                ))
            }

            override fun onPlanningCancelled() {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    isRouteBuilding = false,
                    isRouteReady = false,
                    routeBuildProgress = 0,
                    routePoints = emptyList()
                ))
            }

            override fun onBuiltRoute() {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    isRouteBuilding = false,
                    isRouteReady = true,
                    routeBuildProgress = 100
                ))
            }

            override fun onNavigationStarted() {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    isNavigating = true,
                    isRouteBuilding = false,
                    isRouteReady = false,
                    routeBuildProgress = 100
                ))
            }

            override fun onNavigationCancelled() {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    isNavigating = false,
                    isRouteReady = false,
                    routePoints = emptyList()
                ))
            }

            override fun updateBuildProgress(progress: Int, router: app.organicmaps.sdk.Router) {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    routeBuildProgress = progress.coerceIn(0, 100),
                    isRouteBuilding = progress in 0 until 100,
                    isRouteReady = progress >= 100
                ))
            }

            override fun onStartRouteBuilding() {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    isRouteBuilding = true,
                    isRouteReady = false,
                    routeBuildProgress = 0
                ))
            }
        })
        
        organicMaps.locationHelper.addListener(object : app.organicmaps.sdk.location.LocationListener {
            override fun onLocationUpdated(location: android.location.Location) {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    lat = location.latitude,
                    lon = location.longitude,
                    speedMps = location.speed.toDouble(),
                    bearing = location.bearing
                ))
            }
            override fun onLocationResolutionRequired(pendingIntent: android.app.PendingIntent) {}
            override fun onLocationDisabled() {}
        })
        
        try {
            organicMaps.locationHelper.start()
        } catch (_: SecurityException) {
            Log.e("WearApplication", "Location permission missing at startup")
        }

        MainScope().launch(Dispatchers.Main) {
            while (true) {
                if (NavigationStateHolder.state.value.watchLocalMode && (routingController.isNavigating || routingController.isBuilt())) {
                    val info = Framework.nativeGetRouteFollowingInfo()
                    val routeJunctions = Framework.nativeGetRouteJunctionPoints(50.0)
                    val routePoints = routeJunctions?.map { it.mLat to it.mLon } ?: emptyList()
                    if (info != null) {
                        val currentState = NavigationStateHolder.state.value
                        NavigationStateHolder.update(currentState.copy(
                            isActive = true,
                            isNavigating = routingController.isNavigating,
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
                            turnLon = info.turnLon
                        ))
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
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
            val targetFile = File(storagePath, "countries.txt")
            assets.open("countries.txt").use { input ->
                FileOutputStream(targetFile, false).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Throwable) {
            Log.w("WearApplication", "Couldn't pre-copy countries.txt to writable storage", e)
        }
    }
}
