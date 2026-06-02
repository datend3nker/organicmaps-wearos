package app.organicmaps.wear.message

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.presentation.Omaps
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class MapDownloadHandler : WearMessageHandler {
    companion object {
        private const val TAG = "MapDownloadHandler"
    }

    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val countryId = String(data, StandardCharsets.UTF_8)
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        
        val mapEnabled = prefs.getBoolean("mapEnabled", false)
        val mapDownloadMode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
        if (!mapEnabled || mapDownloadMode != "DIRECT_DOWNLOAD") {
            Log.d(TAG, "Ignoring map push/download request due to sync mode: $mapDownloadMode")
            return
        }

        prefs.edit().putBoolean("forceWatchLocalMode", true).apply()
        
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(openMapManager = true, watchLocalMode = true))
        
        Handler(Looper.getMainLooper()).post {
            try {
                val wearApp = context.applicationContext as WearApplication
                wearApp.waitForInitializationBlocking()
                
                val status = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus(countryId)
                if (status != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE && 
                    status != app.organicmaps.sdk.downloader.CountryItem.STATUS_PROGRESS && 
                    status != app.organicmaps.sdk.downloader.CountryItem.STATUS_ENQUEUED &&
                    status != app.organicmaps.sdk.downloader.CountryItem.STATUS_APPLYING) {
                    app.organicmaps.sdk.downloader.MapManager.startDownload(countryId)
                }
                
                val worldStatus = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World")
                if (worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE && 
                    worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_PROGRESS && 
                    worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_ENQUEUED &&
                    worldStatus != app.organicmaps.sdk.downloader.CountryItem.STATUS_APPLYING) {
                    app.organicmaps.sdk.downloader.MapManager.startDownload("World")
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        
        val intent = Intent(context, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}
