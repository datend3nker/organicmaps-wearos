package app.organicmaps.wear

import android.content.Context

import app.organicmaps.sdk.sync.BaseSettingsSyncManager

interface IWearSyncBackend {

    fun stopNavigation(context: Context)
    fun search(context: Context, query: String, lat: Double, lon: Double)
    fun requestSearchHistory(context: Context)
    fun requestMwmMetadata(context: Context, mwmName: String)
    fun selectSearchResult(context: Context, result: SearchResultItem, routerType: Int)
    fun sendPing(context: Context)
    fun sendPong(context: Context, nodeId: String)
    fun syncPreferences(context: Context)
    fun syncPreferenceUpdates(context: Context, updates: List<BaseSettingsSyncManager.SettingUpdate>)
    fun requestPreferences(context: Context)
    fun syncSearchHistory(context: Context)
    fun checkConnection(context: Context, callback: (Boolean, String?) -> Unit)
    fun startNavigation(context: Context)
    fun showOnPhone(context: Context, result: SearchResultItem)
    fun cancelMapSync(context: Context, mapId: String)
    fun requestDownloadedMaps(context: Context)
    fun sendBackendSwitch(context: Context, newBackend: String)
    fun sendMapDownloadRequest(context: Context, mapId: String, offset: Long = 0, checksum: Long = 0)
    fun sendMapProgress(context: Context, mapId: String, progress: Int)
    fun toggleTrackRecording(context: Context)
    fun requestBookmarks(context: Context)
    fun toggleBookmarkCategory(context: Context, categoryName: String)
    fun syncCategory(context: Context, categoryName: String)
    fun renameBookmarkCategory(context: Context, oldName: String, newName: String)
    fun deleteBookmarkCategory(context: Context, name: String)
    fun createBookmarkCategory(context: Context, name: String)
    fun showBookmarkOnPhone(context: Context, bmkId: Long)
    fun updateBookmarkOnPhone(context: Context, bmkId: Long, name: String, color: Int, categoryId: Long = -1)
    fun sendBookmarkFile(context: Context, categoryName: String, data: ByteArray, isLast: Boolean, merge: Boolean = false)
    fun sendBookmarksMetadata(context: Context, payload: ByteArray)
    fun sendBookmarkTombstone(context: Context, payload: ByteArray)
    fun requestMwmBytes(context: Context, mwmName: String, offset: Long, size: Int)
    fun launchPhoneApp(context: Context)
    fun sendHandshake(context: Context)
    fun stop()
}
