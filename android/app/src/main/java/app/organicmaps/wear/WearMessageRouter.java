package app.organicmaps.wear;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.location.TrackRecordingService;
import app.organicmaps.sync.BluetoothSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearMessageRouter {
    private static final String TAG = "WearMessageRouter";
    private static final int SEARCH_SELECT_MIN_SIZE = 8 * 2 + 4;

    private static final String PATH_STOP_NAVIGATION = "/navigation/stop";
    private static final String PATH_SEARCH_QUERY = "/search/query";
    private static final String PATH_SEARCH_SELECT = "/search/select";
    private static final String PATH_SEARCH_HISTORY_REQUEST = "/search/history/request";
    private static final String PATH_PING = "/ping";
    private static final String PATH_PREFERENCES_REQUEST = "/preferences/request";
    private static final String PATH_START_NAVIGATION_REQUEST = "/navigation/start/request";
    private static final String PATH_TRACK_RECORDING_TOGGLE = "/track/recording/toggle";
    private static final String PATH_BOOKMARK_VISIBLE_TOGGLE = "/bookmark/visible/toggle";
    private static final String PATH_BOOKMARK_SYNC_REQUEST = "/bookmark/sync/request";
    private static final String PATH_BOOKMARKS_REQUEST = "/bookmarks/request";
    private static final String PATH_BOOKMARK_SHOW = "/bookmark/show";
    private static final String PATH_BOOKMARK_UPDATE = "/bookmark/update";
    private static final String PATH_MAP_DOWNLOAD_REQUEST = "/map/download/request";
    private static final String PATH_VIRTUAL_MWM_REQUEST = "/virtual_mwm/request";
    private static final String PATH_VIRTUAL_MWM_METADATA_REQUEST = "/virtual_mwm/metadata_request";
    private static final String PATH_MAP_PROGRESS = "/map/download/progress";
    private static final String PATH_SEARCH_HISTORY_SYNC = "/search/history/sync";

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static long sLastMsgTime = 0;
    private static int sLastMsgHash = 0;

    public static void onMessageReceived(@NonNull Context context, @NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        int hash = path.hashCode() ^ java.util.Arrays.hashCode(data);
        long now = System.currentTimeMillis();
        // Robust deduplication (500ms)
        if (hash == sLastMsgHash && (now - sLastMsgTime) < 500) {
            return;
        }
        sLastMsgHash = hash;
        sLastMsgTime = now;

        Log.d(TAG, "DEBUG_WEAR: Routing message: " + path);
        switch (path) {
            case PATH_STOP_NAVIGATION:
                sMainHandler.post(() -> {
                    Log.d(TAG, "Stopping navigation per watch request");
                    RoutingController.get().cancel();
                    app.organicmaps.routing.NavigationService.stopService(context);
                });
                break;
            case PATH_SEARCH_QUERY: {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                double lat = 0;
                double lon = 0;
                String query;
                if (buffer.remaining() >= 16) {
                    lat = buffer.getDouble();
                    lon = buffer.getDouble();
                    byte[] queryBytes = new byte[buffer.remaining()];
                    buffer.get(queryBytes);
                    query = new String(queryBytes, StandardCharsets.UTF_8);
                } else {
                    query = new String(data, StandardCharsets.UTF_8);
                }
                final double finalLat = lat;
                final double finalLon = lon;
                final String finalQuery = query;
                sMainHandler.post(() -> {
                    Log.d(TAG, "Starting headless search for: " + finalQuery + " at " + finalLat + ", " + finalLon);
                    HeadlessSearchInteractor.getInstance(context).startSearch(finalQuery, finalLat, finalLon);
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

                sMainHandler.post(() -> {
                    Log.d(TAG, "Watch selected: " + name + " (" + lat + ", " + lon + ") Mode: " + routerType);
                    app.organicmaps.sdk.search.SearchRecents.add(name, context);
                    HeadlessRouteInteractor.getInstance(context).planRoute(lat, lon, routerType, name);
                });
                break;
            }
            case PATH_SEARCH_HISTORY_REQUEST:
                sMainHandler.post(() -> {
                    Log.d(TAG, "Sending search history to watch");
                    WearSyncService.sendSearchHistory(context.getApplicationContext());
                });
                break;
            case PATH_SEARCH_HISTORY_SYNC:
                sMainHandler.post(() -> {
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    if (buffer.remaining() < 4) return;
                    int count = buffer.getInt();
                    for (int i = 0; i < count; i++) {
                        int len = buffer.getInt();
                        if (buffer.remaining() < len) break;
                        byte[] b = new byte[len];
                        buffer.get(b);
                        String q = new String(b, StandardCharsets.UTF_8);
                        app.organicmaps.sdk.search.SearchRecents.add(q, context);
                    }
                });
                break;
            case PATH_PING:
                Log.d(TAG, "Ping received from " + sourceNodeId);
                WearSyncService.getSyncLayer().sendPong(context.getApplicationContext(), sourceNodeId);
                break;
            case PATH_PREFERENCES_REQUEST:
                Log.d(TAG, "Watch requested settings sync");
                WearSyncService.getSyncLayer().syncPreferences(context.getApplicationContext());
                break;
            case PATH_START_NAVIGATION_REQUEST:
                sMainHandler.post(() -> {
                    Log.d(TAG, "Watch requested to start navigation");
                    RoutingController.get().start();
                });
                break;
            case PATH_TRACK_RECORDING_TOGGLE:
                sMainHandler.post(() -> {
                    boolean isRecording = app.organicmaps.sdk.location.TrackRecorder.nativeIsTrackRecordingEnabled();
                    Log.d(TAG, "Watch toggling track recording. Current: " + isRecording);
                    if (isRecording) {
                        TrackRecordingService.stopService(context);
                    } else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        TrackRecordingService.startForegroundService(context);
                    } else {
                        Log.w(TAG, "Cannot start track recording: permission missing");
                    }
                });
                break;
            case PATH_BOOKMARKS_REQUEST:
                sMainHandler.post(() -> {
                    Log.d(TAG, "Watch requested bookmark categories");
                    WearSyncService.sendBookmarkCategories(context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());
                });
                break;
            case PATH_BOOKMARK_SHOW: {
                long bmkId = ByteBuffer.wrap(data).getLong();
                sMainHandler.post(() -> {
                    Log.d(TAG, "Watch requested to show bookmark: " + bmkId);
                    app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.showBookmarkOnMap(bmkId);
                    // Also bring app to foreground if needed
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                    }
                });
                break;
            }
            case PATH_BOOKMARK_UPDATE: {
                ByteBuffer updateBuf = ByteBuffer.wrap(data);
                long updateBmkId = updateBuf.getLong();
                int nameLen = updateBuf.getInt();
                byte[] nameB = new byte[nameLen];
                updateBuf.get(nameB);
                String name = new String(nameB, StandardCharsets.UTF_8);
                int color = updateBuf.getInt();
                sMainHandler.post(() -> {
                    Log.d(TAG, "Watch requested to update bookmark: " + updateBmkId + " name: " + name + " color: " + color);
                    app.organicmaps.sdk.bookmarks.data.BookmarkInfo info = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getBookmarkInfo(updateBmkId);
                    if (info != null) {
                        info.update(name, new app.organicmaps.sdk.bookmarks.data.Icon(color, 0), "");
                    }
                });
                break;
            }
            case PATH_BOOKMARK_VISIBLE_TOGGLE:
                long catId = ByteBuffer.wrap(data).getLong();
                sMainHandler.post(() -> {
                    app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategoryById(catId);
                    if (cat != null) {
                        Log.d(TAG, "Watch toggling visibility for cat: " + catId);
                        cat.toggleVisibility();
                    } else {
                        Log.w(TAG, "Watch requested toggle for unknown cat: " + catId);
                    }
                });
                break;
            case PATH_BOOKMARK_SYNC_REQUEST:
                long syncCatId = ByteBuffer.wrap(data).getLong();
                sMainHandler.post(() -> {
                    Log.d(TAG, "Watch requested sync for cat: " + syncCatId);
                    app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.prepareCategoriesForSharing(
                            new long[]{syncCatId}, app.organicmaps.sdk.bookmarks.data.KmlFileType.Binary);
                });
                break;
            case PATH_MAP_DOWNLOAD_REQUEST:
                String mapId = new String(data, StandardCharsets.UTF_8);
                Log.d(TAG, "Watch requested map streaming: " + mapId);
                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        WearMapStreamingHelper.streamMapToWatch(context, sourceNodeId, mapId);
                    });
                });
                break;
            case "/map/download/cancel":
                String cancelMapId = new String(data, StandardCharsets.UTF_8);
                Log.d(TAG, "Watch requested to cancel map streaming: " + cancelMapId);
                ISyncLayer syncLayer = WearSyncService.getSyncLayer();
                if (syncLayer instanceof BluetoothSyncLayer) {
                    ((BluetoothSyncLayer) syncLayer).cancelStreaming(cancelMapId);
                }
                break;
            case PATH_MAP_PROGRESS:
                sMainHandler.post(() -> {
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    if (buffer.remaining() < 8) return;
                    int countryLen = buffer.getInt();
                    if (buffer.remaining() < countryLen + 4) return;
                    byte[] cBytes = new byte[countryLen];
                    buffer.get(cBytes);
                    String countryId = new String(cBytes, StandardCharsets.UTF_8);
                    int progress = buffer.getInt();
                    Log.d(TAG, "Watch map progress: " + countryId + " -> " + progress + "%");
                    // We can reuse the serving notification for phone-initiated streams, 
                    // or show a new one if it's a watch-initiated download progress.
                    WearServantNotificationManager.showServingNotification(context, countryId, progress);
                });
                break;
            case PATH_VIRTUAL_MWM_METADATA_REQUEST: {
                String mwmName = new String(data, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        long size = app.organicmaps.sdk.Framework.nativeGetMwmSize(mwmName);
                        Log.d(TAG, "Watch requested metadata for: " + mwmName + " size: " + size);
                        if (size > 0) {
                            WearSyncService.getSyncLayer().sendMwmMetadata(context.getApplicationContext(), mwmName, size);
                        }
                    });
                });
                break;
            }
            case PATH_VIRTUAL_MWM_REQUEST: {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                int nameLen = buffer.getInt();
                byte[] nameBytes = new byte[nameLen];
                buffer.get(nameBytes);
                String mwmName = new String(nameBytes, StandardCharsets.UTF_8);
                long offset = buffer.getLong();
                int size = buffer.getInt();

                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        byte[] mwmData = app.organicmaps.sdk.Framework.nativeGetMwmBytes(mwmName, offset, size);
                        if (mwmData != null) {
                            Log.d(TAG, "Sending MWM bytes: " + mwmName + " offset: " + offset + " size: " + mwmData.length);
                            WearSyncService.getSyncLayer().sendMwmBytes(context.getApplicationContext(), mwmName, offset, mwmData);
                        }
                    });
                });
                break;
            }
            case "/preferences/watch":
                Log.d(TAG, "Watch sent preferences update");
                sMainHandler.post(() -> {
                    android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                    WearSyncService.getSyncLayer().parsePreferences(context, data, prefs);
                    WearSyncService.initSyncLayer(context);
                    
                    // Notify UI to refresh
                    Intent intent = new Intent("app.organicmaps.wear.SETTINGS_CHANGED");
                    context.sendBroadcast(intent);
                });
                break;
        }
    }

    private static void ensureFrameworkInitialized(Context context, Runnable onReady) {
        app.organicmaps.MwmApplication mwmApp = app.organicmaps.MwmApplication.from(context);
        if (mwmApp.getOrganicMaps().arePlatformAndCoreInitialized()) {
            onReady.run();
            return;
        }
        try {
            Log.d(TAG, "Initializing framework for virtual MWM request...");
            boolean asyncInit = mwmApp.initOrganicMaps(() -> {
                Log.d(TAG, "Framework initialized for virtual MWM.");
                onReady.run();
            });
            if (!asyncInit) {
                onReady.run();
            }
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to initialize Organic Maps", e);
        }
    }
}
