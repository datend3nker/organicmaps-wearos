package app.organicmaps.sync;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.search.SearchResult;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.Node;

import app.organicmaps.wear.WearMessageRouter;

import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import app.organicmaps.sdk.sync.BaseSettingsSyncManager;
import app.organicmaps.sdk.sync.WearProtocol;
import app.organicmaps.sdk.sync.WearProtocolDataConverter;

public class GmsSyncLayer implements ISyncLayer {
    private static final String TAG = "GmsSyncLayer";

    private final List<MessageListener> mListeners = new CopyOnWriteArrayList<>();
    private long mLastReceivedTime = 0;
    private long mLastPingSentTime = 0;
    private long mCurrentPingInterval = 15000; // 15 seconds
    private static final long CONNECTION_TIMEOUT = 45000;
    private boolean mIsApplyingPreferences = false;
    private final android.os.Handler mHeartbeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String mActivePeerId;
    private static volatile String sLocalNodeId;

    public GmsSyncLayer() {
        startHeartbeat();
        if (sLocalNodeId == null) {
            fetchLocalNodeId();
        }
        
        // Log own ID for diagnostics
        app.organicmaps.sdk.sync.WearLog.logState("PHONE", "GmsSyncLayer initialized. LocalNodeID=" + sLocalNodeId);
    }

