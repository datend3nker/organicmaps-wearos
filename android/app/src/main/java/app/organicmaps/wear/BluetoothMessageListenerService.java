package app.organicmaps.wear;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sync.ISyncLayer;

/**
 * Background service for the OSS flavor to handle incoming Bluetooth messages.
 */
public class BluetoothMessageListenerService extends Service implements ISyncLayer.MessageListener {
    private static final String TAG = "BluetoothMsgListener";
    private static final int SEARCH_SELECT_MIN_SIZE = 8 * 2 + 4;
    private static final int MAP_TILE_REQUEST_SIZE = 8 + 8 * 4 + 4;

    private static final String PATH_STOP_NAVIGATION = "/navigation/stop";
    private static final String PATH_SEARCH_QUERY = "/search/query";
    private static final String PATH_SEARCH_SELECT = "/search/select";
    private static final String PATH_SEARCH_HISTORY_REQUEST = "/search/history/request";
    private static final String PATH_MAP_TILE_REQUEST = "/map/tile/request";
    private static final String PATH_PING = "/ping";
    private static final String PATH_PREFERENCES_REQUEST = "/preferences/request";
    private static final String PATH_START_NAVIGATION_REQUEST = "/navigation/start/request";

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private WearMapTileRequestHandler mMapTileRequestHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        WearSyncService.addMessageListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        WearSyncService.removeMessageListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        Log.d(TAG, "onMessageReceived: " + path);
        if (path.equals("/preferences/watch")) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            boolean mapEnabled = buffer.get() == 1;
            buffer.get(); // skip legacy forceOffline byte
            boolean watchLocalMode = buffer.get() == 1;
            boolean standaloneMode = buffer.get() == 1;
            boolean autoDownload = true;
            if (buffer.remaining() > 0) autoDownload = buffer.get() == 1;
            
            int bLen = buffer.remaining() >= 4 ? buffer.getInt() : 0;
            String backendStr = "GMS";
            if (bLen > 0 && buffer.remaining() >= bLen) {
                byte[] b = new byte[bLen];
                buffer.get(b);
                backendStr = new String(b, StandardCharsets.UTF_8);
            }
            
            String downloadModeStr = "BLUETOOTH_ONLY";
            int dLen = buffer.remaining() >= 4 ? buffer.getInt() : 0;
            if (dLen > 0 && buffer.remaining() >= dLen) {
                byte[] d = new byte[dLen];
                buffer.get(d);
                downloadModeStr = new String(d, StandardCharsets.UTF_8);
            }
            
            final boolean finalMapEnabled = mapEnabled;
            final boolean finalWatchLocalMode = watchLocalMode;
            final boolean finalStandaloneMode = standaloneMode;
            final String finalBackend = backendStr;
            final String finalDownloadMode = downloadModeStr;
            mMainHandler.post(() -> {
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit()
                    .putBoolean(getString(app.organicmaps.R.string.pref_wear_os_map_enabled), finalMapEnabled)
                    .putBoolean(getString(app.organicmaps.R.string.pref_wear_os_watch_local_mode), finalWatchLocalMode)
                    .putBoolean(getString(app.organicmaps.R.string.pref_wear_os_standalone_mode), finalStandaloneMode)
                    .putString(getString(app.organicmaps.R.string.pref_wear_os_backend), finalBackend)
                    .putString(getString(app.organicmaps.R.string.pref_wear_os_map_download_mode), finalDownloadMode)
                    .apply();
                WearSyncService.initSyncLayer(this);
            });
            return;
        }

        switch (path) {
            case PATH_STOP_NAVIGATION -> mMainHandler.post(() -> {
                Log.d(TAG, "Stopping navigation per watch request");
                RoutingController.get().cancel();
                app.organicmaps.routing.NavigationService.stopService(this);
            });
            case PATH_SEARCH_QUERY -> {
                String query = new String(data, StandardCharsets.UTF_8);
                mMainHandler.post(() -> {
                    Log.d(TAG, "Starting headless search for: " + query);
                    HeadlessSearchInteractor.getInstance(this).startSearch(query);
                });
            }
            case PATH_SEARCH_SELECT -> {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                if (buffer.remaining() < SEARCH_SELECT_MIN_SIZE) {
                    Log.w(TAG, "Malformed search select payload.");
                    return;
                }

                double lat = buffer.getDouble();
                double lon = buffer.getDouble();
                int routerType = buffer.getInt();
                byte[] nameBytes = new byte[buffer.remaining()];
                buffer.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                mMainHandler.post(() -> {
                    Log.d(TAG, "Watch selected: " + name + " (" + lat + ", " + lon + ") Mode: " + routerType);
                    HeadlessRouteInteractor.getInstance(this).planRoute(lat, lon, routerType, name);
                });
            }
            case PATH_SEARCH_HISTORY_REQUEST -> mMainHandler.post(() -> {
                Log.d(TAG, "Sending search history to watch");
                WearSyncService.sendSearchHistory(getApplicationContext());
            });
            case PATH_MAP_TILE_REQUEST -> {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                if (buffer.remaining() < MAP_TILE_REQUEST_SIZE) {
                    Log.w(TAG, "Malformed map tile request payload.");
                    return;
                }

                long requestId = buffer.getLong();
                double minLat = buffer.getDouble();
                double minLon = buffer.getDouble();
                double maxLat = buffer.getDouble();
                double maxLon = buffer.getDouble();
                int routerType = buffer.getInt();

                mMainHandler.post(() -> {
                    if (mMapTileRequestHandler == null) mMapTileRequestHandler = new WearMapTileRequestHandler(this);
                    mMapTileRequestHandler.handle(sourceNodeId, requestId, minLat, minLon, maxLat, maxLon, routerType);
                });
            }
            case PATH_PING -> {
                Log.d(TAG, "Ping received from " + sourceNodeId);
                WearSyncService.getSyncLayer().sendPong(getApplicationContext(), sourceNodeId);
            }
            case PATH_PREFERENCES_REQUEST -> {
                Log.d(TAG, "Watch requested settings sync");
                WearSyncService.getSyncLayer().syncPreferences(getApplicationContext());
            }
            case PATH_START_NAVIGATION_REQUEST -> mMainHandler.post(() -> {
                Log.d(TAG, "Watch requested to start navigation");
                RoutingController.get().start();
            });
        }
    }
}
