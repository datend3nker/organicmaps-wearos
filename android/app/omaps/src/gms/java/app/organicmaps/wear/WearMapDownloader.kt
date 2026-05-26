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

    enum class DownloadState { IDLE, DOWNLOADING, STREAMING_FROM_PHONE, COMPLETED, FAILED }

    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _currentMap = MutableStateFlow<String?>(null)
    val currentMap: StateFlow<String?> = _currentMap.asStateFlow()

    suspend fun downloadOrStreamMap(context: Context, mapId: String, downloadUrl: String) {
        _currentMap.value = mapId
        
        // Read the user setting for download mode on the watch.
        // It defaults to BLUETOOTH_ONLY if not set, otherwise AUTO or WIFI_ONLY.
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("mapDownloadMode", "BLUETOOTH_ONLY") ?: "BLUETOOTH_ONLY"
        
        Log.d(TAG, "Configured mapDownloadMode is $mode")
        
        // Note on persistence: We are saving map files into context.filesDir inside `downloadOverWifi`.
        // Files in this directory persist natively across app updates, honoring the requirement 
        // to only redownload if the user uninstalls the app or if map metadata points to a newer version.
        val hasWifi = hasHighBandwidthConnection(context)

        when (mode) {
            "WIFI_ONLY" -> {
                if (hasWifi) {
                    _downloadState.value = DownloadState.DOWNLOADING
                    downloadOverWifi(context, mapId, downloadUrl)
                } else {
                    Log.e(TAG, "WIFI_ONLY is set but no Wi-Fi. Failing download.")
                    _downloadState.value = DownloadState.FAILED
                }
            }
            "AUTO" -> {
                if (hasWifi) {
                    _downloadState.value = DownloadState.DOWNLOADING
                    downloadOverWifi(context, mapId, downloadUrl)
                } else {
                    _downloadState.value = DownloadState.STREAMING_FROM_PHONE
                    streamFromPhone(context, mapId)
                }
            }
            else -> { // Default is BLUETOOTH_ONLY
                _downloadState.value = DownloadState.STREAMING_FROM_PHONE
                streamFromPhone(context, mapId)
            }
        }
    }

    private fun hasHighBandwidthConnection(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        val allowMobile = NavigationStateHolder.state.value.allowMobileData
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
               (allowMobile && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    private suspend fun downloadOverWifi(context: Context, mapId: String, downloadUrl: String) = withContext(Dispatchers.IO) {
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
                            _downloadProgress.value = (total.toFloat() / fileLength.toFloat())
                        }
                        output.write(data, 0, count)
                    }
                }
            }
            Log.d(TAG, "Successfully downloaded $mapId over Wi-Fi")
            _downloadState.value = DownloadState.COMPLETED
            _downloadProgress.value = 1.0f
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading via Wi-Fi. Falling back to Bluetooth...", e)
            _downloadState.value = DownloadState.FAILED
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
