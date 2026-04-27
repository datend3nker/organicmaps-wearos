package app.organicmaps.wear

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
class WearDataListenerService : Service() {
    private val TAG = "WearDataListenerFdroid"
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
        isRunning = true
        thread {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@thread
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
                    val lat = buffer.double
                    val lon = buffer.double
                    results.add(SearchResultItem(name, "", lat, lon))
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
                val offlineMapsEnabled = buffer.get().toInt() == 1
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
                    mapEnabled = mapEnabled,
                    offlineMapsEnabled = offlineMapsEnabled
                ))
            }
            5 -> { // MSG_TYPE_MAP_DOWNLOAD
                val countryId = String(data, StandardCharsets.UTF_8)
                NavigationStateHolder.update(NavigationStateHolder.state.value.copy(openMapManager = true))
                // F-Droid: only local download on watch
                launchOmaps()
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
