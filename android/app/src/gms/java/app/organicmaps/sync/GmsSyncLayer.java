package app.organicmaps.sync;

import android.content.Context;
import android.location.Location;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.settings.RoadType;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.MessageClient;

import app.organicmaps.wear.WearMessageRouter;

import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class GmsSyncLayer implements ISyncLayer {
    private static final String TAG = "GmsSyncLayer";
    private static final String PATH_NAVIGATION = "/navigation/status";
    private static final String PATH_START_NAVIGATION = "/navigation/start";
    private static final String PATH_SEARCH_RESULTS = "/search/results";
    private static final String PATH_SEARCH_HISTORY = "/search/history";
    private static final String PATH_PREFERENCES_PHONE = "/preferences/phone";
    private static final String PATH_PREFERENCES_UPDATES = "/preferences/updates";
    private static final String PATH_MAP_TILE_RESPONSE = "/map/tile/response";
    private static final String PATH_TRACK_RECORDING = "/track/recording";
    private static final String PATH_BOOKMARKS = "/bookmarks";
    private static final String PATH_BOOKMARK_FILE = "/bookmark/file";
    private static final String PATH_BOOKMARK_RENAME = "/bookmark/rename";
    private static final String PATH_BOOKMARK_DELETE = "/bookmark/delete";
    private static final String PATH_VIRTUAL_MWM_REQUEST = "/virtual_mwm/request";
    private static final String PATH_VIRTUAL_MWM_DATA = "/virtual_mwm/data";
    private static final String PATH_VIRTUAL_MWM_MOUNT = "/virtual_mwm/mount";
    private static final String PATH_VIRTUAL_MWM_METADATA_REQUEST = "/virtual_mwm/metadata_request";

    private final List<MessageListener> mListeners = new CopyOnWriteArrayList<>();
    private final com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener mManualListener = this::notifyMessageReceived;
    private long mLastReceivedTime = 0;
    private long mLastPingSentTime = 0;
    private long mCurrentPingInterval = 15000; // 15 seconds
    private static final long CONNECTION_TIMEOUT = 120000; // 2 minutes (increased from 40s)
    private boolean mIsApplyingPreferences = false;
    private final android.os.Handler mHeartbeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public GmsSyncLayer() {
        startHeartbeat();
        registerManualListener();
    }

    private void registerManualListener() {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context != null) {
            Log.d(TAG, "DEBUG_GMS: Registering manual GMS message listener");
            Wearable.getMessageClient(context).addListener(mManualListener);
        }
    }

    private void unregisterManualListener() {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context != null) {
            Log.d(TAG, "DEBUG_GMS: Unregistering manual GMS message listener");
            Wearable.getMessageClient(context).removeListener(mManualListener);
        }
    }

    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            boolean isAlive = (now - mLastReceivedTime) < CONNECTION_TIMEOUT;
            
            if (isAlive) {
                mCurrentPingInterval = 15000; // Reset to 15s
            } else {
                // Exponential backoff up to 5 minutes
                mCurrentPingInterval = Math.min((long)(mCurrentPingInterval * 1.5), 300000L);
            }

            // PING LOGIC: Send ping if we haven't HEARD from them recently
            if (now - mLastReceivedTime > mCurrentPingInterval) {
                // AND we haven't already sent a ping in this interval
                if (now - mLastPingSentTime > mCurrentPingInterval) {
                    Log.d(TAG, "DEBUG_GMS: Heartbeat (" + (isAlive ? "Alive" : "Backoff") + " " + mCurrentPingInterval + "ms) - sending ping");
                    sendMessageInternal(app.organicmaps.MwmApplication.sInstance, "/ping", new byte[0]);
                    mLastPingSentTime = now;
                }
            }
            
            // Schedule next check
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
        
        List<app.organicmaps.wear.SettingsSyncManager.SettingUpdate> all = 
            app.organicmaps.wear.SettingsSyncManager.getInstance(context).getAllSettings();

        Log.d(TAG, "DEBUG_GMS_PIPELINE: syncPreferences (Full Sync) - Items: " + all.size());

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_PREFERENCES_PHONE);
        DataMap map = putDataMapReq.getDataMap();
        map.putByte("protocolVersion", PROTOCOL_VERSION);
        
        for (app.organicmaps.wear.SettingsSyncManager.SettingUpdate update : all) {
            putValue(map, update.key, update.value);
            map.putLong("ts_" + update.key, update.timestamp);
        }
        
        long now = System.currentTimeMillis();
        map.putLong("timestamp", now);
        
        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnSuccessListener(dataItem -> {
                    Log.d(TAG, "DEBUG_GMS_PIPELINE: Successfully putDataItem for full preferences");
                    app.organicmaps.wear.SettingsSyncManager.getInstance(context).markAsSynced(all);
                })
                .addOnFailureListener(e -> Log.e(TAG, "DEBUG_GMS_PIPELINE: Failed to putDataItem for full preferences", e));

        context.sendBroadcast(new android.content.Intent("app.organicmaps.wear.SETTINGS_CHANGED"));
        sendMessage(context, "/preferences/trigger", new byte[0]);
    }

    @Override
    public void syncPreferenceUpdates(@NonNull Context context, @NonNull List<app.organicmaps.wear.SettingsSyncManager.SettingUpdate> updates) {
        if (mIsApplyingPreferences || updates.isEmpty()) return;
        Log.d(TAG, "DEBUG_GMS_PIPELINE: syncPreferenceUpdates (Buffered) - Items: " + updates.size());

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_PREFERENCES_UPDATES);
        DataMap map = putDataMapReq.getDataMap();
        map.putByte("protocolVersion", PROTOCOL_VERSION);
        
        for (app.organicmaps.wear.SettingsSyncManager.SettingUpdate update : updates) {
            Log.d(TAG, "DEBUG_GMS_PIPELINE: Buffering setting for transmission: " + update.key + " = " + update.value);
            DataMap item = new DataMap();
            putValue(item, "v", update.value);
            item.putLong("t", update.timestamp);
            map.putDataMap(update.key, item);
        }
        
        map.putLong("_trigger", System.currentTimeMillis());

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnSuccessListener(dataItem -> {
                    Log.d(TAG, "DEBUG_GMS_PIPELINE: Successfully putDataItem for buffered updates");
                    app.organicmaps.wear.SettingsSyncManager.getInstance(context).markAsSynced(updates);
                })
                .addOnFailureListener(e -> Log.e(TAG, "DEBUG_GMS_PIPELINE: Failed to putDataItem for buffered updates", e));
                
        sendMessage(context, "/preferences/trigger", new byte[0]);
    }

    private void putValue(DataMap map, String key, Object value) {
        if (value instanceof Boolean) map.putBoolean(key, (Boolean) value);
        else if (value instanceof String) map.putString(key, (String) value);
        else if (value instanceof Integer) map.putInt(key, (Integer) value);
        else if (value instanceof Long) map.putLong(key, (Long) value);
    }

    @Override
    public void updateNavigation(@NonNull Context context, @Nullable RoutingInfo info, @Nullable Location location) {
        Log.d(TAG, "DEBUG_GMS: updateNavigation (Message). Navigating: " + app.organicmaps.sdk.routing.RoutingController.get().isNavigating());
        
        byte[] streetBytes = (info != null && info.nextStreet != null) ? info.nextStreet.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] distBytes = (info != null && info.distToTurn != null) ? info.distToTurn.toString(context).getBytes(StandardCharsets.UTF_8) : new byte[0];
        
        // BUFFER Format: [1:active][1:carDir][1:pedDir][1:exit][4:progress][8:lat][8:lon][8:turnLat][8:turnLon][4:bearing][4:speed][4:limit][4:routeLen][4:streetLen][4:distLen][street][dist]
        ByteBuffer buffer = ByteBuffer.allocate(64 + streetBytes.length + distBytes.length);
        buffer.put((byte) (app.organicmaps.sdk.routing.RoutingController.get().isNavigating() ? 1 : 0)); 
        buffer.put((byte) (info != null ? info.carDirection.ordinal() : 0));
        buffer.put((byte) (info != null ? info.pedestrianDirection.ordinal() : 0));
        buffer.put((byte) (info != null ? info.exitNum : 0));
        buffer.putFloat((float) (info != null ? info.completionPercent : 0.0));
        buffer.putDouble(location != null ? location.getLatitude() : 0.0);
        buffer.putDouble(location != null ? location.getLongitude() : 0.0);
        buffer.putDouble(info != null ? info.turnLat : 0.0);
        buffer.putDouble(info != null ? info.turnLon : 0.0);
        
        buffer.putFloat(location != null && location.hasBearing() ? location.getBearing() : -1.0f);
        buffer.putFloat(location != null ? (float) location.getSpeed() : -1.0f);
        buffer.putFloat((float) (info != null ? info.speedLimitMps : -1.0));

        buffer.putInt(0); // routeLen (points)
        buffer.putInt(streetBytes.length);
        buffer.putInt(distBytes.length);
        buffer.put(streetBytes);
        buffer.put(distBytes);
        
        sendMessage(context, PATH_NAVIGATION, buffer.array());
    }

    @Override
    public void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        
        float[] routeLats = new float[0];
        float[] routeLons = new float[0];
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

        // Send a message with just the route points
        ByteBuffer buffer = ByteBuffer.allocate(64 + (routeLats.length * 4 * 2));
        buffer.put((byte) 1); // Active
        buffer.put((byte) 0); // carDir
        buffer.put((byte) 0); // pedDir
        buffer.put((byte) 0); // exit
        buffer.putFloat(0.0f); // progress
        buffer.putDouble(0.0); // lat
        buffer.putDouble(0.0); // lon
        buffer.putDouble(0.0); // turnLat
        buffer.putDouble(0.0); // turnLon
        buffer.putFloat(-1.0f); // bearing
        buffer.putFloat(-1.0f); // speed
        buffer.putFloat(-1.0f); // limit
        
        buffer.putInt(routeLats.length);
        buffer.putInt(0); // streetLen
        buffer.putInt(0); // distLen
        
        for (float lat : routeLats) buffer.putFloat(lat);
        for (float lon : routeLons) buffer.putFloat(lon);
        
        sendMessage(context, PATH_NAVIGATION, buffer.array());

        // Still send the trigger message
        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                sendMessage(context, PATH_START_NAVIGATION, new byte[0]);
            }
        });
    }

    @Override
    public void stopNavigation(@NonNull Context context) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) 0); // Inactive
        sendMessage(context, PATH_NAVIGATION, buffer.array());
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping GMS sync layer");
        mHeartbeatHandler.removeCallbacks(mHeartbeatRunnable);
        mLastReceivedTime = 0;
        unregisterManualListener();
    }


    @Override
    public void launchWatchApp(@NonNull Context context) {
        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                sendMessage(context, "/launch", new byte[0]);
            }
        });
    }

    private void addRoutePointsToDataMap(DataMap map) {
        if (!isFrameworkReady()) return;
        try {
            app.organicmaps.sdk.routing.JunctionInfo[] junctions = app.organicmaps.sdk.Framework.nativeGetRouteJunctionPoints(20.0);
            if (junctions != null && junctions.length > 0) {
                float[] lats = new float[junctions.length];
                float[] lons = new float[junctions.length];
                for (int i = 0; i < junctions.length; i++) {
                    lats[i] = (float) junctions[i].mLat;
                    lons[i] = (float) junctions[i].mLon;
                }
                map.putFloatArray("routeLats", lats);
                map.putFloatArray("routeLons", lons);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract route junctions", e);
        }
    }

    @Override
    public void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        int count = Math.min(results.length, 20);
        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendSearchResults - Results: " + results.length + " (sending " + count + "), isSearching: " + isSearching);
        int totalSize = 1; // isSearching
        List<byte[]> nameBytesList = new ArrayList<>();
        List<byte[]> descBytesList = new ArrayList<>();
        List<byte[]> distBytesList = new ArrayList<>();
        List<byte[]> featureBytesList = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            SearchResult res = results[i];
            byte[] nb = (res.getTitle(context) != null ? res.getTitle(context) : "").getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(nb);
            
            String desc = "";
            String dist = "";
            String feature = "";
            if (res.description != null) {
                if (res.description.localizedFeatureType != null) {
                    desc = res.description.localizedFeatureType;
                    feature = res.description.localizedFeatureType;
                } else if (res.description.region != null) {
                    desc = res.description.region;
                }
                
                if (res.description.distance != null && res.description.distance.isValid()) {
                    dist = res.description.distance.toString(context);
                }
            }
            byte[] db = desc.getBytes(StandardCharsets.UTF_8);
            descBytesList.add(db);
            
            byte[] distB = dist.getBytes(StandardCharsets.UTF_8);
            distBytesList.add(distB);
            
            byte[] fb = feature.getBytes(StandardCharsets.UTF_8);
            featureBytesList.add(fb);
            
            totalSize += 4 + nb.length + 4 + db.length + 8 + 8 + 4 + distB.length + 4 + fb.length;
        }
        
        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendSearchResults - Calculated payload size: " + totalSize);
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.put((byte) (isSearching ? 1 : 0));
        for (int i = 0; i < count; i++) {
            byte[] nb = nameBytesList.get(i);
            buffer.putInt(nb.length);
            buffer.put(nb);
            
            byte[] db = descBytesList.get(i);
            buffer.putInt(db.length);
            buffer.put(db);

            buffer.putDouble(results[i].lat);
            buffer.putDouble(results[i].lon);
            
            byte[] distB = distBytesList.get(i);
            buffer.putInt(distB.length);
            buffer.put(distB);
            
            byte[] fb = featureBytesList.get(i);
            buffer.putInt(fb.length);
            buffer.put(fb);
        }
        
        sendMessage(context, PATH_SEARCH_RESULTS, buffer.array());
    }

    @Override
    public void sendSearchState(@NonNull Context context, boolean isSearching) {
        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendSearchState: " + isSearching);
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) (isSearching ? 1 : 0));
        sendMessage(context, PATH_SEARCH_RESULTS, buffer.array());
    }

    @Override
    public void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        int count = Math.min(SearchRecents.getSize(), 10);
        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendSearchHistory - Items: " + count);
        int totalSize = 4;
        List<byte[]> historyBytes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] b = SearchRecents.get(i).getBytes(StandardCharsets.UTF_8);
            historyBytes.add(b);
            totalSize += 4 + b.length;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(count);
        for (byte[] b : historyBytes) {
            buffer.putInt(b.length);
            buffer.put(b);
        }
        
        sendMessage(context, PATH_SEARCH_HISTORY, buffer.array());
    }


    @Override
    public void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        sendMessage(context, "/map/download/request", countryId.getBytes());
    }

    @Override
    public void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features) {
        byte[] dataToSend = features;
        boolean compressed = false;
        if (features.length > 512) {
            try {
                dataToSend = app.organicmaps.sdk.util.GzipUtils.compress(features);
                compressed = true;
                Log.d(TAG, "DEBUG_GMS_PIPELINE: sendMapTileResponse compressed: " + features.length + " -> " + dataToSend.length);
            } catch (java.io.IOException e) {
                Log.w(TAG, "Compression failed, sending raw");
            }
        } else {
            Log.d(TAG, "DEBUG_GMS_PIPELINE: sendMapTileResponse raw: " + features.length);
        }

        ByteBuffer payload = ByteBuffer.allocate(1 + 8 + 1 + dataToSend.length);
        payload.put(PROTOCOL_VERSION);
        payload.putLong(requestId);
        payload.put((byte) (compressed ? 1 : 0));
        payload.put(dataToSend);

        Wearable.getMessageClient(context)
                .sendMessage(nodeId, PATH_MAP_TILE_RESPONSE, payload.array())
                .addOnSuccessListener(v -> Log.d(TAG, "DEBUG_GMS_PIPELINE: Successfully sent map tile response to " + nodeId + " size=" + payload.array().length))
                .addOnFailureListener(e -> Log.e(TAG, "DEBUG_GMS_PIPELINE: Failed to send map tile response", e));
    }

    @Override
    public void sendMapChunk(@NonNull Context context, @NonNull String mapId, byte[] chunk, boolean isLast) {
        // GMS uses Channels for file streaming, so this is not used or could be implemented if needed.
    }

    @Override
    public void sendMwmBytes(@NonNull Context context, @NonNull String mwmName, long offset, @NonNull byte[] data) {
        byte[] dataToSend = data;
        boolean compressed = false;
        
        if (data.length > 512) {
            try {
                dataToSend = app.organicmaps.sdk.util.GzipUtils.compress(data);
                compressed = true;
                Log.d(TAG, "DEBUG_GMS_PIPELINE: sendMwmBytes compressed: " + data.length + " -> " + dataToSend.length);
            } catch (java.io.IOException e) {
                Log.w(TAG, "Compression failed for MwmBytes, sending raw");
            }
        } else {
            Log.d(TAG, "DEBUG_GMS_PIPELINE: sendMwmBytes raw: " + data.length);
        }

        byte[] nameBytes = mwmName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(4 + nameBytes.length + 8 + 1 + dataToSend.length);
        payload.putInt(nameBytes.length);
        payload.put(nameBytes);
        payload.putLong(offset);
        payload.put((byte) (compressed ? 1 : 0));
        payload.put(dataToSend);

        sendMessage(context, PATH_VIRTUAL_MWM_DATA, payload.array());
    }

    @Override
    public void sendMwmMetadata(@NonNull Context context, @NonNull String mwmName, long totalSize) {
        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendMwmMetadata for " + mwmName + " size=" + totalSize);
        byte[] nameBytes = mwmName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(4 + nameBytes.length + 8);
        payload.putInt(nameBytes.length);
        payload.put(nameBytes);
        payload.putLong(totalSize);

        sendMessage(context, PATH_VIRTUAL_MWM_MOUNT, payload.array());
    }

    @Override
    public void requestMwmMetadata(@NonNull Context context, @NonNull String mwmName) {
        // Phone doesn't usually request this from watch
    }

    private void sendMessage(Context context, String path, byte[] data) {
        // App-level traffic reset
        app.organicmaps.wear.WearSyncService.onLocalTrafficSent();
        sendMessageInternal(context, path, data);
    }

    private void sendMessageInternal(Context context, String path, byte[] data) {
        if (context == null) return;
        
        byte[] versionedData = new byte[data.length + 1];
        versionedData[0] = PROTOCOL_VERSION;
        System.arraycopy(data, 0, versionedData, 1, data.length);
        
        Wearable.getCapabilityClient(context)
                .getCapability("organic_maps_watch_app", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener(capabilityInfo -> {
                    Set<Node> nodes = capabilityInfo.getNodes();
                    Log.d(TAG, "DEBUG_GMS_PIPELINE: sendMessageInternal to " + path + " (payload=" + versionedData.length + " bytes). Found watch nodes: " + nodes.size());
                    if (nodes.isEmpty()) {
                        // Fallback to all connected nodes if capability not found yet
                        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(allNodes -> {
                            Log.d(TAG, "DEBUG_GMS_PIPELINE: Fallback to connected nodes: " + allNodes.size());
                            for (Node node : allNodes) {
                                Wearable.getMessageClient(context).sendMessage(node.getId(), path, versionedData)
                                        .addOnSuccessListener(v -> Log.d(TAG, "DEBUG_GMS_PIPELINE: Sent message to node " + node.getDisplayName() + " at " + path))
                                        .addOnFailureListener(e -> Log.e(TAG, "DEBUG_GMS_PIPELINE: Failed to send message to node " + node.getDisplayName() + " at " + path, e));
                            }
                        });
                    } else {
                        for (Node node : nodes) {
                            Wearable.getMessageClient(context).sendMessage(node.getId(), path, versionedData)
                                    .addOnSuccessListener(v -> Log.d(TAG, "DEBUG_GMS_PIPELINE: Sent message to node " + node.getDisplayName() + " at " + path))
                                    .addOnFailureListener(e -> Log.e(TAG, "DEBUG_GMS_PIPELINE: Failed to send message to " + node.getDisplayName() + " at " + path, e));
                        }
                    }
                });
    }

    @Override
    public void sendPong(@NonNull Context context, @NonNull String nodeId) {
        byte[] pongData = new byte[1];
        pongData[0] = PROTOCOL_VERSION;
        Wearable.getMessageClient(context).sendMessage(nodeId, "/pong", pongData)
                .addOnSuccessListener(v -> Log.d(TAG, "DEBUG_GMS: Sent pong to " + nodeId));
    }

    @Override
    public void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/map/download/progress");
        DataMap map = putDataMapReq.getDataMap();
        map.putByte("protocolVersion", PROTOCOL_VERSION);
        map.putString("countryId", countryId);
        map.putInt("progress", progress);
        map.putLong("timestamp", System.currentTimeMillis());
        
        Log.d(TAG, "sendMapProgress: " + countryId + " -> " + progress + "%");
        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void sendRouteBuildProgress(@NonNull Context context, int progress) {
        Log.d(TAG, "sendRouteBuildProgress: " + progress + "%");
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(progress);
        sendMessage(context, "/navigation/route_build_progress", buffer.array());
    }

    @Override
    public void sendMapNotFound(@NonNull Context context, @NonNull String mapId) {
        sendMessage(context, "/map/download/not_found", mapId.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendTrackRecordingStatus(@NonNull Context context, boolean isRecording) {
        long startTime = app.organicmaps.location.TrackRecordingService.getRecordingStartTime();
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8);
        buffer.put((byte) (isRecording ? 1 : 0));
        buffer.putLong(startTime);
        sendMessage(context, PATH_TRACK_RECORDING, buffer.array());
    }

    @Override
    public void sendBookmarkCategories(@NonNull Context context, @NonNull List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories) {
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        int count = Math.min(categories.size(), 50);
        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendBookmarkCategories - Count: " + categories.size() + " (sending " + count + ")");
        
        int totalSize = 4;
        List<byte[]> nameBytesList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = categories.get(i);
            byte[] nb = cat.getName().getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(nb);
            totalSize += 8 + 4 + nb.length + 1 + 4 + 4 + 8; // id(8) + nameLen(4) + name + visible(1) + bmkCount(4) + trkCount(4) + timestamp(8)
        }

        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendBookmarkCategories - Calculated payload size: " + totalSize);
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(count);
        for (int i = 0; i < count; i++) {
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = categories.get(i);
            Log.d(TAG, "DEBUG_GMS_PIPELINE: Buffering bookmark category for transmission: " + cat.getName() + " (ID: " + cat.getId() + ")");
            byte[] nb = nameBytesList.get(i);
            buffer.putLong(cat.getId());
            buffer.putInt(nb.length);
            buffer.put(nb);
            buffer.put((byte) (cat.isVisible() ? 1 : 0));
            buffer.putInt(cat.getBookmarksCount());
            buffer.putInt(cat.getTracksCount());
            buffer.putLong(syncPrefs.getLong(cat.getName(), 0));
        }
        
        sendMessage(context, PATH_BOOKMARKS, buffer.array());
    }


    @Override
    public void sendBookmarkFile(@NonNull Context context, @NonNull String categoryName, @NonNull byte[] data, boolean isLast) {
        Log.d(TAG, "DEBUG_GMS_PIPELINE: sendBookmarkFile for " + categoryName + " size=" + data.length + " isLast=" + isLast);
        byte[] nameBytes = categoryName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + 4 + nameBytes.length + data.length);
        payload.put((byte) (isLast ? 1 : 0));
        payload.putInt(nameBytes.length);
        payload.put(nameBytes);
        payload.put(data);

        sendMessage(context, PATH_BOOKMARK_FILE, payload.array());
        
        if (isLast) {
            context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
                   .edit().putLong(categoryName, System.currentTimeMillis()).apply();
        }
    }

    @Override
    public void renameBookmarkCategory(@NonNull Context context, @NonNull String oldName, @NonNull String newName) {
        Log.d(TAG, "DEBUG_GMS_PIPELINE: renameBookmarkCategory: " + oldName + " -> " + newName);
        byte[] oldBytes = oldName.getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = newName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(4 + oldBytes.length + 4 + newBytes.length);
        payload.putInt(oldBytes.length);
        payload.put(oldBytes);
        payload.putInt(newBytes.length);
        payload.put(newBytes);

        sendMessage(context, PATH_BOOKMARK_RENAME, payload.array());
        
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        long ts = syncPrefs.getLong(oldName, 0);
        syncPrefs.edit().remove(oldName).putLong(newName, ts > 0 ? ts : System.currentTimeMillis()).apply();
    }

    @Override
    public void deleteBookmarkCategory(@NonNull Context context, @NonNull String name) {
        Log.d(TAG, "DEBUG_GMS_PIPELINE: deleteBookmarkCategory: " + name);
        sendMessage(context, PATH_BOOKMARK_DELETE, name.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
               .edit().remove(name).apply();
    }

    @Override
    public void sendBackendSwitch(@NonNull Context context, @NonNull String newBackend) {
        sendMessage(context, "/backend/switch", newBackend.getBytes());
    }

    private final java.util.Map<String, Thread> mStreamingThreads = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void streamMapFile(@NonNull Context context, @NonNull String nodeId, @NonNull String mapId, @NonNull java.io.File file) {
        try {
            com.google.android.gms.wearable.ChannelClient channelClient = Wearable.getChannelClient(context);
            channelClient.openChannel(nodeId, "/map/stream/data/" + mapId)
                    .addOnSuccessListener(channel -> {
                        Thread thread = new Thread(() -> {
                            Log.d(TAG, "DEBUG_GMS_PIPELINE: GMS Channel opened for " + mapId + " (File size: " + file.length() + "), starting manual stream");
                            long totalBytes = file.length();
                            boolean success = false;
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
                                 java.io.OutputStream out = com.google.android.gms.tasks.Tasks.await(channelClient.getOutputStream(channel))) {
                                byte[] buffer = new byte[64 * 1024];
                                int bytesRead;
                                long totalSent = 0;
                                int lastReportedProgress = -1;
                                
                                while ((bytesRead = fis.read(buffer)) != -1) {
                                    if (Thread.interrupted()) {
                                        Log.d(TAG, "DEBUG_GMS_PIPELINE: GMS streaming for " + mapId + " was CANCELLED");
                                        return;
                                    }
                                    out.write(buffer, 0, bytesRead);
                                    totalSent += bytesRead;
                                    int progress = (int) (totalSent * 100 / totalBytes);
                                    if (progress > lastReportedProgress) {
                                        lastReportedProgress = progress;
                                        if (progress % 5 == 0) {
                                            Log.d(TAG, "DEBUG_GMS_PIPELINE: GMS Streaming " + mapId + " progress: " + progress + "% (" + totalSent + "/" + totalBytes + ")");
                                        }
                                        sendMapProgress(context, mapId, progress);
                                        app.organicmaps.wear.WearCompanionNotificationManager.showServingNotification(context, mapId, progress);
                                    }
                                }
                                out.flush();
                                success = true;
                                Log.d(TAG, "DEBUG_GMS_PIPELINE: GMS manual stream completed for " + mapId);
                            } catch (Exception e) {
                                if (e instanceof InterruptedException) {
                                    Log.d(TAG, "DEBUG_GMS_PIPELINE: GMS streaming for " + mapId + " was INTERRUPTED");
                                } else {
                                    Log.e(TAG, "DEBUG_GMS_PIPELINE: GMS Channel manual streaming failed for " + mapId, e);
                                }
                            } finally {
                                if (!success) {
                                    sendMapNotFound(context, mapId);
                                }
                                mStreamingThreads.remove(mapId);
                                channelClient.close(channel);
                                app.organicmaps.wear.WearCompanionNotificationManager.hideNotification(context, app.organicmaps.wear.WearCompanionNotificationManager.NOTIFICATION_ID_MAP_SYNC);
                            }
                        });
                        mStreamingThreads.put(mapId, thread);
                        thread.start();
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "DEBUG_GMS_PIPELINE: Failed to open GMS channel for " + mapId, e));
        } catch (Exception e) {
            Log.e(TAG, "DEBUG_GMS_PIPELINE: GMS Channel streaming failed", e);
        }
    }

    @Override
    public void cancelStreaming(@NonNull String mapId) {
        Thread t = mStreamingThreads.remove(mapId);
        if (t != null) {
            Log.d(TAG, "Cancelling GMS streaming thread for " + mapId);
            t.interrupt();
        }
    }

    @Override
    public void checkConnection(@NonNull Context context, @NonNull ConnectionCallback callback) {
        Wearable.getNodeClient(context).getConnectedNodes().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                boolean nearby = false;
                for (Node node : task.getResult()) {
                    if (node.isNearby()) {
                        nearby = true;
                        break;
                    }
                }
                callback.onConnectionResult(true, nearby ? ConnectionType.BLUETOOTH : ConnectionType.GMS);
            } else {
                callback.onConnectionResult(false, ConnectionType.NONE);
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
            Log.e(TAG, "Protocol version mismatch in preferences: " + dataMap.getByte("protocolVersion"));
            return;
        }

        long timestamp = dataMap.getLong("timestamp", 0);
        long lastApplied = prefs.getLong("pref_wear_os_last_sync_timestamp", 0);
        if (timestamp > 0 && timestamp <= lastApplied) return;

        mIsApplyingPreferences = true;
        try {
            boolean mapEnabled = dataMap.getBoolean("mapEnabled", false);
            boolean watchLocalMode = dataMap.getBoolean("watchLocalMode", false);
            boolean standaloneMode = dataMap.getBoolean("standaloneMode", false);
            boolean autoDownload = dataMap.getBoolean("autoDownloadRouteMaps", true);
            String backend = dataMap.getString("backend", "GMS");
            String mapDownloadMode = dataMap.getString("mapDownloadMode", "PHONE_SYNC");
            String locationSource = dataMap.getString("locationSource", "AUTO");
            int poiMask = dataMap.getInt("poiCategoriesMask", 0x3F);

            boolean is3dEnabled = dataMap.getBoolean("is3dEnabled", true);
            boolean is3dBuildingsEnabled = dataMap.getBoolean("is3dBuildingsEnabled", true);
            boolean isAutoZoomEnabled = dataMap.getBoolean("isAutoZoomEnabled", true);
            int mUnits = dataMap.getInt("measurementUnits", 0);
            String mapStyle = dataMap.getString("mapStyle", "default");

            boolean transitEnabled = dataMap.getBoolean("transitEnabled", false);
            boolean bikingEnabled = dataMap.getBoolean("bikingEnabled", false);
            boolean hikingEnabled = dataMap.getBoolean("hikingEnabled", false);
            boolean isolinesEnabled = dataMap.getBoolean("isolinesEnabled", false);

            boolean avoidTolls = dataMap.getBoolean("avoidTolls", false);
            boolean avoidMotorways = dataMap.getBoolean("avoidMotorways", false);
            boolean avoidFerries = dataMap.getBoolean("avoidFerries", false);
            boolean avoidUnpaved = dataMap.getBoolean("avoidUnpaved", false);
            boolean syncNotificationsEnabled = dataMap.getBoolean("syncNotificationsEnabled", true);
            boolean isTrackRecording = dataMap.getBoolean("isTrackRecording", false);
            long trackRecordingStartTime = dataMap.getLong("trackRecordingStartTime", 0);

            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putLong("pref_wear_os_last_sync_timestamp", timestamp);

            String oldBackend = prefs.getString(context.getString(app.organicmaps.R.string.pref_wear_os_backend), "GMS");

            // Only put if value is different to avoid unnecessary triggers
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_map_enabled), mapEnabled);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_watch_local_mode), watchLocalMode);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_standalone_mode), standaloneMode);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_auto_download_route_maps), autoDownload);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_backend), backend);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_map_download_mode), mapDownloadMode);
            putIfChanged(editor, prefs, "locationSource", locationSource);
            putIfChanged(editor, prefs, "poiCategoriesMask", poiMask);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_3d), is3dEnabled);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_3d_buildings), is3dBuildingsEnabled);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_auto_zoom), isAutoZoomEnabled);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_munits), String.valueOf(mUnits));
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_map_style), mapStyle);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_avoid_tolls), avoidTolls);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_avoid_motorways), avoidMotorways);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_avoid_ferries), avoidFerries);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_avoid_unpaved), avoidUnpaved);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_transit), transitEnabled);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_biking), bikingEnabled);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_hiking), hikingEnabled);
            putIfChanged(editor, prefs, context.getString(app.organicmaps.R.string.pref_wear_os_isolines), isolinesEnabled);
            putIfChanged(editor, prefs, "pref_sync_notifications", syncNotificationsEnabled);
            putIfChanged(editor, prefs, "pref_track_recording_active", isTrackRecording);
            putIfChanged(editor, prefs, "pref_track_recording_start_time", trackRecordingStartTime);
            
            editor.apply();
            app.organicmaps.wear.WearSyncService.onRemotePreferencesApplied();

            // Re-initialize sync layer ONLY if backend changed
            if (!backend.equals(oldBackend)) {
                app.organicmaps.wear.WearSyncService.initSyncLayer(context);
            }
            context.sendBroadcast(new android.content.Intent("app.organicmaps.wear.SETTINGS_CHANGED"));

        } finally {
            mIsApplyingPreferences = false;
        }
    }

    private void putIfChanged(android.content.SharedPreferences.Editor editor, android.content.SharedPreferences prefs, String key, Object value) {
        if (value instanceof Boolean) {
            if (!prefs.contains(key) || prefs.getBoolean(key, !((Boolean) value)) != (Boolean) value) {
                editor.putBoolean(key, (Boolean) value);
            }
        } else if (value instanceof String) {
            if (!prefs.contains(key) || !value.equals(prefs.getString(key, null))) {
                editor.putString(key, (String) value);
            }
        } else if (value instanceof Integer) {
            if (!prefs.contains(key) || prefs.getInt(key, ((Integer) value) + 1) != (Integer) value) {
                editor.putInt(key, (Integer) value);
            }
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

    private long mLastMsgHash = 0;
    private long mLastMsgTime = 0;

    @Override
    public void notifyMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        if (data.length < 1) {
            Log.e(TAG, "DEBUG_GMS_PIPELINE: Received empty message at " + path);
            return;
        }
        byte version = data[0];
        if (version != PROTOCOL_VERSION) {
            Log.e(TAG, "DEBUG_GMS_PIPELINE: Protocol version mismatch at " + path + ": received=" + version + ", expected=" + PROTOCOL_VERSION);
            return;
        }
        byte[] payload = new byte[data.length - 1];
        System.arraycopy(data, 1, payload, 0, payload.length);

        long hash = path.hashCode() ^ java.util.Arrays.hashCode(payload);
        long now = System.currentTimeMillis();
        
        // Robust deduplication (500ms) to ignore redundant listeners
        if (hash == mLastMsgHash && (now - mLastMsgTime) < 500) {
            Log.d(TAG, "DEBUG_GMS_PIPELINE: notifyMessageReceived IGNORED (Deduplication): " + path);
            return;
        }
        
        mLastMsgHash = hash;
        mLastMsgTime = now;
        mLastReceivedTime = now;
        
        Log.d(TAG, "DEBUG_GMS_PIPELINE: notifyMessageReceived: " + path + " from " + sourceNodeId + " (payload=" + payload.length + " bytes) listeners: " + mListeners.size());

        // Ensure watch->phone requests are handled even if the WearableListenerService isn't running.
        // (The manual MessageClient listener receives messages, but WearSyncService listeners may be empty.)
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context != null) {
            WearMessageRouter.onMessageReceived(context.getApplicationContext(), path, payload, sourceNodeId);
        }

        for (MessageListener listener : mListeners) {
            listener.onMessageReceived(path, payload, sourceNodeId);
        }
    }

    // This would be called from a WearableListenerService proxy
    public void notifyMessageReceived(MessageEvent event) {
        notifyMessageReceived(event.getPath(), event.getData(), event.getSourceNodeId());
    }
}
