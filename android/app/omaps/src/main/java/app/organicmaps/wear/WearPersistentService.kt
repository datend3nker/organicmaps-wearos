package app.organicmaps.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import app.organicmaps.wear.presentation.Omaps

/**
 * Keeps the watch app resumable across screen-off (wrist-down). Wear OS 3+ only grants an app
 * "always-on" / return-on-wake behaviour while it publishes an OngoingActivity backed by a
 * foreground service; a plain activity — even one registering AmbientLifecycleObserver — is swapped
 * for the watch face on screen-off, so wrist-up shows the time instead of the app (issue #2).
 *
 * Started while [Omaps] is in the foreground and stopped only when it is truly finishing, so the app
 * behaves like the phone app: turning the wrist away and back returns to where you left off.
 */
class WearPersistentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()

        val touchIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, Omaps::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Organic Maps")
            .setContentText("Running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(touchIntent)

        OngoingActivity.Builder(applicationContext, NOTIF_ID, builder)
            .setStaticIcon(R.mipmap.ic_launcher)
            .setTouchIntent(touchIntent)
            .setStatus(Status.Builder().addTemplate("Running").build())
            .build()
            .apply(applicationContext)

        val notification: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "App Active", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Keeps Organic Maps open on your wrist"
                    }
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "wear_persistent_channel"
        private const val NOTIF_ID = 1100

        fun start(context: Context) {
            val intent = Intent(context, WearPersistentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WearPersistentService::class.java))
        }
    }
}
