package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import app.organicmaps.sdk.sync.WearProtocol
import app.organicmaps.wear.message.WearMessageDispatcher
import app.organicmaps.wear.presentation.Omaps

object WearMessageRouter {
    private const val TAG = "WearMessageRouter"
    private const val PATH_PONG = WearProtocol.PATH_PONG
    
    private val _dispatcher = WearMessageDispatcher()
    private fun getDispatcher(): WearMessageDispatcher {
        return _dispatcher
    }

    private var sLastMsgHash = 0
    private var sLastMsgTime = 0L

    @JvmStatic
    fun onMessageReceived(context: Context, path: String, data: ByteArray?, sourceNodeId: String, localNodeId: String? = null) {
        if (localNodeId != null && localNodeId == sourceNodeId) {
            app.organicmaps.sdk.sync.WearLog.v("Ignoring local loopback message at $path")
            return
        }
        
        val currentLocalId = SyncStateManager.localNodeId
        if (currentLocalId != null && sourceNodeId == currentLocalId) {
            app.organicmaps.sdk.sync.WearLog.v("Ignoring local loopback message (backend ID match) at $path")
            return
        }

        val payload = data ?: ByteArray(0)
        
        val hash = path.hashCode() xor java.util.Arrays.hashCode(payload)
        val now = System.currentTimeMillis()
        // Robust deduplication (500ms) to ignore redundant listeners
        if (hash == sLastMsgHash && (now - sLastMsgTime) < 500) {
            app.organicmaps.sdk.sync.WearLog.d("onMessageReceived IGNORED (Deduplication): $path")
            return
        }
        sLastMsgHash = hash
        sLastMsgTime = now

        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val backend = prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS"
        app.organicmaps.sdk.sync.WearLog.logReceived("WATCH", backend, path, payload.size)
        
        // Notify activity confirmed connection for ANY valid message
        (context.applicationContext as WearApplication).onActivityReceived()
        NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
        
        // Handshake: Send pong back to phone so it knows we are alive
        if (path != PATH_PONG) {
            WearCommandService.sendPong(context, sourceNodeId)
        }

        if (payload.isEmpty()) {
            Log.d(TAG, "DEBUG_GMS: Watch routing heartbeat/trigger message: $path from $sourceNodeId")
        } else {
            Log.d(TAG, "DEBUG_GMS: Watch routing message: $path from $sourceNodeId (payload=${payload.size} bytes)")
        }
        
        if (path == PATH_PONG) {
            return
        }

        if (path == "/launch") {
            launchOmaps(context)
            return
        }

        when (path) {
            WearProtocol.PATH_HANDSHAKE -> {
                Log.d(TAG, "Handshake received")
                val remoteVersion = java.nio.ByteBuffer.wrap(payload).int
                Log.i(TAG, "Remote app version: $remoteVersion")
                
                // On handshake, always request fresh preferences
                WearCommandService.requestPreferences(context)
                return
            }
            WearProtocol.PATH_NAVIGATION_START -> {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(isActive = true, isMapUnlockedBeforeNav = currentState.isMapUnlocked, isMapUnlocked = false))
                launchOmaps(context)
                return
            }
            WearProtocol.PATH_MAP_DOWNLOAD_NOT_FOUND -> {
                val mapId = String(payload)
                Log.w(TAG, "Phone reported map NOT FOUND: $mapId")
                WearMapDownloader.onMapMissingOnPhone(context, mapId)
                return
            }
            WearProtocol.PATH_BACKEND_SWITCH -> {
                handleBackendSwitch(context, String(payload))
                return
            }
            WearProtocol.PATH_PING -> {
                Log.d(TAG, "DEBUG_GMS: Ping received, sending pong")
                WearCommandService.sendPong(context, sourceNodeId)
                WearCommandService.requestPreferences(context)
                return
            }
            WearProtocol.PATH_PREFERENCES_TRIGGER -> {
                Log.d(TAG, "DEBUG_GMS: Preferences trigger received")
                WearCommandService.requestPreferences(context)
                return
            }
        }

        // Delegate everything else to the unified dispatcher
        getDispatcher().dispatch(path, payload, context)
    }

    private fun handleBackendSwitch(context: Context, newBackend: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("pref_wear_os_backend", newBackend).apply()
            WearCommandService.initBackend(context)
            if (newBackend == "BLUETOOTH") {
                context.startService(Intent(context, BluetoothWearDataListenerService::class.java))
            } else if (BuildConfig.FLAVOR != "oss") {
                context.stopService(Intent(context, BluetoothWearDataListenerService::class.java))
            }
        }
    }

    private fun launchOmaps(context: Context) {
        val intent = Intent(context, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}
