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
        // length(m) + duration(s) appended by newer phones; absent on older ones (back-compatible).
        val distance = if (buffer.remaining() >= 8) buffer.double else 0.0
        val duration = if (buffer.remaining() >= 8) buffer.double else 0.0
        Log.d(TAG, "Recording status from phone: isRecording=$isRecording, startTime=$startTime, dist=$distance, dur=$duration")
        NavigationStateHolder.update(NavigationStateHolder.state.value.copy(
            isTrackRecording = isRecording,
            trackRecordingStartTime = startTime,
            trackRecordingDistance = distance,
            trackRecordingDuration = duration
        ))
    }
}
