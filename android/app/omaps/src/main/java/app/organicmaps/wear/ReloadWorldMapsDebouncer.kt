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
                Framework.nativeReloadWorldMaps()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to reload world maps natively", e)
            }
        }
    }
    
    @Synchronized
    fun reloadImmediate() {
        job?.cancel()
        try {
            Log.d(TAG, "Executing immediate nativeReloadWorldMaps")
            Framework.nativeReloadWorldMaps()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to reload world maps natively", e)
        }
    }
}
