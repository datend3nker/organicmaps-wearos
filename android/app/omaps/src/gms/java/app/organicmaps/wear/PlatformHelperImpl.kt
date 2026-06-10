package app.organicmaps.wear

import android.content.Context
import android.util.Log
import app.organicmaps.sdk.sync.WearProtocol
import com.google.android.gms.wearable.Wearable

object PlatformHelperImpl : PlatformHelper {
    private var localNodeId: String? = null

    override fun onApplicationCreate(context: Context) {
        Log.d("PlatformHelper", "DEBUG_GMS: Initializing GMS platform listeners")
        
        Wearable.getNodeClient(context).localNode.addOnSuccessListener { node ->
            localNodeId = node.id
            Log.i("PlatformHelper", "DEBUG_GMS: Local node identified: ${node.id} (${node.displayName})")
        }

        // manual listener in Application to ensure we catch messages early
        Wearable.getMessageClient(context).addListener { event ->
            val currentLocalId = localNodeId
            if (event.sourceNodeId == currentLocalId) {
                Log.v("WearApp", "DEBUG_GMS: Ignoring local loopback message at ${event.path}")
                return@addListener
            }
            
            // Re-check local ID if null (manual fetch fallback)
            if (currentLocalId == null) {
                Wearable.getNodeClient(context).localNode.addOnSuccessListener { node ->
                    localNodeId = node.id
                    if (event.sourceNodeId == localNodeId) {
                        Log.v("WearApp", "DEBUG_GMS: Ignoring local loopback message (deferred check) at ${event.path}")
                    } else {
                        processMessage(context, event)
                    }
                }
                return@addListener
            }

            processMessage(context, event)
        }

        // Diagnostic: Check connected nodes from Watch side
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            Log.d("WearApp", "DEBUG_GMS: Watch sees ${nodes.size} connected nodes")
            for (node in nodes) {
                Log.d("WearApp", "DEBUG_GMS:   - Node: ${node.displayName} ID: ${node.id}")
            }
        }
    }

    private fun processMessage(context: Context, event: com.google.android.gms.wearable.MessageEvent) {
        val data = event.data
        var dataHex = ""
        if (data != null && data.isNotEmpty()) {
            val sb = StringBuilder()
            for (i in 0 until Math.min(data.size, 10)) {
                sb.append(String.format("%02X ", data[i]))
            }
            dataHex = sb.toString()
        }
        Log.d("WearApp", "DEBUG_GMS_PIPELINE: manualListener received message. Path: ${event.path} Data size: ${data?.size ?: "null"} Hex: $dataHex")
        
        WearApplication.instance.onActivityReceived()
        GmsWearSyncBackend.activePeerId = event.sourceNodeId
        
        if (data != null && data.isNotEmpty()) {
            val version = data[0]
            if (version == WearProtocol.PROTOCOL_VERSION) {
                val payload = if (data.size > 1) data.copyOfRange(1, data.size) else ByteArray(0)
                WearMessageRouter.onMessageReceived(context, event.path, payload, event.sourceNodeId, localNodeId)
                return
            }
        }
        WearMessageRouter.onMessageReceived(context, event.path, data, event.sourceNodeId, localNodeId)
    }
}
