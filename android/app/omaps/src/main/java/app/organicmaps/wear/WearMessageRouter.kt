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

    private val sLastMsgTimes = mutableMapOf<String, Long>()
    private val sLastMsgHashes = mutableMapOf<String, Int>()

    @JvmStatic
    private val prefRequestDebouncer = object : Runnable {
        private var ctx: Context? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun request(context: Context) {
            ctx = context.applicationContext
            handler.removeCallbacks(this)
            handler.postDelayed(this, 2000)
        }
        override fun run() {
            ctx?.let { WearCommandService.requestPreferences(it) }
        }
    }

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
        
        val lastHash = sLastMsgHashes[path]
        val lastTime = sLastMsgTimes[path]
        
        var window = 500L
        if (path.endsWith("/request") || path.endsWith("/trigger") || path == PATH_PONG || path == WearProtocol.PATH_PING) window = 50L
        
        // Robust deduplication to ignore redundant listeners
        if (lastHash == hash && lastTime != null && (now - lastTime) < window) {
            app.organicmaps.sdk.sync.WearLog.d("onMessageReceived IGNORED (Deduplication): $path")
            return
        }
        sLastMsgHashes[path] = hash
        sLastMsgTimes[path] = now

        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val backend = prefs.getString("pref_wear_os_backend", "GMS") ?: "GMS"
        app.organicmaps.sdk.sync.WearLog.logReceived("WATCH", backend, path, payload.size)
        
        // Only notify activity for messages from the SELECTED backend
        // Bluetooth messages come from "bluetooth_phone", others are GMS/System
        val isFromSelectedBackend = (backend == "BLUETOOTH" && sourceNodeId == "bluetooth_phone") ||
                                   (backend == "GMS" && sourceNodeId != "bluetooth_phone")

        // Control-plane paths must always be processed (even from the non-selected
        // transport) so a backend switch can be negotiated over whichever transport
        // currently carries it.
        val isControlPlane = path == WearProtocol.PATH_PING || path == PATH_PONG ||
                             path == WearProtocol.PATH_HANDSHAKE || path == WearProtocol.PATH_BACKEND_SWITCH

        if (isFromSelectedBackend) {
            (context.applicationContext as WearApplication).onActivityReceived()
            NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
        }

        // Drop data-plane traffic arriving on the non-selected backend. Without this a
        // lingering/secondary transport (e.g. a still-running Bluetooth listener after
        // switching to GMS) keeps injecting state — the root of the "switching isn't
        // smooth / things still arrive over Bluetooth" symptom.
        if (!isFromSelectedBackend && !isControlPlane) {
            app.organicmaps.sdk.sync.WearLog.d("Dropping $path from non-selected backend (source=$sourceNodeId, selected=$backend)")
            return
        }

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
                // Version stripping is now handled in WearDataListenerService, so payload starts with version code
                val remoteVersion = if (payload.size >= 4) java.nio.ByteBuffer.wrap(payload).int else -1
                Log.i(TAG, "Remote app version: $remoteVersion")
                
                // On handshake, always request fresh preferences
                prefRequestDebouncer.request(context)
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
                VirtualMwmManager.markMetadataFailure(mapId)
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
                prefRequestDebouncer.request(context)
                return
            }
            WearProtocol.PATH_PREFERENCES_TRIGGER -> {
                Log.d(TAG, "DEBUG_GMS: Preferences trigger received")
                prefRequestDebouncer.request(context)
                return
            }
            WearProtocol.PATH_BOOKMARKS_METADATA -> {
                WatchBookmarkSyncManager.handleIncomingMetadata(context, payload)
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
