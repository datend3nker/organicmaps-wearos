package app.organicmaps.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class WearDataListenerService : WearableListenerService() {
    private val TAG = "WearDataListener"

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged: Received ${dataEvents.count} events")
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            Log.d(TAG, "Event type: ${event.type}, URI: $uri")
            
            if (event.type == DataEvent.TYPE_CHANGED && uri.path == "/navigation/status") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                
                val newState = NavigationState(
                    distToTurn = dataMap.getString("distToTurn") ?: "",
                    nextStreet = dataMap.getString("nextStreet") ?: "",
                    carDirection = dataMap.getInt("carDirection"),
                    exitNum = dataMap.getInt("exitNum"),
                    isActive = dataMap.getBoolean("active", false),
                    speedMps = dataMap.getDouble("speedMps", -1.0),
                    completionPercent = dataMap.getDouble("completionPercent", 0.0),
                    distToTarget = dataMap.getString("distToTarget") ?: ""
                )
                Log.d(TAG, "Updating state: isActive=${newState.isActive}, nextStreet=${newState.nextStreet}")
                NavigationStateHolder.update(newState)
            }
        }
    }
}
