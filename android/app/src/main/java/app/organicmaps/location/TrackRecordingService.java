package app.organicmaps.location;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.location.LocationListener;
import app.organicmaps.sdk.location.TrackRecorder;
import app.organicmaps.sdk.util.LocationUtils;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.wear.WearSyncService;

public class TrackRecordingService extends Service implements LocationListener
{
  public static final String TRACK_REC_CHANNEL_ID = "TRACK RECORDING";
  public static final String STOP_TRACK_RECORDING = "STOP_TRACK_RECORDING";
  public static final int TRACK_REC_NOTIFICATION_ID = 54321;
  private static long sRecordingStartTime = 0;
  // Latest live stats for the in-progress recording, so the watch can show distance/duration while
  // the phone does the actual recording. Cached here (not the native single stats-listener slot) so
  // any sync layer can read them when pushing status to the watch.
  private static volatile double sLength = 0;   // metres
  private static volatile double sDuration = 0; // seconds
  // Pending stop disposition consumed by saveAndStop(): discard clears the recording, otherwise it is
  // saved under sPendingName (empty = auto-named). Defaults reproduce the legacy "save on stop".
  private static volatile boolean sDiscard = false;
  private static volatile String sPendingName = "";
  private Location mLastLocation = null;
  private long mLastWatchPushMs = 0;
  private NotificationCompat.Builder mNotificationBuilder;
  private static final String TAG = TrackRecordingService.class.getSimpleName();
  private boolean mWarningNotification = false;
  private NotificationCompat.Builder mWarningBuilder;
  private PendingIntent mPendingIntent;
  private PendingIntent mExitPendingIntent;

  public static long getRecordingStartTime()
  {
    return sRecordingStartTime;
  }

  /** Live recorded distance in metres (0 when not recording). */
  public static double getRecordedLength()
  {
    return sLength;
  }

