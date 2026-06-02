package app.organicmaps.wear

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import app.organicmaps.wear.message.WearMessageDispatcher
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.*

/**
 * F-Droid implementation of data listener using raw Bluetooth RFCOMM Sockets.
 * This replaces the Google Play Services WearableListenerService.
 */
class BluetoothWearDataListenerService : Service() {
    companion object {
        private const val TAG = "BluetoothDataListener"
        private val OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130")
        
        @Volatile
        var activeSocket: BluetoothSocket? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    
    private lateinit var dispatcher: WearMessageDispatcher

    override fun onCreate() {
        super.onCreate()
        dispatcher = WearMessageDispatcher()
        startListening()
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        activeSocket?.let {
            try { it.close() } catch (ignored: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "app.organicmaps.wear.CANCEL_SYNC") {
            val mapId = intent.getStringExtra("mapId")
            if (mapId != null) {
                cancelMapSync(mapId)
            }
        }
        return START_STICKY
    }

    private fun cancelMapSync(mapId: String) {
        Log.d(TAG, "Cleaning up cancelled map sync for $mapId")
        SyncStateManager.mapOutputStreams.remove(mapId)?.let {
            try { it.close() } catch (ignored: Exception) {}
        }
        
        serviceScope.launch {
            try {
                val storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(this@BluetoothWearDataListenerService)
                val dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion()
                val versionedPath = java.io.File(storagePath, dataVersion.toString())
                val tmpFile = java.io.File(versionedPath, "$mapId.mwm.tmp")
                if (tmpFile.exists()) {
                    tmpFile.delete()
                    Log.d(TAG, "Deleted temporary file: ${tmpFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up .tmp file for $mapId", e)
            }
        }
    }

    private fun startListening() {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Bluetooth permission missing. Service will wait but cannot function.")
            // You might want to show a notification here
            return
        }

        isRunning = true
        serviceScope.launch {
            Log.d(TAG, "Bluetooth listener started")
            var retryDelay = 5000L
            while (isRunning) {
                if (activeSocket?.isConnected != true) {
                    val socket = connectToPhone()
                    if (socket != null) {
                        retryDelay = 5000L
                        try {
                            handleClient(socket)
                        } catch (e: Exception) {
                            Log.w(TAG, "Connection lost or error handling socket: ${e.message}")
                            activeSocket?.close()
                            activeSocket = null
                        }
                    } else {
                        Log.d(TAG, "Phone not found, retrying in ${retryDelay/1000}s")
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(60000L)
                    }
                } else {
                    delay(5000)
                }
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun connectToPhone(): BluetoothSocket? {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return null
        if (!adapter.isEnabled) return null

        try {
            val pairedDevices = adapter.bondedDevices ?: return null
            for (device in pairedDevices) {
                Log.d(TAG, "Connecting to: ${device.name}")
                val socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID)
                try {
                    socket.connect()
                    return socket
                } catch (e: Exception) {
                    try { socket.close() } catch (ignored: Exception) {}
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission lost", e)
        }
        return null
    }

    private suspend fun handleClient(socket: BluetoothSocket) = coroutineScope {
        activeSocket = socket
        Log.d(TAG, "Connected to phone")
        
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = true))

        WearCommandService.requestPreferences(this@BluetoothWearDataListenerService)
        WearCommandService.requestBookmarks(this@BluetoothWearDataListenerService)
        WearCommandService.syncSearchHistory(this@BluetoothWearDataListenerService)

        if (app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World") != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
            WearCommandService.requestMwmMetadata(this@BluetoothWearDataListenerService, "World")
        }

        launch(Dispatchers.IO) {
            try {
                val input = socket.getInputStream()
                val headerBuffer = ByteArray(6)
                
                while (isRunning && socket.isConnected) {
                    input.readFully(headerBuffer)
                    val header = ByteBuffer.wrap(headerBuffer)
                    val version = header.get()
                    val type = header.get()
                    val length = header.int

                    if (version != IWearSyncBackend.PROTOCOL_VERSION) {
                        throw java.io.IOException("Protocol version mismatch: received=$version, expected=${IWearSyncBackend.PROTOCOL_VERSION}")
                    }

                    if (length < 0 || length > 20 * 1024 * 1024 || type < 0 || type > 20) {
                        throw java.io.IOException("Invalid message header: type=$type, len=$length")
                    }

                    val payload = ByteArray(length)
                    input.readFully(payload)

                    withContext(Dispatchers.Main) {
                        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = true))
                        NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
                        (application as WearApplication).onActivityReceived()
                    }
                    
                    dispatcher.dispatch(type, payload, this@BluetoothWearDataListenerService)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Receiver thread error: ${e.message}")
                withContext(Dispatchers.Main) {
                    NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = false))
                }
            } finally {
                if (activeSocket == socket) activeSocket = null
                try { socket.close() } catch (ignored: Exception) {}
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
}
