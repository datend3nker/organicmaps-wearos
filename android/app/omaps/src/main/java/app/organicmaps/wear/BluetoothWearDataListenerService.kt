package app.organicmaps.wear

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import app.organicmaps.sdk.sync.BluetoothSyncConnection
import app.organicmaps.sdk.sync.SyncConnection
import app.organicmaps.sdk.sync.TcpSyncConnection
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.sdk.settings.StoragePathManager
import app.organicmaps.sdk.Framework
import app.organicmaps.sdk.downloader.MapManager
import app.organicmaps.sdk.downloader.CountryItem
import app.organicmaps.wear.message.WearMessageDispatcher
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
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
        var activeConnection: SyncConnection? = null
            private set

        /**
         * Close and clear the single shared connection. Called by the sender backend on a write
         * error so the listener loop reconnects. The listener service is the sole owner of the
         * socket (it has the read loop); nothing else may create one.
         */
        fun dropConnection() {
            synchronized(this) {
                try { activeConnection?.close() } catch (_: Exception) {}
                activeConnection = null
            }
        }
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
        activeConnection?.let {
            try { it.close() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "app.organicmaps.wear.CANCEL_SYNC") {
            intent.getStringExtra("mapId")?.let {
                cancelMapSync(it)
            }
        }
        return START_STICKY
    }

    private fun cancelMapSync(mapId: String) {
        Log.d(TAG, "Cleaning up cancelled map sync for $mapId")
        SyncStateManager.mapOutputStreams.remove(mapId)?.let {
            try { it.close() } catch (_: Exception) {}
        }
        
        serviceScope.launch {
            try {
                val storagePath = StoragePathManager.findMapsStorage(this@BluetoothWearDataListenerService)
                val dataVersion = Framework.nativeGetDataVersion()
                val versionedPath = File(storagePath, dataVersion.toString())
                val tmpFile = File(versionedPath, "$mapId.mwm.tmp")
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
        if (!hasBluetoothPermission() && !(Build.PRODUCT.contains("sdk") || Build.PRODUCT.contains("vbox"))) {
            Log.e(TAG, "Bluetooth permission missing. Service will wait but cannot function.")
            // You might want to show a notification here
            return
        }

        isRunning = true
        serviceScope.launch {
            Log.d(TAG, "REF_TCP_RFCOMM_SUCCESS: Listener started")
            var retryDelay = 5000L
            while (isRunning) {
                if (activeConnection?.isConnected() != true) {
                    val connection = connectToPhone()
                    if (connection != null) {
                        retryDelay = 5000L
                        try {
                            handleClient(connection)
                        } catch (e: Exception) {
                            Log.w(TAG, "Connection lost or error handling connection: ${e.message}")
                            activeConnection?.close()
                            activeConnection = null
                        }
                    } else {
                        Log.d(TAG, "Phone not found, retrying in ${retryDelay/1000}s")
                        delay(retryDelay.milliseconds)
                        retryDelay = (retryDelay * 2).coerceAtMost(60000L)
                    }
                } else {
                    delay(5000.milliseconds)
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

    private fun connectToPhone(): SyncConnection? {
        val isEmulator = Build.PRODUCT.contains("sdk") || Build.PRODUCT.contains("vbox")
        if (isEmulator) {
            try {
                Log.d(TAG, "Connecting to phone via TCP (Emulator)...")
                val socket = Socket("10.0.2.2", 5610)
                return TcpSyncConnection(socket)
            } catch (e: Exception) {
                Log.d(TAG, "TCP connection failed: ${e.message}")
                Log.i(TAG, "EMULATOR TIP: To connect Watch emulator to Phone emulator, run: adb forward tcp:5610 tcp:5610")
            }
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return null
        if (!adapter.isEnabled) return null

        try {
            val pairedDevices = adapter.bondedDevices ?: return null
            for (device in pairedDevices) {
                Log.d(TAG, "Connecting to: ${device.name}")
                val socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID)
                try {
                    socket.connect()
                    return BluetoothSyncConnection(socket)
                } catch (_: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission lost", e)
        }
        return null
    }

    private suspend fun handleClient(connection: SyncConnection) = coroutineScope {
        activeConnection = connection
        Log.d(TAG, "Connected to phone")

        // A live socket is not the same as an app-level connection, and it must not flip the
        // indicator unless Bluetooth is the *selected* backend. WearMessageRouter marks
        // isPhoneConnected when the first gated message (handshake/pong/data) arrives — the
        // requests below trigger exactly those responses.

        WearCommandService.requestPreferences(this@BluetoothWearDataListenerService)
        WearCommandService.requestBookmarks(this@BluetoothWearDataListenerService)
        WearCommandService.syncSearchHistory(this@BluetoothWearDataListenerService)

        if (MapManager.nativeGetStatus("World") != CountryItem.STATUS_DONE) {
            WearCommandService.requestMwmMetadata(this@BluetoothWearDataListenerService, "World")
        }

        launch(Dispatchers.IO) {
            try {
                val input = connection.getInputStream()
                val headerBuffer = ByteArray(6)
                
                while (isRunning && connection.isConnected()) {
                    input.readFully(headerBuffer)
                    val header = ByteBuffer.wrap(headerBuffer)
                    val version = header.get()
                    val type = header.get()
                    val length = header.int

                    if (version != WearProtocol.PROTOCOL_VERSION) {
                        Log.e(TAG, "DEBUG_BT_PIPELINE: Protocol version mismatch: received=$version, expected=${WearProtocol.PROTOCOL_VERSION}")
                        throw IOException("Protocol version mismatch: received=$version, expected=${WearProtocol.PROTOCOL_VERSION}")
                    }

                    if (length < 0 || length > 20 * 1024 * 1024 || type < 0 || type > WearProtocol.MAX_MESSAGE_TYPE) {
                        throw IOException("Invalid message header: type=$type, len=$length")
                    }

                    val payload = ByteArray(length)
                    input.readFully(payload)

                    // NOTE: do NOT mark isPhoneConnected here. Liveness must be gated by the
                    // *selected* backend — WearMessageRouter.onMessageReceived does that
                    // (only "bluetooth_phone" messages count when the chosen backend is BLUETOOTH).
                    // Marking connected unconditionally here made the watch show "connected" off a
                    // lingering Bluetooth link even when GMS was selected (split-brain indicator).

                    // Route through WearMessageRouter (same as GMS) so control-plane messages —
                    // handshake (21), bookmarks-metadata (22), ping/pong, backend-switch — are
                    // handled on Bluetooth too, instead of being dropped by the type dispatcher.
                    val path = WearProtocol.getPath(type)
                    if (path != null) {
                        WearMessageRouter.onMessageReceived(
                            this@BluetoothWearDataListenerService,
                            path,
                            payload,
                            "bluetooth_phone",
                            null
                        )
                    } else {
                        dispatcher.dispatch(type, payload, this@BluetoothWearDataListenerService)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Receiver thread error: ${e.message}")
                withContext(Dispatchers.Main) {
                    NavigationStateHolder.update(NavigationStateHolder.state.value.copy(isPhoneConnected = false))
                }
            } finally {
                if (activeConnection == connection) activeConnection = null
                try { connection.close() } catch (_: Exception) {}
            }
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("EOF")
            offset += read
        }
    }
}