  /** Live recorded duration in seconds (0 when not recording). */
  public static double getRecordedDuration()
  {
    return sDuration;
  }


  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }

  @RequiresPermission(value = ACCESS_FINE_LOCATION)
  public static void startForegroundService(@NonNull Context context)
  {
    if (!TrackRecorder.nativeIsTrackRecordingEnabled())
      TrackRecorder.nativeStartTrackRecording();
    MwmApplication.from(context).getLocationHelper().restartWithNewMode();
    ContextCompat.startForegroundService(context, new Intent(context, TrackRecordingService.class));
  }

  public static void createNotificationChannel(@NonNull Context context)
  {
    final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    final NotificationChannelCompat channel =
        new NotificationChannelCompat.Builder(TRACK_REC_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.track_recording))
            .setLightsEnabled(false)
            .setVibrationEnabled(false)
            .build();
    notificationManager.createNotificationChannel(channel);
  }

  private PendingIntent getPendingIntent(@NonNull Context context)
  {
    if (mPendingIntent != null)
      return mPendingIntent;

    final int FLAG_IMMUTABLE = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;
    final Intent contentIntent = new Intent(context, MwmActivity.class);
    mPendingIntent =
        PendingIntent.getActivity(context, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
    return mPendingIntent;
  }

  private PendingIntent getExitPendingIntent(@NonNull Context context)
  {
    if (mExitPendingIntent != null)
      return mExitPendingIntent;

    final int FLAG_IMMUTABLE = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;
    final Intent exitIntent = new Intent(context, TrackRecordingService.class);
    exitIntent.setAction(STOP_TRACK_RECORDING);
    mExitPendingIntent =
        PendingIntent.getService(context, 1, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
    return mExitPendingIntent;
  }

  @NonNull
  public NotificationCompat.Builder getNotificationBuilder(@NonNull Context context)
  {
    if (mNotificationBuilder != null)
      return mNotificationBuilder;

    mNotificationBuilder =
        new NotificationCompat.Builder(context, TRACK_REC_CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_splash)
            .setContentTitle(context.getString(R.string.track_recording))
            .addAction(0, context.getString(R.string.navigation_stop_button), getExitPendingIntent(context))
            .setContentIntent(getPendingIntent(context))
            .setColor(ContextCompat.getColor(context, R.color.notification));

    return mNotificationBuilder;
  }

  public static void stopService(@NonNull Context context)
  {
    Logger.i(TAG);
    context.stopService(new Intent(context, TrackRecordingService.class));
  }

  @Override
  public void onDestroy()
  {
    Logger.d(TAG);
    mNotificationBuilder = null;
    mWarningBuilder = null;
    saveAndStop();
    MwmApplication.from(this).getLocationHelper().removeListener(this);
    WearSyncService.sendTrackRecordingStatus(this, false);
    // The notification is cancelled automatically by the system.
  }

  /** Stop the recording, saving it (optional name; empty = auto-named). */
  public static void saveRecording(@NonNull Context context, @Nullable String name)
  {
    sDiscard = false;
    sPendingName = (name == null) ? "" : name;
    stopService(context);
  }

  /** Stop the recording, discarding (clearing) the recorded track without saving. */
  public static void discardRecording(@NonNull Context context)
  {
    sDiscard = true;
    sPendingName = "";
    stopService(context);
  }

  private void saveAndStop() {
    sRecordingStartTime = 0;
    sLength = 0;
    sDuration = 0;
    mLastLocation = null;
    if (TrackRecorder.nativeIsTrackRecordingEnabled())
    {
      if (sDiscard) {
        Logger.i(TAG, "Discarding track recording");
        TrackRecorder.nativeClearTrackRecording();
      } else if (!TrackRecorder.nativeIsTrackRecordingEmpty()) {
        Logger.i(TAG, "Saving track recording" + (sPendingName.isEmpty() ? "" : " as '" + sPendingName + "'"));
        TrackRecorder.nativeSaveTrackRecordingWithName(sPendingName);
      }
      TrackRecorder.nativeStopTrackRecording();
    }
    sDiscard = false;
    sPendingName = "";
  }

  @Override
  public void onTaskRemoved(@NonNull Intent rootIntent)
  {
    Logger.d(TAG, "Task removed, stopping service");
    stopSelf();
    super.onTaskRemoved(rootIntent);
  }

  @Override
  public int onStartCommand(@NonNull Intent intent, int flags, int startId)
  {
    if (!MwmApplication.from(this).getOrganicMaps().arePlatformAndCoreInitialized())
    {
      Logger.w(TAG, "Application is not initialized");
      stopSelf();
      return START_NOT_STICKY; // The service will be stopped by stopSelf().
    }

    if (!LocationUtils.checkFineLocationPermission(this))
    {
      // In a hypothetical scenario, the user could revoke location permissions after the app's process crashed,
      // but before the service with START_STICKY was restarted by the system.
      Logger.w(TAG, "Permission ACCESS_FINE_LOCATION is not granted, skipping TrackRecordingService");
      stopSelf();
      return START_NOT_STICKY; // The service will be stopped by stopSelf().
    }

    if (!TrackRecorder.nativeIsTrackRecordingEnabled())
    {
      Logger.i(TAG, "Service can't be started because Track Recorder is turned off in settings");
      stopSelf();
      return START_NOT_STICKY;
    }

    final String action = intent.getAction();
    if (action != null && STOP_TRACK_RECORDING.equals(action))
    {
      Logger.d(TAG, "Stop action received");
      saveAndStop();
      stopSelf();
      return START_NOT_STICKY;
    }

    Logger.i(TAG, "Starting Track Recording Foreground service");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
      ServiceCompat.startForeground(this, TrackRecordingService.TRACK_REC_NOTIFICATION_ID,
                                    getNotificationBuilder(this).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    else
      ServiceCompat.startForeground(this, TrackRecordingService.TRACK_REC_NOTIFICATION_ID,
                                    getNotificationBuilder(this).build(), 0);

    final LocationHelper locationHelper = MwmApplication.from(this).getLocationHelper();

    // Subscribe to location updates. This call is idempotent.
    locationHelper.addListener(this);

    if (sRecordingStartTime == 0)
      sRecordingStartTime = System.currentTimeMillis();

    // Restart the location with more frequent refresh interval for Track Recording.
    locationHelper.restartWithNewMode();
    WearSyncService.sendTrackRecordingStatus(this, true);

    return START_STICKY;
  }

  public NotificationCompat.Builder getWarningBuilder(Context context)
  {
    if (mWarningBuilder != null)
      return mWarningBuilder;

    mWarningBuilder =
        new NotificationCompat.Builder(context, TRACK_REC_CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.warning_icon)
            .setContentTitle(context.getString(R.string.current_location_unknown_error_title))
            .setContentText(context.getString(R.string.dialog_routing_location_turn_wifi))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.dialog_routing_location_turn_wifi)))
            .addAction(0, context.getString(R.string.navigation_stop_button), getExitPendingIntent(context))
            .setContentIntent(getPendingIntent(context))
            .setColor(ContextCompat.getColor(context, R.color.notification_warning));

    return mWarningBuilder;
  }

  @Override
  public void onLocationUpdateTimeout()
  {
    Logger.i(TAG, "Location update timeout");
    mWarningNotification = true;
    // post notification permission is not there but we will not stop the runnable because if
    // in between user gives permission then warning will not be updated until next restart
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && ActivityCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED)
      return;

    NotificationManagerCompat.from(this).notify(TRACK_REC_NOTIFICATION_ID, getWarningBuilder(this).build());
  }

  @Override
  public void onLocationUpdated(@NonNull Location location)
  {
    Logger.i(TAG, "Location is being updated in Track Recording service");

    // Accumulate live distance/duration ourselves from the location stream (the native stats listener
    // is unreliable here) so the watch can show recording stats. Duration is wall-clock from start.
    if (mLastLocation != null)
    {
      float seg = mLastLocation.distanceTo(location);
      if (seg > 0 && seg < 10000) // ignore GPS jumps
        sLength += seg;
    }
    mLastLocation = location;
    if (sRecordingStartTime > 0)
      sDuration = (System.currentTimeMillis() - sRecordingStartTime) / 1000.0;

    long now = System.currentTimeMillis();
    if (now - mLastWatchPushMs >= 2000)
    {
      mLastWatchPushMs = now;
      WearSyncService.sendTrackRecordingStatus(this, true);
    }

    if (mWarningNotification)
    {
      mWarningNotification = false;

      // post notification permission is not there but we will not stop the runnable because if
      // in between user gives permission then warning will not be updated until next restart
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
          && ActivityCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED)
        return;

      NotificationManagerCompat.from(this).notify(TRACK_REC_NOTIFICATION_ID, getNotificationBuilder(this).build());
    }
  }
}
