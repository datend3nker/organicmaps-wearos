package app.organicmaps.wear;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.location.TrackRecordingService;
import app.organicmaps.sync.BluetoothSyncLayer;
import app.organicmaps.sync.ISyncLayer;
import app.organicmaps.sdk.sync.WearProtocol;
import app.organicmaps.sdk.sync.WearProtocolDataConverter;
import app.organicmaps.sdk.util.ChecksumUtils;

public class WearMessageRouter {
    private static final String TAG = "WearMessageRouter";
    private static final int SEARCH_SELECT_MIN_SIZE = 8 * 2 + 4;
    private static final long DEDUPLICATION_WINDOW_SEARCH_MS = 200;
    private static final long DEDUPLICATION_WINDOW_DEFAULT_MS = 500;

    private static final String PATH_STOP_NAVIGATION = WearProtocol.PATH_NAVIGATION_STOP;
    private static final String PATH_SEARCH_QUERY = WearProtocol.PATH_SEARCH_QUERY;
    private static final String PATH_SEARCH_SELECT = WearProtocol.PATH_SEARCH_SELECT;
    private static final String PATH_SEARCH_HISTORY_REQUEST = WearProtocol.PATH_SEARCH_HISTORY_REQUEST;
    private static final String PATH_PING = WearProtocol.PATH_PING;
    private static final String PATH_PREFERENCES_REQUEST = WearProtocol.PATH_PREFERENCES_REQUEST;
    private static final String PATH_START_NAVIGATION_REQUEST = WearProtocol.PATH_NAVIGATION_START;
    private static final String PATH_TRACK_RECORDING_TOGGLE = WearProtocol.PATH_TRACK_RECORDING_TOGGLE;
    private static final String PATH_BOOKMARK_VISIBLE_TOGGLE = WearProtocol.PATH_BOOKMARK_VISIBLE_TOGGLE;
    private static final String PATH_BOOKMARK_SYNC_REQUEST = WearProtocol.PATH_BOOKMARK_SYNC_REQUEST;
    private static final String PATH_BOOKMARKS_REQUEST = WearProtocol.PATH_BOOKMARKS_REQUEST;
    private static final String PATH_BOOKMARK_SHOW = WearProtocol.PATH_BOOKMARK_SHOW;
    private static final String PATH_BOOKMARK_UPDATE = WearProtocol.PATH_BOOKMARK_UPDATE;
    private static final String PATH_BOOKMARK_RENAME = WearProtocol.PATH_BOOKMARK_RENAME;
    private static final String PATH_BOOKMARK_DELETE = WearProtocol.PATH_BOOKMARK_DELETE;
    private static final String PATH_BOOKMARK_CATEGORY_CREATE = WearProtocol.PATH_BOOKMARK_CATEGORY_CREATE;
    private static final String PATH_MAP_DOWNLOAD_REQUEST = WearProtocol.PATH_MAP_DOWNLOAD_REQUEST;
    private static final String PATH_DOWNLOADED_MAPS_REQUEST = WearProtocol.PATH_MAP_PHONE_DOWNLOADED_REQUEST;
    private static final String PATH_VIRTUAL_MWM_REQUEST = WearProtocol.PATH_VIRTUAL_MWM_REQUEST;
    private static final String PATH_VIRTUAL_MWM_METADATA_REQUEST = WearProtocol.PATH_VIRTUAL_MWM_METADATA_REQUEST;
    private static final String PATH_MAP_PROGRESS = WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS;
    private static final String PATH_SEARCH_HISTORY_SYNC = WearProtocol.PATH_SEARCH_HISTORY_SYNC;
    private static final String PATH_HANDSHAKE = WearProtocol.PATH_HANDSHAKE;
    private static final String PATH_SEARCH_RESULTS = WearProtocol.PATH_SEARCH_RESULTS;
    private static final String PATH_SEARCH_HISTORY = WearProtocol.PATH_SEARCH_HISTORY;
    private static final String PATH_BOOKMARKS = WearProtocol.PATH_BOOKMARKS;
    private static final String PATH_BOOKMARKS_METADATA = WearProtocol.PATH_BOOKMARKS_METADATA;
    private static final String PATH_BOOKMARK_FILE = WearProtocol.PATH_BOOKMARK_FILE;
    private static final String PATH_BACKEND_SWITCH = WearProtocol.PATH_BACKEND_SWITCH;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private static final java.util.Map<String, Long> sLastMsgTimes = new java.util.HashMap<>();
    private static final java.util.Map<String, Integer> sLastMsgHashes = new java.util.HashMap<>();

