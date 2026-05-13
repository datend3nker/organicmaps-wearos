package app.organicmaps.wear

import android.content.Context

object WearCommandService {
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
        val selectedBackend = prefs.getString("pref_wear_os_backend", "GMS")
        
        // Properly dispose of previous backend if it exists
        backend = null 
        
        backend = if (selectedBackend == "BLUETOOTH" || BuildConfig.FLAVOR == "oss") {
            BluetoothWearSyncBackend()
        } else {
            try {
                Class.forName("app.organicmaps.wear.GmsWearSyncBackend")
                    .getDeclaredConstructor().newInstance() as IWearSyncBackend
            } catch (e: Exception) {
                BluetoothWearSyncBackend()
            }
        }
        
        // Immediate connection attempt and pref sync
        syncPreferences(context)
    }

    fun stopNavigation(context: Context) = getBackend(context).stopNavigation(context)
    fun search(context: Context, query: String) = getBackend(context).search(context, query)
    fun requestSearchHistory(context: Context) = getBackend(context).requestSearchHistory(context)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int) = 
        getBackend(context).selectSearchResult(context, result, routerType)
    fun requestMapTile(context: Context, requestId: Long, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, routerType: Int, poiCategoriesMask: Int) =
        getBackend(context).requestMapTile(context, requestId, minLat, minLon, maxLat, maxLon, routerType, poiCategoriesMask)
    fun sendPing(context: Context) = getBackend(context).sendPing(context)
    fun syncPreferences(context: Context) = getBackend(context).syncPreferences(context)
    fun requestPreferences(context: Context) = getBackend(context).requestPreferences(context)
    fun checkConnection(context: Context, callback: (Boolean) -> Unit) = getBackend(context).checkConnection(context, callback)
    fun startNavigation(context: Context) = getBackend(context).startNavigation(context)
}
