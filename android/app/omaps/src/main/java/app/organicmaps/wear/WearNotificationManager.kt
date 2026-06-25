package app.organicmaps.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object WearNotificationManager {
    private const val CHANNEL_ID = "map_sync_channel"
    private const val SYNC_NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Map Synchronization"
            val descriptionText = "Shows progress of map downloads and syncs"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun updateSyncNotification(context: Context, mapId: String, progress: Float, isStreaming: Boolean) {
        if (!NavigationStateHolder.state.value.syncNotificationsEnabled) {
            android.util.Log.d("WearNotif", "DEBUG_NOTIF: Notifications disabled in state")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("WearNotif", "DEBUG_NOTIF: POST_NOTIFICATIONS permission missing")
                return
            }
        }

        val title = if (isStreaming) "Streaming Map from Phone" else "Downloading Map"
        val progressInt = (progress * 100).toInt()
        android.util.Log.d("WearNotif", "DEBUG_NOTIF: updateSyncNotification: $mapId $progressInt% isStreaming=$isStreaming")
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(mapId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, progressInt, progress <= 0f)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(SYNC_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            android.util.Log.e("WearNotif", "DEBUG_NOTIF: SecurityException updating notification: ${e.message}")
        }
    }

    fun hideSyncNotification(context: Context) {
        android.util.Log.d("WearNotif", "DEBUG_NOTIF: hideSyncNotification")
        NotificationManagerCompat.from(context).cancel(SYNC_NOTIFICATION_ID)
    }
}