    public static void onMessageReceived(@NonNull Context context, @NonNull String path, @Nullable byte[] data, @NonNull String sourceNodeId) {
        onMessageReceived(context, path, data, sourceNodeId, null);
    }

    public static void onMessageReceived(@NonNull Context context, @NonNull String path, @Nullable byte[] data, @NonNull String sourceNodeId, @Nullable String localNodeId) {
        if (localNodeId != null && localNodeId.equals(sourceNodeId)) {
            Log.v(TAG, "DEBUG_WEAR_PIPELINE: Ignoring local loopback message at " + path);
            return;
        }

        byte[] payload = data != null ? data : new byte[0];

        int hash = path.hashCode() ^ java.util.Arrays.hashCode(payload);
        long now = System.currentTimeMillis();
        
        long window = DEDUPLICATION_WINDOW_DEFAULT_MS;
        if (path.equals(PATH_SEARCH_QUERY)) window = DEDUPLICATION_WINDOW_SEARCH_MS;
        else if (path.endsWith("/request") || path.endsWith("/trigger") || path.equals(PATH_PING)) window = 50; // Very short for triggers
        
        Long lastTime = sLastMsgTimes.get(path);
        Integer lastHash = sLastMsgHashes.get(path);
        
        if (lastHash != null && lastHash == hash && lastTime != null && (now - lastTime) < window) {
            app.organicmaps.sdk.sync.WearLog.d("Message IGNORED (Deduplication): " + path);
            return;
        }
        sLastMsgHashes.put(path, hash);
        sLastMsgTimes.put(path, now);

        app.organicmaps.sdk.sync.WearLog.logReceived("PHONE", "AUTO", path, payload.length);
        
        final byte[] finalPayload = payload;

        switch (path) {
            case PATH_HANDSHAKE:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handshake received");
                // Force a bookmark-metadata push once per connect so both sides reconcile to a full
                // mirror. (Self-guards on framework readiness.)
                sMainHandler.post(WearSyncService::syncBookmarksMetadataForced);
                break;
            case PATH_STOP_NAVIGATION:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_STOP_NAVIGATION");
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Stopping navigation per watch request");
                    RoutingController.get().cancel();
                    app.organicmaps.routing.NavigationService.stopService(context);
                });
                break;
            case PATH_SEARCH_QUERY: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_SEARCH_QUERY. Payload size: " + finalPayload.length);
                ByteBuffer buffer = ByteBuffer.wrap(finalPayload);
                double lat = 0;
                double lon = 0;
                String query = "";
                
                if (finalPayload.length >= 16) {
                    try {
                        lat = buffer.getDouble();
                        lon = buffer.getDouble();
                        
                        int queryLen = finalPayload.length - 16;
                        if (queryLen > 0) {
                            byte[] queryBytes = new byte[queryLen];
                            buffer.get(queryBytes);
                            query = new String(queryBytes, StandardCharsets.UTF_8).trim();
                        }
                        Log.i(TAG, "DEBUG_WEAR_PIPELINE: Parsed search: '" + query + "' at " + lat + ", " + lon);
                    } catch (Exception e) {
                        Log.e(TAG, "DEBUG_WEAR_PIPELINE: Failed to parse search payload", e);
                        query = new String(finalPayload, StandardCharsets.UTF_8).trim();
                    }
                } else {
                    query = new String(finalPayload, StandardCharsets.UTF_8).trim();
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Search payload too small for headers, using raw: '" + query + "'");
                }
                
                final double finalLat = lat;
                final double finalLon = lon;
                final String finalQuery = query;
                
