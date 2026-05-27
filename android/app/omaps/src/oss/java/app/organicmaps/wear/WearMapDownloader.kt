package app.organicmaps.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * F-Droid implementation of WearMapDownloader.
 * It focuses on Standalone mode (local Wi-Fi download) since Bluetooth is too slow for maps.
 */
object WearMapDownloader {
    private const val TAG = "WearMapDownloaderFdroid"

    enum class DownloadState { IDLE, DOWNLOADING, COMPLETED, FAILED }

    private val _downloadState = MutableStateFlow(DownloadState.IDLE)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _currentMap = MutableStateFlow<String?>(null)
    val currentMap: StateFlow<String?> = _currentMap.asStateFlow()

    suspend fun downloadOrStreamMap(context: Context, mapId: String, downloadUrl: String = "") {
        _currentMap.value = mapId
        
        val finalUrl = if (downloadUrl.isEmpty()) {
            "https://direct.organicmaps.app/251123/$mapId.mwm"
        } else {
            downloadUrl
        }
        
        if (hasHighBandwidthConnection(context)) {
            _downloadState.value = DownloadState.DOWNLOADING
            downloadOverWifi(context, mapId, finalUrl)
        } else {
            Log.e(TAG, "F-Droid: Maps require Wi-Fi on the watch. No connection found.")
            _downloadState.value = DownloadState.FAILED
        }
    }

    private fun hasHighBandwidthConnection(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
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
            Log.e(TAG, "Failed downloading via Wi-Fi: ${e.message}")
            _downloadState.value = DownloadState.FAILED
        }
    }
}
