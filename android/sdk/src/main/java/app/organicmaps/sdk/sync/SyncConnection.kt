package app.organicmaps.sdk.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface SyncConnection {
    @Throws(IOException::class)
    fun getInputStream(): InputStream
    @Throws(IOException::class)
    fun getOutputStream(): OutputStream
    fun isConnected(): Boolean
    @Throws(IOException::class)
    fun close()
}

class BluetoothSyncConnection(private val socket: android.bluetooth.BluetoothSocket) : SyncConnection {
    override fun getInputStream(): InputStream = socket.inputStream
    override fun getOutputStream(): OutputStream = socket.outputStream
    override fun isConnected(): Boolean = socket.isConnected
    override fun close() = socket.close()
}

class TcpSyncConnection(private val socket: java.net.Socket) : SyncConnection {
    override fun getInputStream(): InputStream = socket.getInputStream()
    override fun getOutputStream(): OutputStream = socket.getOutputStream()
    override fun isConnected(): Boolean = socket.isConnected
    override fun close() = socket.close()
}
