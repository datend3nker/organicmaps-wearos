package app.organicmaps.wear

import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import app.organicmaps.wear.presentation.Omaps
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread

/**
 * F-Droid implementation of data listener using raw Bluetooth RFCOMM Sockets.
 * This replaces the Google Play Services WearableListenerService.
 */
class BluetoothWearDataListenerService : Service() {
    private val TAG = "BluetoothDataListener"
    private val OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130")

    private var serverSocket: BluetoothServerSocket? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        startListening()
    }

    override fun onDestroy() {
        isRunning = false
        serverSocket?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission missing")
                return
            }
        }
        isRunning = true
        thread {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return@thread
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("OrganicMapsSync", OM_WEAR_UUID)
                while (isRunning) {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: BluetoothSocket) {
        thread {
            try {
                val input = socket.getInputStream()
                while (isRunning && socket.isConnected) {
                    val type = input.read()
                    if (type == -1) break
                    
                    val lengthBuffer = ByteArray(4)
                    input.readFully(lengthBuffer)
                    val length = ByteBuffer.wrap(lengthBuffer).int
                    
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    
                    NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                        isPhoneConnected = true,
                        lastMessageTimestamp = System.currentTimeMillis()
                    ))
                    processMessage(type.toByte(), payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection lost: ${e.message}")
            } finally {
                socket.close()
            }
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) throw java.io.IOException("EOF")
            offset += read
        }
    }

    private fun processMessage(type: Byte, data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        when (type.toInt()) {
            1 -> { // MSG_TYPE_NAV_STATUS
                (application as WearApplication).onPongReceived()
                val active = buffer.get().toInt() == 1
                if (!active) {
                    NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isActive = false))
                    return
                }
                val carDir = buffer.get().toInt()
                val pedDir = buffer.get().toInt()
                val exitNum = buffer.get().toInt()
                val progress = buffer.float.toDouble()
                val lat = buffer.double
                val lon = buffer.double
                val turnLat = buffer.double
                val turnLon = buffer.double
                val streetLen = buffer.int
                val distLen = buffer.int
                
                val street = String(data, buffer.position(), streetLen, StandardCharsets.UTF_8)
                buffer.position(buffer.position() + streetLen)
                val dist = String(data, buffer.position(), distLen, StandardCharsets.UTF_8)
                
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(
                    isActive = true,
                    isNavigating = true,
                    carDirection = carDir,
                    pedestrianDirection = pedDir,
                    exitNum = exitNum,
                    completionPercent = progress,
                    lat = lat,
                    lon = lon,
                    turnLat = turnLat,
                    turnLon = turnLon,
                    nextStreet = street,
                    distToTurn = dist,
                    isPhoneConnected = true
                ))
                if (!currentState.isActive) launchOmaps()
            }
            2 -> { // MSG_TYPE_SEARCH_RESULTS
                val isSearching = buffer.get().toInt() == 1
                val results = mutableListOf<SearchResultItem>()
                while (buffer.hasRemaining()) {
                    val nameLen = buffer.int
                    val name = String(data, buffer.position(), nameLen, StandardCharsets.UTF_8)
                    buffer.position(buffer.position() + nameLen)
                    
                    val descLen = if (buffer.remaining() >= 4) buffer.int else 0
                    val desc = if (descLen > 0 && buffer.remaining() >= descLen) {
                        val s = String(data, buffer.position(), descLen, StandardCharsets.UTF_8)
                        buffer.position(buffer.position() + descLen)
                        s
                    } else ""
                    
                    val lat = buffer.double
                    val lon = buffer.double
                    results.add(SearchResultItem(name, desc, lat, lon))
                }
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                    searchResults = results,
                    isSearching = isSearching
                ))
            }
            3 -> { // MSG_TYPE_SEARCH_HISTORY
                val count = buffer.int
                val history = mutableListOf<String>()
                repeat(count) {
                    val len = buffer.int
                    val s = String(data, buffer.position(), len, StandardCharsets.UTF_8)
                    buffer.position(buffer.position() + len)
                    history.add(s)
                }
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(searchHistory = history))
            }
            4 -> { // MSG_TYPE_PREFERENCES
                val mapEnabled = buffer.get().toInt() == 1
                val watchLocalMode = buffer.get().toInt() == 1
                val standaloneMode = buffer.get().toInt() == 1
                
                val modeLen = if (buffer.remaining() >= 4) buffer.int else 0
                val mapDownloadMode = if (modeLen > 0 && buffer.remaining() >= modeLen) {
                    val bytes = ByteArray(modeLen)
                    buffer.get(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                } else "AUTO"

                val backendLen = if (buffer.remaining() >= 4) buffer.int else 0
                val backend = if (backendLen > 0 && buffer.remaining() >= backendLen) {
                    val bytes = ByteArray(backendLen)
                    buffer.get(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                } else "GMS"

                val poiMask = if (buffer.remaining() >= 4) buffer.int else 0x3F

                val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                val isForcedOffline = prefs.getBoolean("forceWatchLocalMode", false)
                val finalOfflineState = isForcedOffline || watchLocalMode

                prefs.edit()
                    .putBoolean("mapEnabled", mapEnabled)
                    .putBoolean("watchLocalMode", watchLocalMode)
                    .putBoolean("disconnectFromPhone", standaloneMode)
                    .putString("mapDownloadMode", mapDownloadMode)
                    .putString("pref_wear_os_backend", backend)
                    .putInt("poiCategoriesMask", poiMask)
                    .apply()

                WearCommandService.initBackend(this)
                if (backend == "GMS" && app.organicmaps.wear.BuildConfig.FLAVOR != "oss") {
                    stopService(Intent(this, BluetoothWearDataListenerService::class.java))
                }

                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                    mapEnabled = mapEnabled,
                    watchLocalMode = finalOfflineState,
                    standaloneMode = standaloneMode,
                    poiCategoriesMask = poiMask
                ))
            }
            5 -> { // MSG_TYPE_MAP_DOWNLOAD
                val countryId = String(data, StandardCharsets.UTF_8)
                val prefs = getSharedPreferences("wear_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("forceWatchLocalMode", true).apply()
                
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(openMapManager = true, watchLocalMode = true))
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val wearApp = applicationContext as app.organicmaps.wear.WearApplication
                        wearApp.waitForInitializationBlocking()
                        app.organicmaps.sdk.downloader.MapManager.startDownload(countryId)
                        app.organicmaps.sdk.downloader.MapManager.startDownload("World")
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
                launchOmaps()
            }
            7 -> { // MSG_TYPE_MAP_PROGRESS
                val countryLen = if (buffer.remaining() >= 4) buffer.int else 0
                if (countryLen > 0 && buffer.remaining() >= countryLen + 4) {
                    val countryBytes = ByteArray(countryLen)
                    buffer.get(countryBytes)
                    val countryId = String(countryBytes, StandardCharsets.UTF_8)
                    val progress = buffer.int
                    Log.d(TAG, "Received map progress via Bluetooth: $countryId -> $progress%")
                }
            }
            6 -> { // MSG_TYPE_MAP_TILE_RESPONSE
                val requestId = buffer.long
                val compressed = buffer.get().toInt() == 1
                var features = ByteArray(buffer.remaining())
                buffer.get(features)
                
                if (compressed) {
                    try {
                        features = GzipUtils.decompress(features)
                    } catch (e: Exception) {
                        Log.e(TAG, "Decompression failed", e)
                        return
                    }
                }
                MapTileStateHolder.update(requestId, features)
            }
            10 -> { // MSG_TYPE_COMMAND
                val pathLen = if (buffer.remaining() >= 4) buffer.int else 0
                if (pathLen > 0 && buffer.remaining() >= pathLen) {
                    val pathBytes = ByteArray(pathLen)
                    buffer.get(pathBytes)
                    val path = String(pathBytes, StandardCharsets.UTF_8)
                    if (path == "/pong") {
                        (application as WearApplication).onPongReceived()
                    }
                }
            }
        }
    }

    private fun launchOmaps() {
        val intent = Intent(this, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
