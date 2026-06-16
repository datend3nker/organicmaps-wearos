package app.organicmaps.wear

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class HeartbeatManager(private val context: Context) {
    private var lastReceivedTime = 0L
    private var lastSentTime = 0L
    private var lastLaunchRequestTime = 0L
    private var currentPingBackoffMs = 15000L
    
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        
        job = ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            delay(3000)
            while (isActive) {
                if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    delay(10000)
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
                val isStandaloneSetting = prefs.getBoolean("disconnectFromPhone", false)
                val currentState = NavigationStateHolder.state.value
                
                if (isStandaloneSetting) {
                    if (currentState.isPhoneConnected) {
                        NavigationStateHolder.update(currentState.copy(isPhoneConnected = false))
                    }
                    delay(60000)
                    continue
                }

                try {
                    val isConnected = currentState.isPhoneConnected
                    val idleMs = now - lastReceivedTime
                    val sinceSentMs = now - lastSentTime

                    val effectivePingInterval = if (isConnected) 10000L else currentPingBackoffMs
                    
                    if (idleMs > effectivePingInterval && sinceSentMs > effectivePingInterval) {
                        if (!isConnected && !currentState.watchLocalMode && !currentState.standaloneMode) {
                            if (now - lastLaunchRequestTime > currentPingBackoffMs.coerceAtLeast(30000L)) {
                                app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Heartbeat Backoff (${currentPingBackoffMs}ms): Trying to wake up phone app")
                                lastLaunchRequestTime = now
                                WearCommandService.launchPhoneApp(context)
                            }
                        }

                        app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Heartbeat (Connected=$isConnected, Interval=${effectivePingInterval}ms) - sending ping")
                        WearCommandService.sendPing(context)
                        lastSentTime = SystemClock.elapsedRealtime()
                        
                        if (!isConnected) {
                            currentPingBackoffMs = (currentPingBackoffMs * 1.5).toLong().coerceAtMost(300000L)
                        }
                    }

                    // Authority for disconnection
                    if (idleMs > 45000 && isConnected) {
                        app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Phone connection timeout ($idleMs ms since last message) - marking as disconnected")
                        var newState = currentState.copy(isPhoneConnected = false)
                        
                        if (!newState.watchLocalMode && !newState.standaloneMode) {
                            newState = newState.copy(watchLocalMode = true)
                            NavigationStateHolder.emitEvent(UiEvent.ShowToast("Phone connection lost. Using Offline Maps."))
                        }
                        NavigationStateHolder.update(newState)
                    }
                } catch (e: Exception) {
                    Log.e("HeartbeatManager", "Error in heartbeat loop", e)
                }

                delay(if (NavigationStateHolder.state.value.isPhoneConnected) 10000L else 15000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun onActivityReceived() {
        val now = SystemClock.elapsedRealtime()
        lastReceivedTime = now
        currentPingBackoffMs = 15000L
        val currentState = NavigationStateHolder.state.value
        if (!currentState.isPhoneConnected || currentState.isConnecting) {
            app.organicmaps.sdk.sync.WearLog.logState("WATCH", "Marking isPhoneConnected = true")
            NavigationStateHolder.update(currentState.copy(isPhoneConnected = true, isConnecting = false))
            
            // Reconnected! Trigger Handshake
            WearCommandService.sendHandshake(context)
            
            val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
            val backend = prefs.getString("pref_wear_os_backend", "GMS")
            NavigationStateHolder.emitEvent(UiEvent.ShowToast("Phone Connected ($backend)"))
        }
    }
}
