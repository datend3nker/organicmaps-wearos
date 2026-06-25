package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.organicmaps.sdk.util.MapIdUtils
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.settings.StoragePathManager
import kotlin.time.Duration.Companion.seconds

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
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private var lastProgressTime: Long = 0

    fun setStreamingProgress(progress: Float) {
        _downloadProgress.value = progress
        lastProgressTime = System.currentTimeMillis()
        if (_downloadState.value != DownloadState.DOWNLOADING) {
            _downloadState.value = DownloadState.STREAMING_FROM_PHONE
        }
        _currentMap.value?.let { 
            WearNotificationManager.updateSyncNotification(WearApplication.instance, it, progress, isStreaming = true)
        }
    }

    fun setStreamingMap(mapId: String) {
        val normalizedMapId = MapIdUtils.normalize(mapId)!!
        _currentMap.value = normalizedMapId
        _downloadState.value = DownloadState.STREAMING_FROM_PHONE
        _downloadProgress.value = 0f
        lastProgressTime = System.currentTimeMillis()
        startWatchdog()
        WearNotificationManager.updateSyncNotification(WearApplication.instance, normalizedMapId, 0f, isStreaming = true)
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.Main).launch {
            while (_downloadState.value == DownloadState.STREAMING_FROM_PHONE) {
                delay(10.seconds)
                if ((System.currentTimeMillis() - lastProgressTime) > 60000) {
                    Log.e(TAG, "DEBUG_WEAR_PIPELINE: Map streaming STALLED for 60s, marking as FAILED")
                    _downloadState.value = DownloadState.FAILED
                    break
                }
            }
        }
    }

    fun onDownloadCompleted() {
        _downloadState.value = DownloadState.COMPLETED
        _downloadProgress.value = 1.0f
        _currentMap.value = null
        currentDownloadJob = null
        watchdogJob?.cancel()
        WearNotificationManager.hideSyncNotification(WearApplication.instance)
        NavigationStateHolder.update { it.copy(missingMapId = null) }
    }

    fun onDownloadCancelled() {
        _downloadState.value = DownloadState.CANCELLED
        _downloadProgress.value = 0f
        _currentMap.value = null
        currentDownloadJob = null
        watchdogJob?.cancel()
        WearNotificationManager.hideSyncNotification(WearApplication.instance)
    }

    fun cancel(context: Context) {
        val mapId = _currentMap.value
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        watchdogJob?.cancel()
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
                } catch (_: Exception) {
                    Log.d(TAG, "Bluetooth listener not notified of cancel (GMS mode?)")
                }
            }
        }
        _downloadState.value = DownloadState.CANCELLED
        _currentMap.value = null
        WearNotificationManager.hideSyncNotification(context)
    }

    fun onMapMissingOnPhone(context: Context, mapId: String) {
        val normalizedMapId = MapIdUtils.normalize(mapId)!!

        val wasStreamingThis = _currentMap.value == normalizedMapId &&
            _downloadState.value == DownloadState.STREAMING_FROM_PHONE

        // Don't silently pull a whole region from the internet behind the user's back. Surface a
        // prompt instead (the Map Manager "Not on Phone — Download via Internet?" card, plus the
        // MapPanel "Map not on phone" control) so the user explicitly chooses, respecting their
        // data preference (#6). In INTERNET/DIRECT_DOWNLOAD mode the tap never reaches here — it
        // goes straight to the internet — so this ask is PHONE_SYNC-only by construction.
        NavigationStateHolder.update { it.copy(missingMapId = normalizedMapId) }
        val online = hasInternetAccess(context)
        val msg = if (online)
            "Map '$normalizedMapId' not on phone — open Map Manager to download it via internet"
        else
            "Map '$normalizedMapId' not on phone, and the watch has no internet connection"
        NavigationStateHolder.emitEvent(UiEvent.ShowToast(msg, Toast.LENGTH_LONG))

        if (wasStreamingThis) {
            _downloadState.value = DownloadState.FAILED
            watchdogJob?.cancel()
        }
    }

    suspend fun downloadOrStreamMap(context: Context, mapId: String, downloadUrl: String = "", forceInternet: Boolean = false) {
        (context.applicationContext as WearApplication).waitForInitializationSuspend()
        currentDownloadJob?.cancel()
        currentDownloadJob = currentCoroutineContext()[Job]

        val normalizedMapId = MapIdUtils.normalize(mapId)!!
        _currentMap.value = normalizedMapId
        NavigationStateHolder.update { it.copy(missingMapId = null) }

        // Clean up any existing virtual map first so the download starts fresh
        app.organicmaps.wear.VirtualMwmManager.deleteVirtual(context, "$normalizedMapId.mwm")
        
        val dataVersion = Framework.nativeGetDataVersion()
        // The download CDN uses underscores in URLs; everywhere else the map id
        // must keep its original spaces.
        val urlMapId = normalizedMapId.replace(" ", "_")
        val finalUrl = downloadUrl.ifEmpty {
            "https://direct.organicmaps.app/$dataVersion/$urlMapId.mwm"
        }

        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val mode = if (forceInternet) "INTERNET" else (prefs.getString("pref_wear_os_map_download_mode", "PHONE_SYNC") ?: "PHONE_SYNC")
        
        Log.d(TAG, "Configured mapDownloadMode is $mode (forceInternet=$forceInternet)")
        
        val hasInternet = hasInternetAccess(context)

        when (mode) {
            "INTERNET", "DIRECT_DOWNLOAD" -> {
                if (hasInternet) {
                    _downloadState.value = DownloadState.DOWNLOADING
                    downloadOverInternet(context, normalizedMapId, finalUrl)
                } else {
                    Log.e(TAG, "DIRECT_DOWNLOAD mode set but no internet access. Falling back to phone sync.")
                    _downloadState.value = DownloadState.STREAMING_FROM_PHONE
                    streamFromPhone(context, normalizedMapId)
                }
            }
            else -> { // Default is PHONE_SYNC
                _downloadState.value = DownloadState.STREAMING_FROM_PHONE
                lastProgressTime = System.currentTimeMillis()
                startWatchdog()
                streamFromPhone(context, normalizedMapId)
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
            val storagePath = StoragePathManager.findMapsStorage(context)
            val dataVersion = Framework.nativeGetDataVersion()
            val versionedPath = File(storagePath, dataVersion.toString())
            if (!versionedPath.exists()) versionedPath.mkdirs()

            // A prior phone-streamed session may have left a sparse virtual .mwm at this exact path
            // (same filename as a real download — see VirtualMwmManager.mount). FileOutputStream below
            // would silently truncate it, but its .bits sidecar would survive and get re-mounted as a
            // "complete" map full of zeroed holes on next launch (the issue #7 corruption). Tear the
            // virtual mount down cleanly first so the real download starts from a clean file.
            app.organicmaps.wear.VirtualMwmManager.deleteVirtual(context, "$mapId.mwm")

            val file = File(versionedPath, "$mapId.mwm")
            connection.getInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    val data = ByteArray(4096)
                    var total: Long = 0
                    while (true) {
                        yield()
                        val count = input.read(data)
                        if (count == -1) break
                        total += count.toLong()
                        if (fileLength > 0) {
                            val progress = total.toFloat() / fileLength.toFloat()
                            _downloadProgress.value = progress
                            _currentMap.value?.let { 
                                WearNotificationManager.updateSyncNotification(context, it, progress, isStreaming = false)
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
            if (e is CancellationException || e.cause is CancellationException) {
                _downloadState.value = DownloadState.CANCELLED
                throw e
            }
            Log.e(TAG, "Failed downloading via internet. Falling back to phone sync...", e)
            _downloadState.value = DownloadState.STREAMING_FROM_PHONE
            lastProgressTime = System.currentTimeMillis()
            startWatchdog()
            streamFromPhone(context, mapId)
        }
    }

    private suspend fun streamFromPhone(context: Context, mapId: String) = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.STREAMING_FROM_PHONE
        _downloadProgress.value = 0.0f

        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val currentBackend = prefs.getString("pref_wear_os_backend", "GMS")

        if (currentBackend == "GMS") {
            Log.d(TAG, "Checking GMS availability before streaming...")
            val nodes = withTimeoutOrNull(10.seconds) {
                try {
                    Wearable.getNodeClient(context).connectedNodes.await()
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking GMS nodes", e)
                    null
                }
            }

            if (nodes.isNullOrEmpty()) {
                // Respect the user's selected backend: do NOT silently flip to Bluetooth.
                // Surface the problem and abort so the indicator/pref stay consistent.
                NavigationStateHolder.emitEvent(UiEvent.ShowToast("Phone not reachable over GMS. Check the connection or switch backend in Settings."))
                Log.w(TAG, "GMS nodes not found or timeout. Aborting map sync (backend left as GMS).")
                _downloadState.value = DownloadState.FAILED
                watchdogJob?.cancel()
                WearNotificationManager.hideSyncNotification(context)
                return@withContext
            }
        }

        try {
            WearCommandService.sendMapDownloadRequest(context, mapId)
            Log.d(TAG, "Requested phone to stream $mapId over $currentBackend")
            // Completion will be handled when the channel stream finishes in DataListenerService
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request map streaming from phone", e)
            _downloadState.value = DownloadState.FAILED
        }
    }
}
