package app.organicmaps.wear;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import app.organicmaps.SplashActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.settings.RoadType;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import app.organicmaps.sync.GmsSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearMessageListenerService extends WearableListenerService implements ISyncLayer.MessageListener {
    private static final String TAG = "WearMessageListener";
    private static final int SEARCH_SELECT_MIN_SIZE = Double.BYTES * 2 + Integer.BYTES;
    private static final int MAP_TILE_REQUEST_SIZE = 8 + 8 * 4 + 4 + 4;

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

    @NonNull
    private WearMapTileRequestHandler getMapTileRequestHandler() {
        if (mMapTileRequestHandler == null) {
            mMapTileRequestHandler = new WearMapTileRequestHandler(this);
        }
        return mMapTileRequestHandler;
    }

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
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        WearSyncService.getSyncLayer().notifyMessageReceived(
            messageEvent.getPath(), messageEvent.getData(), messageEvent.getSourceNodeId());
    }

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEventBuffer) {
        super.onDataChanged(dataEventBuffer);
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED && event.getDataItem().getUri().getPath().equals("/preferences/watch")) {
                DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                long timestamp = dataMap.getLong("timestamp", 0);
                boolean mapEnabled = dataMap.getBoolean("mapEnabled", false);
                boolean watchLocalMode = dataMap.getBoolean("watchLocalMode", false);
                boolean standaloneMode = dataMap.getBoolean("standaloneMode", false);
                boolean autoDownload = dataMap.getBoolean("autoDownloadRouteMaps", true);
                String backend = dataMap.getString("backend", "GMS");
                String mapDownloadMode = dataMap.getString("mapDownloadMode", "BLUETOOTH_ONLY");
                int poiMask = dataMap.getInt("poiCategoriesMask", 0x3F);
                
                boolean is3dEnabled = dataMap.getBoolean("is3dEnabled", true);
                boolean is3dBuildingsEnabled = dataMap.getBoolean("is3dBuildingsEnabled", true);
                boolean isAutoZoomEnabled = dataMap.getBoolean("isAutoZoomEnabled", true);
                int mUnits = dataMap.getInt("measurementUnits", 0);
                String mapStyle = dataMap.getString("mapStyle", "default");

                boolean avoidTolls = dataMap.getBoolean("avoidTolls", false);
                boolean avoidMotorways = dataMap.getBoolean("avoidMotorways", false);
                boolean avoidFerries = dataMap.getBoolean("avoidFerries", false);
                boolean avoidUnpaved = dataMap.getBoolean("avoidUnpaved", false);

                mMainHandler.post(() -> {
                    android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
                    long lastApplied = prefs.getLong("pref_wear_os_last_sync_timestamp", 0);
                    Log.d(TAG, "Watch pref update received. Timestamp: " + timestamp + ", LastApplied: " + lastApplied);
                    if (timestamp > 0 && timestamp < lastApplied) return;

                    boolean changed = prefs.getBoolean(getString(R.string.pref_wear_os_map_enabled), false) != mapEnabled ||
                            prefs.getBoolean(getString(R.string.pref_wear_os_watch_local_mode), false) != watchLocalMode ||
                            prefs.getBoolean(getString(R.string.pref_wear_os_standalone_mode), false) != standaloneMode ||
                            !prefs.getString(getString(R.string.pref_wear_os_backend), "GMS").equals(backend) ||
                            !prefs.getString(getString(R.string.pref_wear_os_map_download_mode), "BLUETOOTH_ONLY").equals(mapDownloadMode) ||
                            prefs.getInt("poiCategoriesMask", 0x3F) != poiMask ||
                            prefs.getBoolean(getString(R.string.pref_3d), true) != is3dEnabled ||
                            prefs.getBoolean(getString(R.string.pref_3d_buildings), true) != is3dBuildingsEnabled ||
                            prefs.getBoolean(getString(R.string.pref_auto_zoom), true) != isAutoZoomEnabled ||
                            !prefs.getString(getString(R.string.pref_munits), "0").equals(String.valueOf(mUnits)) ||
                            !prefs.getString(getString(R.string.pref_map_style), "default").equals(mapStyle) ||
                            prefs.getBoolean("avoid_tolls", false) != avoidTolls ||
                            prefs.getBoolean("avoid_motorways", false) != avoidMotorways ||
                            prefs.getBoolean("avoid_ferries", false) != avoidFerries ||
                            prefs.getBoolean("avoid_dirty_roads", false) != avoidUnpaved;

                    if (!changed && timestamp > 0 && timestamp == lastApplied) return;

                    Log.d(TAG, "Applying watch preferences. Changed=" + changed);
                    prefs.edit()
                        .putLong("pref_wear_os_last_sync_timestamp", timestamp)
                        .putBoolean(getString(R.string.pref_wear_os_map_enabled), mapEnabled)
                        .putBoolean(getString(R.string.pref_wear_os_watch_local_mode), watchLocalMode)
                        .putBoolean(getString(R.string.pref_wear_os_standalone_mode), standaloneMode)
                        .putString(getString(R.string.pref_wear_os_backend), backend)
                        .putString(getString(R.string.pref_wear_os_map_download_mode), mapDownloadMode)
                        .putInt("poiCategoriesMask", poiMask)
                        .putBoolean(getString(R.string.pref_3d), is3dEnabled)
                        .putBoolean(getString(R.string.pref_3d_buildings), is3dBuildingsEnabled)
                        .putBoolean(getString(R.string.pref_auto_zoom), isAutoZoomEnabled)
                        .putString(getString(R.string.pref_munits), String.valueOf(mUnits))
                        .putString(getString(R.string.pref_map_style), mapStyle)
                        .putBoolean("avoid_tolls", avoidTolls)
                        .putBoolean("avoid_motorways", avoidMotorways)
                        .putBoolean("avoid_ferries", avoidFerries)
                        .putBoolean("avoid_dirty_roads", avoidUnpaved)
                        .apply();
                    
                    if (avoidTolls) RoutingOptions.addOption(RoadType.Toll); else RoutingOptions.removeOption(RoadType.Toll);
                    if (avoidMotorways) RoutingOptions.addOption(RoadType.Motorway); else RoutingOptions.removeOption(RoadType.Motorway);
                    if (avoidFerries) RoutingOptions.addOption(RoadType.Ferry); else RoutingOptions.removeOption(RoadType.Ferry);
                    if (avoidUnpaved) RoutingOptions.addOption(RoadType.Dirty); else RoutingOptions.removeOption(RoadType.Dirty);

                    WearSyncService.initSyncLayer(this);
                    
                    if (changed) {
                        // Notify UI to refresh
                        Intent intent = new Intent("app.organicmaps.wear.SETTINGS_CHANGED");
                        sendBroadcast(intent);
                    }
                });
            }
        }
    }

    @Override
    public void onMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        Log.d(TAG, "onMessageReceived: " + path);
        switch (path) {
            case PATH_STOP_NAVIGATION:
                mMainHandler.post(() -> {
                    Log.d(TAG, "Stopping navigation per watch request");
                    RoutingController.get().cancel();
                    app.organicmaps.routing.NavigationService.stopService(this);
                });
                break;
            case PATH_SEARCH_QUERY: {
                String query = new String(data, StandardCharsets.UTF_8);
                mMainHandler.post(() -> {
                    Log.d(TAG, "Starting headless search for: " + query);
                    HeadlessSearchInteractor.getInstance(this).startSearch(query);
                });
                break;
            }
            case PATH_SEARCH_SELECT: {
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
                break;
            }
            case PATH_SEARCH_HISTORY_REQUEST:
                mMainHandler.post(() -> {
                    Log.d(TAG, "Sending search history to watch");
                    WearSyncService.sendSearchHistory(getApplicationContext());
                });
                break;
            case PATH_MAP_TILE_REQUEST: {
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
                int poiCategoriesMask = buffer.remaining() >= 4 ? buffer.getInt() : 0;

                mMainHandler.post(() -> getMapTileRequestHandler().handle(
                    sourceNodeId, requestId, minLat, minLon, maxLat, maxLon, routerType, poiCategoriesMask));
                break;
            }
            case PATH_PING:
                Log.d(TAG, "Ping received from " + sourceNodeId);
                WearSyncService.getSyncLayer().sendPong(getApplicationContext(), sourceNodeId);
                break;
            case PATH_PREFERENCES_REQUEST:
                Log.d(TAG, "Watch requested settings sync");
                WearSyncService.getSyncLayer().syncPreferences(getApplicationContext());
                break;
            case PATH_START_NAVIGATION_REQUEST:
                mMainHandler.post(() -> {
                    Log.d(TAG, "Watch requested to start navigation");
                    RoutingController.get().start();
                });
                break;
            case "/preferences/watch":
                Log.d(TAG, "Watch sent preferences update");
                mMainHandler.post(() -> {
                    android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
                    
                    // We don't have a direct "parse" that returns if it changed, 
                    // but we can check before/after or just broadcast only if it's likely a user change.
                    // For now, let's just use the same logic as data changed.
                    
                    String oldBackend = prefs.getString(getString(R.string.pref_wear_os_backend), "GMS");
                    
                    WearSyncService.getSyncLayer().parsePreferences(this, data, prefs);
                    WearSyncService.initSyncLayer(this);
                    
                    String newBackend = prefs.getString(getString(R.string.pref_wear_os_backend), "GMS");
                    
                    // Notify UI to refresh
                    Intent intent = new Intent("app.organicmaps.wear.SETTINGS_CHANGED");
                    sendBroadcast(intent);
                });
                break;
        }
    }
}
