package app.organicmaps.wear

import android.content.Context
import android.content.Intent
import android.util.Log
import app.organicmaps.wear.message.WearMessageDispatcher
import app.organicmaps.wear.presentation.Omaps

object WearMessageRouter {
    private const val TAG = "WearMessageRouter"
    private const val PATH_PONG = "/pong"
    
    private val _dispatcher = WearMessageDispatcher()
    private fun getDispatcher(): WearMessageDispatcher {
        return _dispatcher
    }

    fun onMessageReceived(context: Context, path: String, data: ByteArray, sourceNodeId: String) {
        if (data.isEmpty()) {
            Log.e(TAG, "Received empty message at $path")
            return
        }
        val version = data[0]
        if (version != IWearSyncBackend.PROTOCOL_VERSION) {
            Log.e(TAG, "Protocol version mismatch at $path: received=$version, expected=${IWearSyncBackend.PROTOCOL_VERSION}")
            return
        }
        val payload = data.copyOfRange(1, data.size)

        Log.d(TAG, "DEBUG_GMS: Watch routing message: $path from $sourceNodeId")
        
        NavigationStateHolder.updateTimestamp(System.currentTimeMillis())
        (context.applicationContext as WearApplication).onActivityReceived()
        NavigationStateHolder.update { it.copy(isPhoneConnected = true) }
        
        if (path == PATH_PONG) return

        if (path == "/launch") {
            launchOmaps(context)
            return
        }

        when (path) {
            "/navigation/start" -> {
                val currentState = NavigationStateHolder.state.value
                NavigationStateHolder.update(currentState.copy(isActive = true, isMapUnlockedBeforeNav = currentState.isMapUnlocked, isMapUnlocked = false))
                launchOmaps(context)
                return
            }
            "/map/download/not_found" -> {
                val mapId = String(payload)
                Log.w(TAG, "Phone reported map NOT FOUND: $mapId")
                WearMapDownloader.onMapMissingOnPhone(context, mapId)
                return
            }
            "/backend/switch" -> {
                handleBackendSwitch(context, String(payload))
                return
            }
            "/ping" -> {
                Log.d(TAG, "DEBUG_GMS: Ping received, sending pong")
                WearCommandService.sendPong(context, sourceNodeId)
                WearCommandService.requestPreferences(context)
                return
            }
            "/preferences/trigger" -> {
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