    private void fetchLocalNodeId() {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context != null) {
            Wearable.getNodeClient(context).getLocalNode()
                .addOnSuccessListener(node -> {
                    sLocalNodeId = node.getId();
                    Log.i(TAG, "DEBUG_GMS: Local node ID identified: " + sLocalNodeId + " (" + node.getDisplayName() + ")");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "DEBUG_GMS: Failed to fetch local node ID, retrying...", e);
                    mHeartbeatHandler.postDelayed(this::fetchLocalNodeId, 5000);
                });
        }
    }

    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean isAlive = (now - mLastReceivedTime) < CONNECTION_TIMEOUT;
            
            if (isAlive) {
                mCurrentPingInterval = 15000; // Reset to 15s
            } else {
                mCurrentPingInterval = Math.min((long)(mCurrentPingInterval * 1.5), 300000L);
            }

            if (now - mLastReceivedTime > mCurrentPingInterval) {
                if (now - mLastPingSentTime > mCurrentPingInterval) {
                    app.organicmaps.sdk.sync.WearLog.logState("PHONE", "Heartbeat (" + (isAlive ? "Alive" : "Backoff") + " " + mCurrentPingInterval + "ms) - sending ping");
                    sendMessageInternal(app.organicmaps.MwmApplication.sInstance, WearProtocol.PATH_PING, new byte[0]);
                    mLastPingSentTime = now;
                }
            }
            
            Context context = app.organicmaps.MwmApplication.sInstance;
            if (context != null) {
                Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
                    if (!nodes.isEmpty()) {
                        long idle = SystemClock.elapsedRealtime() - mLastReceivedTime;
                        if (idle > CONNECTION_TIMEOUT) {
                            Log.d(TAG, "DEBUG_GMS: Attempting re-handshake ping (Idle: " + idle + "ms)");
                            sendMessageInternal(context, WearProtocol.PATH_PING, new byte[0]);
                        }
                    }
                });
            }
            mHeartbeatHandler.postDelayed(this, 10000);
        }
    };

    private void startHeartbeat() {
        mHeartbeatHandler.removeCallbacks(mHeartbeatRunnable);
        mHeartbeatHandler.postDelayed(mHeartbeatRunnable, 15000);
    }

    private boolean isFrameworkReady() {
        return app.organicmaps.MwmApplication.sInstance != null && 
               app.organicmaps.MwmApplication.sInstance.getOrganicMaps().arePlatformAndCoreInitialized();
    }

    @Override
    public void syncPreferences(@NonNull Context context) {
        if (mIsApplyingPreferences) return;
        
        app.organicmaps.wear.SettingsSyncManager manager = app.organicmaps.wear.SettingsSyncManager.getInstance(context);
        List<BaseSettingsSyncManager.SettingUpdate> all = manager.getAllSettings();

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_PREFERENCES_PHONE);
        DataMap map = putDataMapReq.getDataMap();
        map.putByte("protocolVersion", PROTOCOL_VERSION);
        
        for (BaseSettingsSyncManager.SettingUpdate update : all) {
            putValue(map, update.key, update.value);
            map.putLong("ts_" + update.key, update.timestamp);
            map.putLong("v_" + update.key, update.version);
        }
        
        map.putLong("timestamp", System.currentTimeMillis());
        
        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnSuccessListener(dataItem -> manager.markAsSynced(all))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to sync preferences", e));

        sendMessage(context, WearProtocol.PATH_PREFERENCES_TRIGGER, new byte[0]);
    }

    @Override
    public void syncPreferenceUpdates(@NonNull Context context, @NonNull List<BaseSettingsSyncManager.SettingUpdate> updates) {
        if (mIsApplyingPreferences || updates.isEmpty()) return;

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_PREFERENCES_UPDATES);
        DataMap map = putDataMapReq.getDataMap();
        map.putByte("protocolVersion", PROTOCOL_VERSION);
        
        for (BaseSettingsSyncManager.SettingUpdate update : updates) {
            DataMap item = new DataMap();
            putValue(item, "v", update.value);
            item.putLong("t", update.timestamp);
            item.putLong("ver", update.version);
            map.putDataMap(update.key, item);
        }
        
        map.putLong("_trigger", System.currentTimeMillis());

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnSuccessListener(dataItem -> app.organicmaps.wear.SettingsSyncManager.getInstance(context).markAsSynced(updates));
                
        sendMessage(context, WearProtocol.PATH_PREFERENCES_TRIGGER, new byte[0]);
    }

    private void putValue(DataMap map, String key, Object value) {
        if (value instanceof Boolean) map.putBoolean(key, (Boolean) value);
        else if (value instanceof String) map.putString(key, (String) value);
        else if (value instanceof Integer) map.putInt(key, (Integer) value);
        else if (value instanceof Long) map.putLong(key, (Long) value);
    }

    @Override
    public void updateNavigation(@NonNull Context context, @Nullable RoutingInfo info, @Nullable Location location) {
        byte[] payload = WearProtocolDataConverter.encodeNavigationStatus(context, 
            app.organicmaps.sdk.routing.RoutingController.get().isNavigating(), 
            info, location, null, null);
        sendMessage(context, WearProtocol.PATH_NAVIGATION_STATUS, payload);
    }

    @Override
    public void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        
        float[] routeLats = null;
        float[] routeLons = null;
        try {
            app.organicmaps.sdk.routing.JunctionInfo[] junctions = app.organicmaps.sdk.Framework.nativeGetRouteJunctionPoints(20.0);
            if (junctions != null && junctions.length > 0) {
                routeLats = new float[junctions.length];
                routeLons = new float[junctions.length];
                for (int i = 0; i < junctions.length; i++) {
                    routeLats[i] = (float) junctions[i].mLat;
                    routeLons[i] = (float) junctions[i].mLon;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract route junctions", e);
        }

        byte[] payload = WearProtocolDataConverter.encodeNavigationStatus(context, true, null, null, routeLats, routeLons);
        sendMessage(context, WearProtocol.PATH_NAVIGATION_STATUS, payload);
    }

    @Override
    public void stopNavigation(@NonNull Context context) {
        byte[] payload = WearProtocolDataConverter.encodeNavigationStatus(context, false, null, null, null, null);
        sendMessage(context, WearProtocol.PATH_NAVIGATION_STATUS, payload);
    }

    @Override
    public void stop() {
        mHeartbeatHandler.removeCallbacks(mHeartbeatRunnable);
        mLastReceivedTime = 0;
    }


    @Override
    public void launchWatchApp(@NonNull Context context) {
        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                if (node.getId().equals(sLocalNodeId)) continue;
                sendMessage(context, "/launch", new byte[0]);
            }
        });
    }

    @Override
    public void sendHandshake(@NonNull Context context) {
        byte[] payload = WearProtocolDataConverter.encodeHandshake(app.organicmaps.BuildConfig.VERSION_CODE, (byte) 0);
        sendMessage(context, WearProtocol.PATH_HANDSHAKE, payload);
    }

    @Override
    public void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        byte[] payload = WearProtocolDataConverter.encodeSearchResults(context, results, isSearching, 20);
        sendMessage(context, WearProtocol.PATH_SEARCH_RESULTS, payload);
    }

    @Override
    public void sendSearchState(Context context, boolean isSearching) {
        byte[] payload = WearProtocolDataConverter.encodeSearchResults(context, new SearchResult[0], isSearching, 0);
        sendMessage(context, WearProtocol.PATH_SEARCH_RESULTS, payload);
    }

    @Override
    public void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        List<String> history = new ArrayList<>();
        for (int i = 0; i < Math.min(SearchRecents.getSize(), 10); i++) {
            history.add(SearchRecents.get(i));
        }
        byte[] payload = WearProtocolDataConverter.encodeSearchHistory(history, 10);
        sendMessage(context, WearProtocol.PATH_SEARCH_HISTORY, payload);
    }

    @Override
    public void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        sendMessage(context, WearProtocol.PATH_MAP_DOWNLOAD_REQUEST, countryId.getBytes());
    }

    @Override
    public void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features) {
        byte[] dataToSend = features;
        boolean compressed = false;
        if (features.length > 512) {
            try {
                dataToSend = app.organicmaps.sdk.util.GzipUtils.compress(features);
                compressed = true;
            } catch (java.io.IOException e) {
                Log.w(TAG, "Compression failed, sending raw");
            }
        }

        ByteBuffer payload = ByteBuffer.allocate(1 + 8 + 1 + dataToSend.length);
        payload.put(PROTOCOL_VERSION);
        payload.putLong(requestId);
        payload.put((byte) (compressed ? 1 : 0));
        payload.put(dataToSend);

        Wearable.getMessageClient(context).sendMessage(nodeId, WearProtocol.PATH_MAP_TILE_RESPONSE, payload.array());
    }

    @Override
    public void sendMapChunk(@NonNull Context context, @NonNull String mapId, byte[] chunk, long offset, long totalSize) {
    }

    @Override
    public void sendMwmBytes(@NonNull Context context, @NonNull String mwmName, long offset, @NonNull byte[] data) {
        byte[] dataToSend = data;
        boolean compressed = false;
        if (data.length > 512) {
            try {
                dataToSend = app.organicmaps.sdk.util.GzipUtils.compress(data);
                compressed = true;
            } catch (java.io.IOException e) {
                Log.w(TAG, "Compression failed for MwmBytes, sending raw");
            }
        }

        byte[] nameBytes = mwmName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(4 + nameBytes.length + 8 + 1 + dataToSend.length);
        payload.putInt(nameBytes.length);
        payload.put(nameBytes);
        payload.putLong(offset);
        payload.put((byte) (compressed ? 1 : 0));
        payload.put(dataToSend);

        sendMessage(context, WearProtocol.PATH_VIRTUAL_MWM_DATA, payload.array());
    }

    @Override
    public void sendMwmMetadata(@NonNull Context context, @NonNull String mwmName, long totalSize, @Nullable byte[] header, @Nullable byte[] footer) {
        byte[] nameBytes = mwmName.getBytes(StandardCharsets.UTF_8);
        int headerLen = (header != null) ? header.length : 0;
        int footerLen = (footer != null) ? footer.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(4 + nameBytes.length + 8 + 4 + headerLen + 4 + footerLen);
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);
        buffer.putLong(totalSize);
        buffer.putInt(headerLen);
        if (header != null) buffer.put(header);
        buffer.putInt(footerLen);
        if (footer != null) buffer.put(footer);

        sendMessage(context, WearProtocol.PATH_VIRTUAL_MWM_MOUNT, buffer.array());
    }

    @Override
    public void sendDownloadedMaps(@NonNull Context context, @NonNull List<String> mapIds) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_MAP_PHONE_DOWNLOADED);
        DataMap map = putDataMapReq.getDataMap();
        map.putByte("protocolVersion", PROTOCOL_VERSION);
        map.putStringArrayList("mapIds", new ArrayList<>(mapIds));
        map.putLong("timestamp", System.currentTimeMillis());
        
        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void requestMwmMetadata(@NonNull Context context, @NonNull String mwmName) {
    }

    private void sendMessage(Context context, String path, byte[] data) {
        app.organicmaps.wear.WearSyncService.onLocalTrafficSent();
        sendMessageInternal(context, path, data);
    }

    @Override
    public void sendRawMessage(@NonNull Context context, byte type, byte[] payload) {
        String path = WearProtocol.getPath(type);
        if (path != null) {
            sendMessage(context, path, payload);
        }
    }

    private void sendMessageInternal(Context context, String path, byte[] data) {
        if (context == null) return;

        byte[] versionedData = new byte[data.length + 1];
        versionedData[0] = PROTOCOL_VERSION;
        System.arraycopy(data, 0, versionedData, 1, data.length);

        app.organicmaps.sdk.sync.WearLog.logSent("PHONE", "GMS", path, versionedData.length);
        
        app.organicmaps.wear.WearSyncService.onLocalTrafficSent();

        if (sLocalNodeId == null) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                try {
                    sLocalNodeId = Tasks.await(Wearable.getNodeClient(context).getLocalNode(), 2, java.util.concurrent.TimeUnit.SECONDS).getId();
                } catch (Exception e) {
                    fetchLocalNodeId();
                }
            } else {
                fetchLocalNodeId();
            }
        }

        if (mActivePeerId != null && !mActivePeerId.equals(sLocalNodeId)) {
            Wearable.getMessageClient(context).sendMessage(mActivePeerId, path, versionedData)
                .addOnFailureListener(e -> {
                    mActivePeerId = null;
                    sendViaCapability(context, path, versionedData);
                });
            return;
        }

        sendViaCapability(context, path, versionedData);
    }

    private void sendViaCapability(Context context, String path, byte[] versionedData) {
        Wearable.getCapabilityClient(context)
                .getCapability("organic_maps_watch_app", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> {
                    Set<Node> nodes = capabilityInfo.getNodes();
                    int sentCount = 0;
                    for (Node node : nodes) {
                        if (sLocalNodeId != null && node.getId().equals(sLocalNodeId)) continue;
                        Wearable.getMessageClient(context).sendMessage(node.getId(), path, versionedData);
                        sentCount++;
                    }

                    if (sentCount == 0) {
                        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(allNodes -> {
                            for (Node node : allNodes) {
                                if (sLocalNodeId != null && node.getId().equals(sLocalNodeId)) continue;
                                Wearable.getMessageClient(context).sendMessage(node.getId(), path, versionedData);
                            }
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(allNodes -> {
                        for (Node node : allNodes) {
                            if (sLocalNodeId != null && node.getId().equals(sLocalNodeId)) continue;
                            Wearable.getMessageClient(context).sendMessage(node.getId(), path, versionedData);
                        }
                    });
                });
    }

    @Override
    public void sendPong(@NonNull Context context, @NonNull String nodeId) {
        byte[] pongData = new byte[1];
        pongData[0] = PROTOCOL_VERSION;
        Wearable.getMessageClient(context).sendMessage(nodeId, WearProtocol.PATH_PONG, pongData);
    }

    @Override
    public void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS);
        DataMap map = putDataMapReq.getDataMap();
        map.putByte("protocolVersion", PROTOCOL_VERSION);
        map.putString("countryId", countryId);
        map.putInt("progress", progress);
        map.putLong("timestamp", System.currentTimeMillis());
        
        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void sendRouteBuildProgress(@NonNull Context context, int progress) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(progress);
        sendMessage(context, WearProtocol.PATH_ROUTE_BUILD_PROGRESS, buffer.array());
    }

    @Override
    public void sendMapNotFound(@NonNull Context context, @NonNull String mapId) {
        sendMessage(context, WearProtocol.PATH_MAP_DOWNLOAD_NOT_FOUND, mapId.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendTrackRecordingStatus(@NonNull Context context, boolean isRecording) {
        long startTime = app.organicmaps.location.TrackRecordingService.getRecordingStartTime();
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8);
        buffer.put((byte) (isRecording ? 1 : 0));
        buffer.putLong(startTime);
        sendMessage(context, WearProtocol.PATH_TRACK_RECORDING, buffer.array());
    }

    @Override
    public void sendBookmarkCategories(@NonNull Context context, @NonNull List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories) {
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        byte[] payload = WearProtocolDataConverter.encodeBookmarkCategories(categories, syncPrefs, 50);
        sendMessage(context, WearProtocol.PATH_BOOKMARKS, payload);
    }


    @Override
    public void sendBookmarkFile(@NonNull Context context, @NonNull String categoryName, @NonNull byte[] data, boolean isLast) {
        byte[] nameBytes = categoryName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + 4 + nameBytes.length + data.length);
        payload.put((byte) (isLast ? 1 : 0));
        payload.putInt(nameBytes.length);
        payload.put(nameBytes);
        payload.put(data);

        sendMessage(context, WearProtocol.PATH_BOOKMARK_FILE, payload.array());
        
        if (isLast) {
            context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
                   .edit().putLong(categoryName, System.currentTimeMillis()).apply();
        }
    }

    @Override
    public void renameBookmarkCategory(@NonNull Context context, @NonNull String oldName, @NonNull String newName) {
        byte[] oldBytes = oldName.getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = newName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(4 + oldBytes.length + 4 + newBytes.length);
        payload.putInt(oldBytes.length);
        payload.put(oldBytes);
        payload.putInt(newBytes.length);
        payload.put(newBytes);

        sendMessage(context, WearProtocol.PATH_BOOKMARK_RENAME, payload.array());
        
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        long ts = syncPrefs.getLong(oldName, 0);
        syncPrefs.edit().remove(oldName).putLong(newName, ts > 0 ? ts : System.currentTimeMillis()).apply();
    }

    @Override
    public void deleteBookmarkCategory(@NonNull Context context, @NonNull String name) {
        sendMessage(context, WearProtocol.PATH_BOOKMARK_DELETE, name.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
               .edit().remove(name).apply();
    }

    @Override
    public void sendBackendSwitch(@NonNull Context context, @NonNull String newBackend) {
        sendMessage(context, WearProtocol.PATH_BACKEND_SWITCH, newBackend.getBytes());
    }

    private final java.util.Map<String, Thread> mStreamingThreads = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void streamMapFile(@NonNull Context context, @NonNull String nodeId, @NonNull String mapId, @NonNull java.io.File file, long offset) {
        String targetNodeId = (mActivePeerId != null) ? mActivePeerId : nodeId;
        try {
            com.google.android.gms.wearable.ChannelClient channelClient = Wearable.getChannelClient(context);
            channelClient.openChannel(targetNodeId, WearProtocol.PATH_MAP_STREAM_DATA + mapId)
                    .addOnSuccessListener(channel -> {
                        android.net.Uri uri = android.net.Uri.fromFile(file);
                        mStreamingThreads.put(mapId, Thread.currentThread());
                        app.organicmaps.wear.WearCompanionNotificationManager.showSearchNotification(context, mapId);

                        channelClient.sendFile(channel, uri)
                                .addOnFailureListener(e -> {
                                    sendMapNotFound(context, mapId);
                                    channelClient.close(channel);
                                });

                        channelClient.registerChannelCallback(channel, new com.google.android.gms.wearable.ChannelClient.ChannelCallback() {
                            @Override
                            public void onChannelClosed(com.google.android.gms.wearable.ChannelClient.Channel c, int closeReason, int errorCode) {
                                mStreamingThreads.remove(mapId);
                                app.organicmaps.wear.WearCompanionNotificationManager.hideNotification(context, app.organicmaps.wear.WearCompanionNotificationManager.NOTIFICATION_ID_SEARCH);
                                channelClient.unregisterChannelCallback(c, this);
                            }
                        });
                    });
        } catch (Exception e) {
            Log.e(TAG, "GMS Channel streaming failed", e);
        }
    }

    @Override
    public void cancelStreaming(@NonNull String mapId) {
        Thread t = mStreamingThreads.remove(mapId);
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void checkConnection(@NonNull Context context, @NonNull ConnectionCallback callback) {
        boolean isLinked = (SystemClock.elapsedRealtime() - mLastReceivedTime) < CONNECTION_TIMEOUT;
        
        Wearable.getCapabilityClient(context)
                .getCapability("organic_maps_watch_app", com.google.android.gms.wearable.CapabilityClient.FILTER_ALL)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && !task.getResult().getNodes().isEmpty()) {
                        Set<Node> nodes = task.getResult().getNodes();
                        boolean nearby = false;
                        for (Node node : nodes) {
                            if (node.isNearby()) {
                                nearby = true;
                                break;
                            }
                        }
                        callback.onConnectionResult(isLinked, nearby ? ConnectionType.BLUETOOTH : ConnectionType.GMS);
                    } else {
                        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
                            boolean hasPeer = false;
                            boolean nearby = false;
                            for (Node node : nodes) {
                                if (!node.getId().equals(sLocalNodeId)) {
                                    hasPeer = true;
                                    if (node.isNearby()) nearby = true;
                                }
                            }
                            if (hasPeer) {
                                callback.onConnectionResult(isLinked, nearby ? ConnectionType.BLUETOOTH : ConnectionType.GMS);
                            } else {
                                callback.onConnectionResult(false, ConnectionType.NONE);
                            }
                        }).addOnFailureListener(e -> callback.onConnectionResult(false, ConnectionType.NONE));
                    }
                });
    }

    @Override
    public void parsePreferences(@NonNull Context context, @NonNull byte[] data, @NonNull android.content.SharedPreferences prefs) {
        DataMap dataMap = DataMap.fromByteArray(data);
        applyPreferencesFromDataMap(context, dataMap, prefs);
    }

    public void applyPreferencesFromDataMap(@NonNull Context context, @NonNull DataMap dataMap, @NonNull android.content.SharedPreferences prefs) {
        if (dataMap.containsKey("protocolVersion") && dataMap.getByte("protocolVersion") != PROTOCOL_VERSION) {
            return;
        }

        long timestamp = dataMap.getLong("timestamp", 0);
        long lastApplied = prefs.getLong("pref_wear_os_last_sync_timestamp", 0);
        if (timestamp > 0 && timestamp <= lastApplied) return;

        mIsApplyingPreferences = true;
        try {
            app.organicmaps.wear.SettingsSyncManager manager = app.organicmaps.wear.SettingsSyncManager.getInstance(context);
            
            List<BaseSettingsSyncManager.SettingUpdate> updates = new ArrayList<>();
            java.util.Map<String, String> mapping = manager.getCanonicalToLocalMapping();
            
            for (String canonicalKey : mapping.keySet()) {
                if (dataMap.containsKey(canonicalKey)) {
                    Object value = dataMap.get(canonicalKey);
                    long ts = dataMap.getLong("ts_" + canonicalKey, 0);
                    long ver = dataMap.getLong("v_" + canonicalKey, 0);
                    if (value != null) {
                        updates.add(new BaseSettingsSyncManager.SettingUpdate(canonicalKey, value, ts, ver));
                    }
                }
            }
            
            for (String key : dataMap.keySet()) {
                Object val = dataMap.get(key);
                if (val instanceof DataMap item) {
                    if (item.containsKey("v") && item.containsKey("t")) {
                         Object innerVal = item.get("v");
                         if (innerVal != null) {
                             updates.add(new BaseSettingsSyncManager.SettingUpdate(key, innerVal, item.getLong("t"), item.getLong("ver", 0)));
                         }
                    }
                }
            }

            if (!updates.isEmpty()) {
                if (manager.applyRemoteUpdates(updates)) {
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    editor.putLong("pref_wear_os_last_sync_timestamp", timestamp);
                    editor.apply();
                    app.organicmaps.wear.WearSyncService.initSyncLayer(context);
                    context.sendBroadcast(new android.content.Intent("app.organicmaps.wear.SETTINGS_CHANGED"));
                }
            }
        } finally {
            mIsApplyingPreferences = false;
        }
    }

    @Override
    public boolean isIgnoringPreferenceChanges() {
        return mIsApplyingPreferences;
    }

    @Override
    public void addMessageListener(@NonNull MessageListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    @Override
    public void removeMessageListener(@NonNull MessageListener listener) {
        mListeners.remove(listener);
    }

    @Override
    public void notifyMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        if (sLocalNodeId == null) {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                Log.v(TAG, "DEBUG_GMS: sLocalNodeId is null, sync fetching for " + path);
                try {
                    sLocalNodeId = Tasks.await(Wearable.getNodeClient(app.organicmaps.MwmApplication.sInstance).getLocalNode(), 2, java.util.concurrent.TimeUnit.SECONDS).getId();
                } catch (Exception e) {
                    Log.w(TAG, "DEBUG_GMS: Failed sync local node fetch, falling back to async");
                    fetchLocalNodeId();
                }
            } else {
                fetchLocalNodeId();
            }
        }

        if (sourceNodeId == null || sourceNodeId.equals(sLocalNodeId)) {
            app.organicmaps.sdk.sync.WearLog.v("PHONE GMS: Ignoring local loopback message at " + path);
            return;
        }

        if (sLocalNodeId == null) {
            app.organicmaps.sdk.sync.WearLog.v("PHONE GMS: Received message before local ID known. Source=" + sourceNodeId + " Path=" + path);
        }
        
        if (data == null || data.length < 1) {
            app.organicmaps.sdk.sync.WearLog.w("PHONE GMS Rejected: Data is NULL or empty");
            return;
        }
        
        byte version = data[0];
        if (version != PROTOCOL_VERSION) {
            app.organicmaps.sdk.sync.WearLog.e("PHONE GMS Rejected: Version mismatch. Received=" + version + " Expected=" + PROTOCOL_VERSION);
            return;
        }
        byte[] payload = new byte[data.length - 1];
        System.arraycopy(data, 1, payload, 0, payload.length);

        mLastReceivedTime = SystemClock.elapsedRealtime();
        mActivePeerId = sourceNodeId;
        
        app.organicmaps.sdk.sync.WearLog.logReceived("PHONE", "GMS", path, payload.length);

        if (path.equals(WearProtocol.PATH_PING) && (sLocalNodeId == null || !sourceNodeId.equals(sLocalNodeId))) {
            sendPong(app.organicmaps.MwmApplication.sInstance, sourceNodeId);
        }

        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context != null) {
            WearMessageRouter.onMessageReceived(context.getApplicationContext(), path, payload, sourceNodeId, sLocalNodeId);
        }

        for (MessageListener listener : mListeners) {
            listener.onMessageReceived(path, payload, sourceNodeId);
        }
    }

    @Override
    public boolean isLinked() {
        return (SystemClock.elapsedRealtime() - mLastReceivedTime) < CONNECTION_TIMEOUT;
    }
}
