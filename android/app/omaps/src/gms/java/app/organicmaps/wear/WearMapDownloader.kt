package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WearMapDownloader {
    private const val TAG = "WearMapDownloader"

    enum class DownloadState { IDLE, DOWNLOADING, STREAMING_FROM_PHONE, COMPLETED, FAILED, CANCELLED }

    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _currentMap = MutableStateFlow<String?>(null)
    val currentMap: StateFlow<String?> = _currentMap.asStateFlow()

    private var currentDownloadJob: kotlinx.coroutines.Job? = null

    fun setStreamingProgress(progress: Float) {
        _downloadProgress.value = progress
        if (_downloadState.value != DownloadState.DOWNLOADING) {
            _downloadState.value = DownloadState.STREAMING_FROM_PHONE
        }
        _currentMap.value?.let { 
            WearNotificationManager.updateSyncNotification(WearApplication.instance, it, progress, true)
        }
    }

    fun setStreamingMap(mapId: String) {
        _currentMap.value = mapId
        _downloadState.value = DownloadState.STREAMING_FROM_PHONE
        _downloadProgress.value = 0f
        WearNotificationManager.updateSyncNotification(WearApplication.instance, mapId, 0f, true)
    }

    fun onDownloadCompleted() {
        _downloadState.value = DownloadState.COMPLETED
        _downloadProgress.value = 1.0f
        currentDownloadJob = null
        WearNotificationManager.hideSyncNotification(WearApplication.instance)
    }

    fun onDownloadCancelled() {
        _downloadState.value = DownloadState.CANCELLED
        _downloadProgress.value = 0f
        currentDownloadJob = null
        WearNotificationManager.hideSyncNotification(WearApplication.instance)
    }

    fun cancel(context: Context) {
        val mapId = _currentMap.value
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        if (_downloadState.value == DownloadState.STREAMING_FROM_PHONE) {
            if (mapId != null) {
                WearCommandService.cancelMapSync(context, mapId)
                // Notify listener service to cleanup (Bluetooth mode uses this)
                try {
                    val intent = Intent(context, BluetoothWearDataListenerService::class.java).apply {
                        action = "app.organicmaps.wear.CANCEL_SYNC"
                        putExtra("mapId", mapId)
                    }
                    context.startService(intent)
                } catch (e: Exception) {
                    Log.d(TAG, "Bluetooth listener not notified of cancel (GMS mode?)")
                }
            }
        }
        _downloadState.value = DownloadState.CANCELLED
        WearNotificationManager.hideSyncNotification(context)
    }

    fun onMapMissingOnPhone(context: Context, mapId: String) {
        if (_currentMap.value == mapId && _downloadState.value == DownloadState.STREAMING_FROM_PHONE) {
            _downloadState.value = DownloadState.FAILED
            NavigationStateHolder.update { it.copy(missingMapId = mapId) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Map '$mapId' not found on phone.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun downloadOrStreamMap(context: Context, mapId: String, downloadUrl: String = "", forceInternet: Boolean = false) {
        (context.applicationContext as WearApplication).waitForInitializationSuspend()
        currentDownloadJob?.cancel()
        currentDownloadJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]

        _currentMap.value = mapId
        NavigationStateHolder.update { it.copy(missingMapId = null) }
        
        val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
        val finalUrl = if (downloadUrl.isEmpty()) {
            "https://direct.organicmaps.app/$dataVersion/$mapId.mwm"
        } else {
            downloadUrl
        }

        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mode = if (forceInternet) "INTERNET" else (prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC")
        
        Log.d(TAG, "Configured mapDownloadMode is $mode (forceInternet=$forceInternet)")
        
        val hasInternet = hasInternetAccess(context)

        when (mode) {
            "INTERNET", "DIRECT_DOWNLOAD" -> {
                if (hasInternet) {
                    _downloadState.value = DownloadState.DOWNLOADING
                    downloadOverInternet(context, mapId, finalUrl)
                } else {
                    Log.e(TAG, "DIRECT_DOWNLOAD mode set but no internet access. Falling back to phone sync.")
                    _downloadState.value = DownloadState.STREAMING_FROM_PHONE
                    streamFromPhone(context, mapId)
                }
            }
            else -> { // Default is PHONE_SYNC
                _downloadState.value = DownloadState.STREAMING_FROM_PHONE
                streamFromPhone(context, mapId)
            }
        }
    }

    private fun hasInternetAccess(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun downloadOverInternet(context: Context, mapId: String, downloadUrl: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val connection = url.openConnection()
            connection.connect()

            val fileLength = connection.contentLength
            val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(context)
            val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
            val versionedPath = File(storagePath, dataVersion.toString())
            if (!versionedPath.exists()) versionedPath.mkdirs()
            
            val file = File(versionedPath, "$mapId.mwm")
            connection.getInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    val data = ByteArray(4096)
                    var total: Long = 0
                    while (true) {
                        kotlinx.coroutines.yield()
                        val count = input.read(data)
                        if (count == -1) break
                        total += count.toLong()
                        if (fileLength > 0) {
                            val progress = total.toFloat() / fileLength.toFloat()
                            _downloadProgress.value = progress
                            _currentMap.value?.let { 
                                WearNotificationManager.updateSyncNotification(context, it, progress, false)
                            }
                        }
                        output.write(data, 0, count)
                    }
                }
            }
            Log.d(TAG, "Successfully downloaded $mapId over internet")
            _downloadState.value = DownloadState.COMPLETED
            _downloadProgress.value = 1.0f
            currentDownloadJob = null
            WearNotificationManager.hideSyncNotification(context)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || e.cause is kotlinx.coroutines.CancellationException) {
                _downloadState.value = DownloadState.CANCELLED
                throw e
            }
            Log.e(TAG, "Failed downloading via internet. Falling back to phone sync...", e)
            _downloadState.value = DownloadState.STREAMING_FROM_PHONE
            streamFromPhone(context, mapId)
        }
    }

    private suspend fun streamFromPhone(context: Context, mapId: String) = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.STREAMING_FROM_PHONE
        _downloadProgress.value = 0.0f
        try {
            WearCommandService.sendMapDownloadRequest(context, mapId)
            Log.d(TAG, "Requested phone to stream $mapId over Bluetooth channel")
            // Completion will be handled when the channel stream finishes in DataListenerService
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request map streaming from phone", e)
            _downloadState.value = DownloadState.FAILED
        }
    }
}
