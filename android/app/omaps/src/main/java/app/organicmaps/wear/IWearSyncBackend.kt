package app.organicmaps.wear

import android.content.Context

interface IWearSyncBackend {
    fun stopNavigation(context: Context)
    fun search(context: Context, query: String)
    fun requestSearchHistory(context: Context)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int)
    fun requestMapTile(context: Context, requestId: Long, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, routerType: Int, poiCategoriesMask: Int)
    fun sendPing(context: Context)
    fun syncPreferences(context: Context)
    fun requestPreferences(context: Context)
    fun checkConnection(context: Context, callback: (Boolean) -> Unit)
    fun startNavigation(context: Context)
}
