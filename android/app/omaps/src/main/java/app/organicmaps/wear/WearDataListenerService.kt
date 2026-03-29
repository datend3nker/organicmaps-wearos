package app.organicmaps.wear

import android.content.Intent
import app.organicmaps.wear.presentation.Omaps
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"
    private val PATH_START_NAVIGATION = "/navigation/start"
    private val PATH_STOP_NAVIGATION = "/navigation/stop"

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: ${messageEvent.path}")
        if (messageEvent.path == PATH_START_NAVIGATION) {
            Log.d(TAG, "Start message received, ensuring isActive=true")
            val currentState = NavigationStateHolder.state.value
            NavigationStateHolder.update(currentState.copy(isActive = true))
            launchOmaps()
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged: Received ${dataEvents.count} events")
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            Log.d(TAG, "Event type: ${event.type}, URI: $uri")
            
            if (event.type == DataEvent.TYPE_CHANGED && uri.path == "/navigation/status") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                
                val isActive = dataMap.getBoolean("active", false)
                Log.d(TAG, "Data update: active=$isActive, distToTurn=${dataMap.getString("distToTurn")}")

                val currentState = NavigationStateHolder.state.value
                val newState = currentState.copy(
                    distToTurn = dataMap.getString("distToTurn") ?: currentState.distToTurn,
                    nextStreet = dataMap.getString("nextStreet") ?: currentState.nextStreet,
                    carDirection = if (dataMap.containsKey("carDirection")) dataMap.getInt("carDirection") else currentState.carDirection,
                    pedestrianDirection = if (dataMap.containsKey("pedestrianDirection")) dataMap.getInt("pedestrianDirection") else currentState.pedestrianDirection,
                    exitNum = if (dataMap.containsKey("exitNum")) dataMap.getInt("exitNum") else currentState.exitNum,
                    isActive = isActive,
                    speedMps = if (dataMap.containsKey("speedMps")) dataMap.getDouble("speedMps") else currentState.speedMps,
                    completionPercent = if (dataMap.containsKey("completionPercent")) dataMap.getDouble("completionPercent") else currentState.completionPercent,
                    distToTarget = dataMap.getString("distToTarget") ?: currentState.distToTarget
                )
                
                Log.d(TAG, "Updating state: isActive=${newState.isActive}, street=${newState.nextStreet}")
                NavigationStateHolder.update(newState)
                
                if (newState.isActive && !currentState.isActive) {
                    launchOmaps()
                }
            }
        }
    }

    private fun launchOmaps() {
        Log.d(TAG, "Launching Omaps activity")
        val intent = Intent(this, Omaps::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
}
