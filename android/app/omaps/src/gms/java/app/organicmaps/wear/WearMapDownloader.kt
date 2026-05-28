package app.organicmaps.wear

import android.content.Context
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

    fun cancel(context: Context) {
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        if (_downloadState.value == DownloadState.STREAMING_FROM_PHONE) {
            WearCommandService.cancelMapSync(context)
        }
        _downloadState.value = DownloadState.CANCELLED
        WearNotificationManager.hideSyncNotification(context)
    }

    suspend fun downloadOrStreamMap(context: Context, mapId: String, downloadUrl: String = "") {
        currentDownloadJob?.cancel()
        currentDownloadJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]

        _currentMap.value = mapId
        
        val finalUrl = if (downloadUrl.isEmpty()) {
            "https://direct.organicmaps.app/251123/$mapId.mwm"
        } else {
            downloadUrl
        }

        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("mapDownloadMode", "PHONE_SYNC") ?: "PHONE_SYNC"
        
        Log.d(TAG, "Configured mapDownloadMode is $mode")
        
        val hasInternet = hasInternetAccess(context)

        when (mode) {
            "INTERNET" -> {
                if (hasInternet) {
                    _downloadState.value = DownloadState.DOWNLOADING
                    downloadOverInternet(context, mapId, finalUrl)
                } else {
                    Log.e(TAG, "INTERNET mode set but no internet access. Falling back to phone sync.")
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
            val file = File(context.filesDir, "$mapId.mwm")
            connection.getInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    val data = ByteArray(4096)
                    var total: Long = 0
                    while (true) {
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed downloading via internet. Falling back to phone sync...", e)
            _downloadState.value = DownloadState.STREAMING_FROM_PHONE
            streamFromPhone(context, mapId)
        }
    }

    private suspend fun streamFromPhone(context: Context, mapId: String) = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.STREAMING_FROM_PHONE
        _downloadProgress.value = 0.0f
        try {
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = Tasks.await(nodeClient.connectedNodes)
            val phoneNode = nodes.firstOrNull() ?: run {
                _downloadState.value = DownloadState.FAILED
                return@withContext
            }
            
            val messageClient = Wearable.getMessageClient(context)
            Tasks.await(messageClient.sendMessage(phoneNode.id, "/map/stream/request", mapId.toByteArray()))
            Log.d(TAG, "Requested phone to stream $mapId over Bluetooth channel")
            // Completion will be handled when the channel stream finishes in DataListenerService
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request map streaming from phone", e)
            _downloadState.value = DownloadState.FAILED
        }
    }
}
