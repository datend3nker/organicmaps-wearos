package app.organicmaps.wear;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import app.organicmaps.R;

public class WearServantNotificationManager {
    private static final String CHANNEL_ID = "wear_servant_channel";
    private static final int NOTIFICATION_ID = 1002;

    public static void createNotificationChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Wear OS Synchronization";
            String description = "Shows progress when serving map data to watch";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    public static void showServingNotification(@NonNull Context context, @NonNull String mapId, int progress) {
        // Only show if enabled in preferences
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("pref_sync_notifications", true)) {
            android.util.Log.d("WearServantNotif", "Notifications disabled in prefs");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.util.Log.w("WearServantNotif", "POST_NOTIFICATIONS permission missing");
                return;
            }
        }

        android.util.Log.d("WearServantNotif", "Showing notification: " + mapId + " " + progress + "%");
        createNotificationChannel(context);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Serving Map to Watch")
                .setContentText(mapId.replace("_", " "))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, progress, progress <= 0);

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {}
    }

    public static void hideNotification(@NonNull Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
    }
}