                if (finalQuery.isEmpty()) {
                    Log.w(TAG, "DEBUG_WEAR_PIPELINE: Received EMPTY search query, ignoring");
                    return;
                }

                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Starting headless search for: '" + finalQuery + "' at " + finalLat + ", " + finalLon);
                    ensureFrameworkInitialized(context, () -> {
                        app.organicmaps.sdk.search.SearchEngine.INSTANCE.initialize();
                        HeadlessSearchInteractor.getInstance(context).startSearch(finalQuery, finalLat, finalLon);
                    });
                });
                break;
            }
            case PATH_SEARCH_SELECT: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_SEARCH_SELECT");
                ByteBuffer buffer = ByteBuffer.wrap(finalPayload);
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
                    ensureFrameworkInitialized(context, () -> {
                        app.organicmaps.sdk.search.SearchRecents.add(name, context);
                        HeadlessRouteInteractor.getInstance(context).planRoute(lat, lon, routerType, name);
                        
                        // Wake up UI to ensure user sees the planning
                        Intent intent = new Intent(context, app.organicmaps.MwmActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        intent.putExtra(app.organicmaps.MwmActivity.EXTRA_SHOW_MAP, true);
                        context.startActivity(intent);
                    });
                });
                break;
            }
            case PATH_SEARCH_HISTORY_REQUEST:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_SEARCH_HISTORY_REQUEST");
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Sending search history to watch");
                    ensureFrameworkInitialized(context, () -> WearSyncService.sendSearchHistory(context.getApplicationContext()));
                });
                break;
            case PATH_SEARCH_HISTORY:
            case PATH_SEARCH_HISTORY_SYNC:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling " + path);
                sMainHandler.post(() -> {
                    ByteBuffer buffer = ByteBuffer.wrap(finalPayload);
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
            case PATH_SEARCH_RESULTS:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Received search results from watch (ignoring on phone)");
                break;
            case PATH_BOOKMARKS:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Received bookmarks from watch (ignoring on phone)");
                break;
            case PATH_PING:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Ping received from " + sourceNodeId);
                WearSyncService.getSyncLayer().sendPong(context.getApplicationContext(), sourceNodeId);
                break;
            case PATH_PREFERENCES_REQUEST:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_PREFERENCES_REQUEST");
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested settings sync");
                WearSyncService.getSyncLayer().syncPreferences(context.getApplicationContext());
                break;
            case PATH_START_NAVIGATION_REQUEST:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_START_NAVIGATION_REQUEST");
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to start navigation");
                    RoutingController.get().start();
                    
                    // Wake up UI to ensure user sees the navigation
                    Intent intent = new Intent(context, app.organicmaps.MwmActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra(app.organicmaps.MwmActivity.EXTRA_SHOW_MAP, true);
                    context.startActivity(intent);
                });
                break;
            case PATH_TRACK_RECORDING_TOGGLE:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_TRACK_RECORDING_TOGGLE");
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
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_BOOKMARKS_REQUEST");
                sMainHandler.post(() -> {
                    Log.i(TAG, "DEBUG_WEAR_PIPELINE: Watch requested bookmark categories - triggering sync");
                    ensureFrameworkInitialized(context, () -> {
                        WearSyncService.sendBookmarkCategories(context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());
                        // Push bookmark metadata (deduped — unchanged metadata is skipped, so the watch's
                        // periodic requests don't cause a resend storm). Per-connect force is on HANDSHAKE.
                        WearSyncService.syncBookmarksNow();
                    });
                });
                break;
            case PATH_BOOKMARK_SHOW: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_BOOKMARK_SHOW");
                long bmkId = ByteBuffer.wrap(finalPayload).getLong();
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to show bookmark: " + bmkId);
                    app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.showBookmarkOnMap(bmkId);
                    Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                    }
                });
                break;
            }
            case PATH_BOOKMARK_UPDATE: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_BOOKMARK_UPDATE");
                ByteBuffer updateBuf = ByteBuffer.wrap(finalPayload);
                long updateBmkId = updateBuf.getLong();
                int nameLen = updateBuf.getInt();
                byte[] nameB = new byte[nameLen];
                updateBuf.get(nameB);
                String name = new String(nameB, StandardCharsets.UTF_8);
                int color = updateBuf.getInt();
                long newCategoryId = updateBuf.hasRemaining() ? updateBuf.getLong() : -1;

                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to update bookmark: " + updateBmkId + " name: " + name + " color: " + color + " categoryId: " + newCategoryId);
                    WearSyncService.setApplyingRemoteUpdate(true);
                    try {
                        app.organicmaps.sdk.bookmarks.data.BookmarkInfo info = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getBookmarkInfo(updateBmkId);
                        if (info != null) {
                            info.update(name, new app.organicmaps.sdk.bookmarks.data.Icon(color, 0), "");
                            if (newCategoryId != -1 && newCategoryId != info.getCategoryId()) {
                                info.changeCategory(newCategoryId);
                            }
                        }
                    } finally {
                        WearSyncService.setApplyingRemoteUpdate(false);
                    }
                });
                break;
            }
            case PATH_BOOKMARK_CATEGORY_CREATE: {
                String name = new String(finalPayload, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to create category: " + name);
                    WearSyncService.setApplyingRemoteUpdate(true);
                    try {
                        app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.createCategory(name);
                    } finally {
                        WearSyncService.setApplyingRemoteUpdate(false);
                    }
                });
                break;
            }
            case PATH_BOOKMARK_VISIBLE_TOGGLE:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_BOOKMARK_VISIBLE_TOGGLE");
                String catName = new String(finalPayload, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    WearSyncService.setApplyingRemoteUpdate(true);
                    try {
                        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                            if (cat.getName().equals(catName)) {
                                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch toggling visibility for cat: " + catName);
                                cat.toggleVisibility();
                                return;
                            }
                        }
                        Log.w(TAG, "DEBUG_WEAR_PIPELINE: Watch requested toggle for unknown cat: " + catName);
                    } finally {
                        WearSyncService.setApplyingRemoteUpdate(false);
                    }
                });
                break;
            case PATH_BOOKMARK_SYNC_REQUEST:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_BOOKMARK_SYNC_REQUEST");
                String syncCatName = new String(finalPayload, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested sync for cat: " + syncCatName);
                    ensureFrameworkInitialized(context, () -> {
                        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                            if (cat.getName().equals(syncCatName)) {
                                // Register the id so the sharing listener pushes the export to the
                                // watch. setSilentSyncInProgress(true) is a no-op under the ID-based
                                // logic, which left this path silent=false → file dropped → watch
                                // re-requests forever (prepare/save storm).
                                WearSyncService.markSilentSync(cat.getId());
                                app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.prepareCategoriesForSharing(
                                        new long[]{cat.getId()}, app.organicmaps.sdk.bookmarks.data.KmlFileType.Text);
                                return;
                            }
                        }
                    });
                });
                break;
            case PATH_BOOKMARK_RENAME: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_BOOKMARK_RENAME");
                ByteBuffer buffer = ByteBuffer.wrap(finalPayload);
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
                    WearSyncService.setApplyingRemoteUpdate(true);
                    try {
                        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                            if (cat.getName().equals(oldName)) {
                                cat.setName(newName);
                                return;
                            }
                        }
                    } finally {
                        WearSyncService.setApplyingRemoteUpdate(false);
                    }
                });
                break;
            }
            case PATH_BOOKMARK_DELETE: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_BOOKMARK_DELETE");
                String delName = new String(finalPayload, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to delete cat: " + delName);
                    WearSyncService.setApplyingRemoteUpdate(true);
                    try {
                        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                            if (cat.getName().equals(delName)) {
                                app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.deleteCategory(cat.getId());
                                return;
                            }
                        }
                    } finally {
                        WearSyncService.setApplyingRemoteUpdate(false);
                    }
                });
                break;
            }
            case PATH_DOWNLOADED_MAPS_REQUEST:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_DOWNLOADED_MAPS_REQUEST");
                sMainHandler.post(() -> {
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested downloaded maps list");
                    ensureFrameworkInitialized(context, () -> {
                        java.util.List<app.organicmaps.sdk.downloader.CountryItem> downloaded = new java.util.ArrayList<>();
                        app.organicmaps.sdk.downloader.MapManager.nativeListItems(null, 0, 0, false, true, downloaded);
                        java.util.List<String> ids = new java.util.ArrayList<>();
                        for (app.organicmaps.sdk.downloader.CountryItem item : downloaded) {
                            if (item.present) ids.add(item.id);
                        }
                        WearSyncService.getSyncLayer().sendDownloadedMaps(context.getApplicationContext(), ids);
                    });
                });
                break;
            case PATH_MAP_DOWNLOAD_REQUEST:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_MAP_DOWNLOAD_REQUEST");
                ByteBuffer dlBuf = ByteBuffer.wrap(finalPayload);
                String dlMapId;
                long dlOffset = 0;
                long dlChecksum = 0;
                if (dlBuf.remaining() >= 4) {
                    int nameLen = dlBuf.getInt();
                    if (dlBuf.remaining() >= nameLen) {
                        byte[] nameBytes = new byte[nameLen];
                        dlBuf.get(nameBytes);
                        dlMapId = new String(nameBytes, StandardCharsets.UTF_8);
                        if (dlBuf.remaining() >= 8) {
                            dlOffset = dlBuf.getLong();
                        }
                        if (dlBuf.remaining() >= 8) {
                            dlChecksum = dlBuf.getLong();
                        }
                    } else {
                        dlMapId = new String(finalPayload, StandardCharsets.UTF_8);
                    }
                } else {
                    dlMapId = new String(finalPayload, StandardCharsets.UTF_8);
                }
                
                final String finalDlMapId = dlMapId;
                final long requestedOffset = dlOffset;
                final long requestedChecksum = dlChecksum;

                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        long validatedOffset = requestedOffset;
                        if (validatedOffset > 0) {
                            try {
                                java.io.File localFile = WearMapStreamingHelper.findMapFile(context, finalDlMapId);
                                if (localFile != null && localFile.exists()) {
                                    long localChecksum = ChecksumUtils.calculateCRC32(localFile, validatedOffset);
                                    if (localChecksum != requestedChecksum) {
                                        Log.w(TAG, "Checksum mismatch for " + finalDlMapId + " at offset " + validatedOffset + ". Local: " + localChecksum + ", Remote: " + requestedChecksum + ". Forcing full re-download.");
                                        validatedOffset = 0;
                                    } else {
                                        Log.i(TAG, "Checksum verified for " + finalDlMapId + " at offset " + validatedOffset + ". Resuming...");
                                    }
                                } else {
                                    validatedOffset = 0;
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to verify checksum for " + finalDlMapId, e);
                                validatedOffset = 0;
                            }
                        }

                        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested map streaming: " + finalDlMapId + " from offset: " + validatedOffset);
                        WearMapStreamingHelper.streamMapToWatch(context, sourceNodeId, finalDlMapId, validatedOffset);
                    });
                });
                break;
            case WearProtocol.PATH_MAP_DOWNLOAD_CANCEL:
                String cancelMapId = new String(finalPayload, StandardCharsets.UTF_8);
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested to cancel map streaming: " + cancelMapId);
                WearSyncService.getSyncLayer().cancelStreaming(cancelMapId);
                break;
            case PATH_MAP_PROGRESS:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_MAP_PROGRESS");
                sMainHandler.post(() -> {
                    ByteBuffer buffer = ByteBuffer.wrap(finalPayload);
                    if (buffer.remaining() < 8) return;
                    int countryLen = buffer.getInt();
                    if (buffer.remaining() < countryLen + 4) return;
                    byte[] cBytes = new byte[countryLen];
                    buffer.get(cBytes);
                    String countryId = new String(cBytes, StandardCharsets.UTF_8);
                    int progress = buffer.getInt();
                    Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch map progress received: " + countryId + " -> " + progress + "%");
                    WearCompanionNotificationManager.showServingNotification(context, countryId, progress);
                });
                break;
            case PATH_VIRTUAL_MWM_METADATA_REQUEST: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_VIRTUAL_MWM_METADATA_REQUEST");
                String mwmName = new String(finalPayload, StandardCharsets.UTF_8);
                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        long size = app.organicmaps.sdk.Framework.nativeGetMwmSize(mwmName);
                        long dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion();
                        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch requested metadata for: " + mwmName + " size: " + size + " version: " + dataVersion);
                        if (size > 0) {
                            int footerSize = (int) Math.min(size, 64 * 1024);
                            long footerOffset = size - footerSize;
                            byte[] footerData = app.organicmaps.sdk.Framework.nativeGetMwmBytes(mwmName, footerOffset, footerSize);
                            
                            int headerSize = (int) Math.min(size, 16 * 1024);
                            byte[] headerData = app.organicmaps.sdk.Framework.nativeGetMwmBytes(mwmName, 0, headerSize);
                            
                            WearSyncService.getSyncLayer().sendMwmMetadata(context.getApplicationContext(), mwmName, size, headerData, footerData);
                        } else {
                            Log.w(TAG, "DEBUG_WEAR_PIPELINE: Map NOT FOUND on phone: " + mwmName + " (Checking storage...)");
                            WearSyncService.getSyncLayer().sendMapNotFound(context.getApplicationContext(), mwmName);
                        }
                    });
                });
                break;
            }
            case PATH_VIRTUAL_MWM_REQUEST: {
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_VIRTUAL_MWM_REQUEST");
                ByteBuffer buffer = ByteBuffer.wrap(finalPayload);
                int nameLen = buffer.getInt();
                byte[] nameBytes = new byte[nameLen];
                buffer.get(nameBytes);
                String mwmName = new String(nameBytes, StandardCharsets.UTF_8);
                long offset = buffer.getLong();
                int size = buffer.getInt();

                sMainHandler.post(() -> {
                    ensureFrameworkInitialized(context, () -> {
                        int safeSize = Math.min(size, 85 * 1024); 
                        byte[] mwmData = app.organicmaps.sdk.Framework.nativeGetMwmBytes(mwmName, offset, safeSize);
                        if (mwmData != null && mwmData.length > 0) {
                            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Sending MWM bytes: " + mwmName + " offset: " + offset + " size: " + mwmData.length + " (requested: " + size + ")");
                            WearSyncService.getSyncLayer().sendMwmBytes(context.getApplicationContext(), mwmName, offset, mwmData);
                        } else {
                            long fileSize = app.organicmaps.sdk.Framework.nativeGetMwmSize(mwmName);
                            Log.w(TAG, "DEBUG_WEAR_PIPELINE: Failed to get MwmBytes for: " + mwmName + " at " + offset + " (MWM file size: " + fileSize + ")");
                        }
                    });
                });
                break;
            }
            case WearProtocol.PATH_PREFERENCES_WATCH:
            case WearProtocol.PATH_PREFERENCES_UPDATES:
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Handling PATH_PREFERENCES (watch/updates)");
                Log.d(TAG, "DEBUG_WEAR_PIPELINE: Watch sent preferences update: " + path);
                sMainHandler.post(() -> {
                    android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                    WearSyncService.getSyncLayer().parsePreferences(context, finalPayload, prefs);
                    
                    Intent intent = new Intent("app.organicmaps.wear.SETTINGS_CHANGED");
                    context.sendBroadcast(intent);
                });
                break;
            case PATH_BOOKMARKS_METADATA:
                sMainHandler.post(() -> WearSyncService.handleIncomingBookmarksMetadata(context, finalPayload));
                break;
            case PATH_BOOKMARK_FILE:
                sMainHandler.post(() -> WearSyncService.handleIncomingBookmarkFile(context, finalPayload));
                break;
            case WearProtocol.PATH_BOOKMARK_TOMBSTONE:
                WearSyncService.applyIncomingTombstone(context, finalPayload);
                break;
            case PATH_BACKEND_SWITCH: {
                String newBackend = new String(finalPayload, StandardCharsets.UTF_8);
                Log.i(TAG, "DEBUG_WEAR_PIPELINE: Watch requested backend switch to: " + newBackend);
                sMainHandler.post(() -> {
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .putString("pref_wear_os_backend", newBackend)
                            .apply();
                    WearSyncService.initSyncLayer(context);
                });
                break;
            }
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
