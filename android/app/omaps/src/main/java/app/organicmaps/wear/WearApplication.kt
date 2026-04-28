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

        // F-Droid: Start Bluetooth Listener Service
        if (BuildConfig.FLAVOR == "fdroid" || BuildConfig.FLAVOR == "oss") {
            startService(Intent(this, WearDataListenerService::class.java))
        }
    }

    private fun setupLocalNavigationListener() {
        val routingController = RoutingController.get()
        routingController.initialize(organicMaps.locationHelper)
        
        MainScope().launch(Dispatchers.Main) {
            while (true) {
                if (NavigationStateHolder.state.value.offlineMapsEnabled && routingController.isNavigating) {
                    val info = Framework.nativeGetRouteFollowingInfo()
                    if (info != null) {
                        val currentState = NavigationStateHolder.state.value
                        NavigationStateHolder.update(currentState.copy(
                            distToTurn = info.distToTurn?.toString(this@WearApplication) ?: "",
                            nextStreet = info.nextStreet ?: "",
                            carDirection = info.carDirection.ordinal,
                            pedestrianDirection = info.pedestrianDirection.ordinal,
                            isActive = true,
                            distToTarget = info.distToTarget?.toString(this@WearApplication) ?: "",
                            eta = info.totalTimeInSeconds,
                            completionPercent = info.completionPercent
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
