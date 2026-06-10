package app.organicmaps.wear

import android.util.Log
import app.organicmaps.sdk.Framework
import kotlinx.coroutines.*

object ReloadWorldMapsDebouncer {
    private const val TAG = "ReloadWorldMapsDebouncer"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    @Synchronized
    fun reload() {
        job?.cancel()
        job = scope.launch {
            delay(1000) // 1 second debounce
            try {
                Log.d(TAG, "Executing debounced nativeReloadWorldMaps")
                withContext(Dispatchers.Main) {
                    app.organicmaps.sdk.Map.pauseSurfaceRendering()
                    Framework.nativeReloadWorldMaps()
                    app.organicmaps.sdk.Map.resumeSurfaceRendering()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to reload world maps natively", e)
            }
        }
    }
    
    @Synchronized
    fun reloadImmediate() {
        job?.cancel()
        // Never block the main thread for map reloading
        // BUT we must call it on Main thread because Organic Maps framework is NOT thread-safe
        // Debounce immediate reloads too to prevent multiple overlapping heavy reloads
        job = scope.launch {
            try {
                Log.d(TAG, "Executing immediate nativeReloadWorldMaps (main thread)")
                withContext(Dispatchers.Main) {
                    app.organicmaps.sdk.Map.pauseSurfaceRendering()
                    Framework.nativeReloadWorldMaps()
                    app.organicmaps.sdk.Map.resumeSurfaceRendering()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to reload world maps natively", e)
            } finally {
                if (coroutineContext[Job] == job) job = null
            }
        }
    }
}
