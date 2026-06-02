package app.organicmaps.wear;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import app.organicmaps.R;

public class WearCompanionNotificationManager {
    private static final String TAG = "WearCompanionNotif";
    
    private static final String CHANNEL_ID_PROGRESS = "wear_companion_progress";
    private static final String CHANNEL_ID_SILENT = "wear_companion_silent";
    
    public static final int NOTIFICATION_ID_MAP_SYNC = 1002;
    public static final int NOTIFICATION_ID_ROUTE = 1003;
    public static final int NOTIFICATION_ID_SEARCH = 1004;

    public static void createNotificationChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            if (notificationManager == null) return;

            // Progress channel (Low importance - silent by default but visible)
            NotificationChannel progressChannel = new NotificationChannel(
                    CHANNEL_ID_PROGRESS,
                    context.getString(R.string.notification_channel_wear_progress),
                    NotificationManager.IMPORTANCE_LOW
            );
            progressChannel.setDescription(context.getString(R.string.notification_channel_wear_progress_description));
            notificationManager.createNotificationChannel(progressChannel);

            // Silent channel (Min importance - less intrusive)
            NotificationChannel silentChannel = new NotificationChannel(
                    CHANNEL_ID_SILENT,
                    context.getString(R.string.notification_channel_wear_silent),
                    NotificationManager.IMPORTANCE_MIN
            );
            silentChannel.setDescription(context.getString(R.string.notification_channel_wear_silent_description));
            notificationManager.createNotificationChannel(silentChannel);
        }
    }

    public static void showServingNotification(@NonNull Context context, @NonNull String mapId, int progress) {
        showNotification(context, NOTIFICATION_ID_MAP_SYNC, 
                context.getString(R.string.notification_serving_map), 
                mapId.replace("_", " "), 
                progress, true);
    }

    public static void showRouteCalculationNotification(@NonNull Context context, @NonNull String destination) {
        showNotification(context, NOTIFICATION_ID_ROUTE, 
                context.getString(R.string.notification_calculating_route), 
                context.getString(R.string.notification_planning_route_to, destination), 
                -1, true);
    }

    public static void showSearchNotification(@NonNull Context context, @NonNull String query) {
        showNotification(context, NOTIFICATION_ID_SEARCH, 
                context.getString(R.string.notification_searching), 
                context.getString(R.string.notification_searching_for, query), 
                -1, false);
    }

    private static void showNotification(@NonNull Context context, int id, String title, String text, int progress, boolean ongoing) {
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean("pref_sync_notifications", true)) {
            Log.d(TAG, "DEBUG_NOTIF: Notifications disabled in prefs");
            return;
        }

        Log.d(TAG, "DEBUG_NOTIF: showNotification id=" + id + " title=" + title + " progress=" + progress);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "DEBUG_NOTIF: POST_NOTIFICATIONS permission missing");
                return;
            }
        }

        createNotificationChannels(context);
        
        String channelId = ongoing ? CHANNEL_ID_PROGRESS : CHANNEL_ID_SILENT;
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_splash)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(ongoing ? NotificationCompat.PRIORITY_LOW : NotificationCompat.PRIORITY_MIN)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);

        if (progress >= 0) {
            builder.setProgress(100, progress, false);
        } else if (ongoing) {
            builder.setProgress(100, 0, true);
        }

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException e) {
            Log.e(TAG, "DEBUG_NOTIF: SecurityException showing notification: " + e.getMessage());
        }
    }

    public static void hideNotification(@NonNull Context context, int id) {
        Log.d(TAG, "DEBUG_NOTIF: hideNotification id=" + id);
        NotificationManagerCompat.from(context).cancel(id);
    }
}
