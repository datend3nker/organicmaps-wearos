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
    private static final String PATH_BOOKMARK_RENAME = "/bookmark/rename";
    private static final String PATH_BOOKMARK_DELETE = "/bookmark/delete";
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
            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Message IGNORED (Deduplication): " + path);
            return;
        }
        sLastMsgHash = hash;
        sLastMsgTime = now;

        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Routing message: " + path + " from " + sourceNodeId + " (payload=" + data.length + " bytes)");
        switch (path) {
            case PATH_STOP_NAVIGATION:
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Stopping navigation per watch request");
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
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Starting headless search for: " + finalQuery + " at " + finalLat + ", " + finalLon);
                    HeadlessSearchInteractor.getInstance(context).startSearch(finalQuery, finalLat, finalLon);
                });
                break;
            }
            case PATH_SEARCH_SELECT: {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                if (buffer.remaining() < SEARCH_SELECT_MIN_SIZE) {
                    Log.w(TAG, "DEBUG_WEAR_PIPELINE: Malformed search select payload.");
                    return;
                }

                double lat = buffer.getDouble();
                double lon = buffer.getDouble();
                int routerType = buffer.getInt();
                byte[] nameBytes = new byte[buffer.remaining()];
                buffer.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch selected result: " + name + " (" + lat + ", " + lon + ") Mode: " + routerType);
                    app.organicmaps.sdk.search.SearchRecents.add(name, context);
                    HeadlessRouteInteractor.getInstance(context).planRoute(lat, lon, routerType, name);
                });
                break;
            }
            case PATH_SEARCH_HISTORY_REQUEST:
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Sending search history to watch");
                    WearSyncService.sendSearchHistory(context.getApplicationContext());
                });
                break;
            case PATH_SEARCH_HISTORY_SYNC:
                sMainHandler.post(() -> {
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    if (buffer.remaining() < 4) return;
                    int count = buffer.getInt();
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Receiving search history sync. Items: " + count);
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
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Ping received from " + sourceNodeId);
                WearSyncService.getSyncLayer().sendPong(context.getApplicationContext(), sourceNodeId);
                break;
            case PATH_PREFERENCES_REQUEST:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested settings sync");
                WearSyncService.getSyncLayer().syncPreferences(context.getApplicationContext());
                break;
            case PATH_START_NAVIGATION_REQUEST:
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to start navigation");
                    RoutingController.get().start();
                });
                break;
            case PATH_TRACK_RECORDING_TOGGLE:
                sMainHandler.post(() -> {
                    boolean isRecording = app.organicmaps.sdk.location.TrackRecorder.nativeIsTrackRecordingEnabled();
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch toggling track recording. Current: " + isRecording);
                    if (isRecording) {
                        TrackRecordingService.stopService(context);
                    } else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        TrackRecordingService.startForegroundService(context);
                    } else {
                        Log.w(TAG, "DEBUG_WEAR_PIPELINE: Cannot start track recording: permission missing");
                    }
                });
                break;
            case PATH_BOOKMARKS_REQUEST:
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested bookmark categories");
                    ensureFrameworkInitialized(context, () -> {
                        WearSyncService.sendBookmarkCategories(context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());
                    });
                });
                break;
            case PATH_BOOKMARK_SHOW: {
                long bmkId = ByteBuffer.wrap(data).getLong();
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to show bookmark: " + bmkId);
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
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to update bookmark: " + updateBmkId + " name: " + name + " color: " + color);
                    app.organicmaps.sdk.bookmarks.data.BookmarkInfo info = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getBookmarkInfo(updateBmkId);
                    if (info != null) {
                        info.update(name, new app.organicmaps.sdk.bookmarks.data.Icon(color, 0), "");
                    }
                });
                break;
            }
            case PATH_BOOKMARK_VISIBLE_TOGGLE:
                String catName = new String(data, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                        if (cat.getName().equals(catName)) {
                            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch toggling visibility for cat: " + catName);
                            cat.toggleVisibility();
                            return;
                        }
                    }
                    Log.w(TAG, "DEBUG_WEAR_PIPELINE: Watch requested toggle for unknown cat: " + catName);
                });
                break;
            case PATH_BOOKMARK_SYNC_REQUEST:
                String syncCatName = new String(data, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested sync for cat: " + syncCatName);
                    ensureFrameworkInitialized(context, () -> {
                        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                            if (cat.getName().equals(syncCatName)) {
                                app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.prepareCategoriesForSharing(
                                        new long[]{cat.getId()}, app.organicmaps.sdk.bookmarks.data.KmlFileType.Text);
                                return;
                            }
                        }
                    });
                });
                break;
            case PATH_BOOKMARK_RENAME: {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                if (buffer.remaining() < 4) return;
                int oldNameLen = buffer.getInt();
                if (buffer.remaining() < oldNameLen + 4) return;
                byte[] oldBytes = new byte[oldNameLen];
                buffer.get(oldBytes);
                String oldName = new String(oldBytes, StandardCharsets.UTF_8);
                int newNameLen = buffer.getInt();
                if (buffer.remaining() < newNameLen) return;
                byte[] newBytes = new byte[newNameLen];
                buffer.get(newBytes);
                String newName = new String(newBytes, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to rename cat: " + oldName + " to " + newName);
                    for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                        if (cat.getName().equals(oldName)) {
                            cat.setName(newName);
                            return;
                        }
                    }
                });
                break;
            }
            case PATH_BOOKMARK_DELETE: {
                String delName = new String(data, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to delete cat: " + delName);
                    for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                        if (cat.getName().equals(delName)) {
                            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.deleteCategory(cat.getId());
                            return;
                        }
                    }
                });
                break;
            }
            case PATH_MAP_DOWNLOAD_REQUEST:
                String mapId = new String(data, StandardCharsets.UTF_8);
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested map streaming: " + mapId);
                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        WearMapStreamingHelper.streamMapToWatch(context, sourceNodeId, mapId);
                    });
                });
                break;
            case "/map/download/cancel":
                String cancelMapId = new String(data, StandardCharsets.UTF_8);
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to cancel map streaming: " + cancelMapId);
                WearSyncService.getSyncLayer().cancelStreaming(cancelMapId);
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
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch map progress received: " + countryId + " -> " + progress + "%");
                    // We can reuse the serving notification for phone-initiated streams, 
                    // or show a new one if it's a watch-initiated download progress.
                    WearCompanionNotificationManager.showServingNotification(context, countryId, progress);
                });
                break;
            case PATH_VIRTUAL_MWM_METADATA_REQUEST: {
                String mwmName = new String(data, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        long size = app.organicmaps.sdk.Framework.nativeGetMwmSize(mwmName);
                        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested metadata for: " + mwmName + " size: " + size);
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
                        // Stability: Cap size to stay within transport limits (GMS: ~100KB)
                        int safeSize = Math.min(size, 85 * 1024); 
                        byte[] mwmData = app.organicmaps.sdk.Framework.nativeGetMwmBytes(mwmName, offset, safeSize);
                        if (mwmData != null) {
                            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Sending MWM bytes: " + mwmName + " offset: " + offset + " size: " + mwmData.length + " (requested: " + size + ")");
                            // Optimization: ISyncLayer implementations will handle compression if beneficial
                            WearSyncService.getSyncLayer().sendMwmBytes(context.getApplicationContext(), mwmName, offset, mwmData);
                        } else {
                            Log.w(TAG, "DEBUG_WEAR_PIPELINE: Failed to get MwmBytes for: " + mwmName + " at " + offset);
                            // We don't send a response, watch has a timeout to clear pending state
                        }
                    });
                });
                break;
            }
            case "/preferences/watch":
            case "/preferences/updates":
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch sent preferences update: " + path);
                sMainHandler.post(() -> {
                    android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                    WearSyncService.getSyncLayer().parsePreferences(context, data, prefs);
                    
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
