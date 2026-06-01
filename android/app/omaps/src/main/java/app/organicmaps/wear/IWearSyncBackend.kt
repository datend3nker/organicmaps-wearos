package app.organicmaps.wear

import android.content.Context

interface IWearSyncBackend {
    fun stopNavigation(context: Context)
    fun search(context: Context, query: String, lat: Double, lon: Double)
    fun requestSearchHistory(context: Context)
    fun requestMwmMetadata(context: Context, mwmName: String)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int)
    fun sendPing(context: Context)
    fun sendPong(context: Context, nodeId: String)
    fun syncPreferences(context: Context)
    fun requestPreferences(context: Context)
    fun syncSearchHistory(context: Context)
    fun checkConnection(context: Context, callback: (Boolean) -> Unit)
    fun startNavigation(context: Context)
    fun showOnPhone(context: Context, result: SearchResultItem)
    fun cancelMapSync(context: Context, mapId: String)
    fun sendBackendSwitch(context: Context, newBackend: String)
    fun sendMapDownloadRequest(context: Context, mapId: String)
    fun sendMapProgress(context: Context, mapId: String, progress: Int)
    fun toggleTrackRecording(context: Context)
    fun requestBookmarks(context: Context)
    fun toggleBookmarkCategory(context: Context, categoryId: Long)
    fun syncCategory(context: Context, categoryId: Long)
    fun showBookmarkOnPhone(context: Context, bmkId: Long)
    fun updateBookmarkOnPhone(context: Context, bmkId: Long, name: String, color: Int)
    fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int)
    fun launchPhoneApp(context: Context)
    fun stop()
}
