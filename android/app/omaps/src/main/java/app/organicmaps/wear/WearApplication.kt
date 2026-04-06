package app.organicmaps.wear

import android.app.Application
import android.content.Context
import app.organicmaps.sdk.location.BaseLocationProvider
import app.organicmaps.sdk.location.LocationProviderFactory
import app.organicmaps.sdk.OrganicMaps
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
            "google", 
            "app.organicmaps.debug", 
            1, 
            "1.0", 
            "app.organicmaps.debug.provider", 
            dummyLocationFactory
        )
        
        try {
            val asyncContinue = organicMaps.init { 
                isFullyInitialized = true 
            }
            if (!asyncContinue) {
                // If it refused to init (already initialized), set the flag manually
                isFullyInitialized = true
            }
        } catch (e: Throwable) {
            initError = e.stackTraceToString()
            e.printStackTrace()
        }
    }
    
    suspend fun waitForInitializationSuspend() {
        var retries = 0
        while (!isFullyInitialized) {
            if (initError != null) throw RuntimeException(initError)
            if (retries > 300) throw RuntimeException("Timeout waiting for init (30s)") // Increased timeout
            kotlinx.coroutines.delay(100)
            retries++
        }
    }
    
    fun waitForInitializationBlocking() {
        var retries = 0
        while (!isFullyInitialized) {
            if (initError != null) throw java.lang.RuntimeException(initError)
            if (retries > 300) throw java.lang.RuntimeException("Timeout waiting for init (30s)") // Increased timeout
            Thread.sleep(100)
            retries++
        }
    }
}
