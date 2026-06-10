package app.organicmaps.wear;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.organicmaps.sync.ISyncLayer;

/**
 * Background service for the OSS flavor to handle incoming Bluetooth messages.
 */
public class BluetoothMessageListenerService extends Service implements ISyncLayer.MessageListener {
    private static final String TAG = "BluetoothMsgListener";

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        WearSyncService.addMessageListener(this);
    }

    @Override
    public void onDestroy() {
        WearSyncService.getSyncLayer().stop();
        super.onDestroy();
        WearSyncService.removeMessageListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        WearMessageRouter.onMessageReceived(this, path, data, sourceNodeId, null);
    }
}
