package app.organicmaps.wear.message

import android.content.Context
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import java.nio.ByteBuffer

class TrackRecordingHandler : WearMessageHandler {
    companion object {
        private const val TAG = "TrackRecordingHandler"
    }

    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val isRecording = buffer.get().toInt() == 1
        val startTime = if (buffer.remaining() >= 8) buffer.long else 0L
        Log.d(TAG, "Recording status updated from phone (BT): isRecording=$isRecording, startTime=$startTime")
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            isTrackRecording = isRecording,
            trackRecordingStartTime = startTime
        ))
    }
}
