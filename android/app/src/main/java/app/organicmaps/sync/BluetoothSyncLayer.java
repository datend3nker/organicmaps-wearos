package app.organicmaps.sync;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.settings.RoadType;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.GzipUtils;

/**
 * OSS implementation of ISyncLayer using standard Bluetooth RFCOMM Sockets.
 */
public class BluetoothSyncLayer implements ISyncLayer {
    private static final String TAG = "BluetoothSyncLayer";
    private static final UUID OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130");

    private static final byte MSG_TYPE_NAV_STATUS = 1;
    private static final byte MSG_TYPE_SEARCH_RESULTS = 2;
    private static final byte MSG_TYPE_SEARCH_HISTORY = 3;
    private static final byte MSG_TYPE_PREFERENCES = 4;
    private static final byte MSG_TYPE_MAP_DOWNLOAD = 5;
    private static final byte MSG_TYPE_MAP_TILE_RESPONSE = 6;
    private static final byte MSG_TYPE_MAP_PROGRESS = 7;
    private static final byte MSG_TYPE_TRACK_RECORDING = 8;
    private static final byte MSG_TYPE_BOOKMARKS = 9;
    private static final byte MSG_TYPE_COMMAND = 10;
    private static final byte MSG_TYPE_VIRTUAL_MWM_MOUNT = 15;
    private static final byte MSG_TYPE_ROUTE_BUILD_PROGRESS = 16;
    private static final byte MSG_TYPE_MAP_CHUNK = 11;
    private static final byte MSG_TYPE_BOOKMARK_FILE = 12;
    private static final byte MSG_TYPE_BOOKMARK_RENAME = 17;
    private static final byte MSG_TYPE_BOOKMARK_DELETE = 18;
    private static final byte MSG_TYPE_VIRTUAL_MWM_REQUEST = 13;
    private static final byte MSG_TYPE_VIRTUAL_MWM_DATA = 14;
    private static final byte MSG_TYPE_PREFERENCES_UPDATES = 19;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private BluetoothSocket mActiveSocket = null;
    private BluetoothServerSocket mServerSocket = null;
    private final List<MessageListener> mListeners = new CopyOnWriteArrayList<>();
    private boolean mIsListening = false;
    private boolean mIsServerRunning = false;
    private long mLastReceivedTime = 0;
    private long mLastPingSentTime = 0;
    private long mCurrentPingInterval = 15000; // 15 seconds
    private static final long CONNECTION_TIMEOUT = 120000; // 2 minutes (increased from 40s)
    private boolean mIsApplyingPreferences = false;
    private final android.os.Handler mHeartbeatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            boolean isAlive = (now - mLastReceivedTime) < CONNECTION_TIMEOUT;
            
            if (isAlive) {
                mCurrentPingInterval = 15000;
            } else {
                mCurrentPingInterval = Math.min((long)(mCurrentPingInterval * 1.5), 300000L);
            }

            if (now - mLastReceivedTime > mCurrentPingInterval) {
                if (now - mLastPingSentTime > mCurrentPingInterval) {
                    Log.d(TAG, "DEBUG_BT: Heartbeat (" + (isAlive ? "Alive" : "Backoff") + " " + mCurrentPingInterval + "ms) - sending ping");
                    sendRawMessage(app.organicmaps.MwmApplication.sInstance, MSG_TYPE_COMMAND, buildCommandPayload("/ping", new byte[0]));
                    mLastPingSentTime = now;
                }
            }
            
            mHeartbeatHandler.postDelayed(this, 10000);
        }
    };

    public BluetoothSyncLayer() {
        startConnectionListener();
        startHeartbeat();
    }

    private void startHeartbeat() {
        mHeartbeatHandler.removeCallbacks(mHeartbeatRunnable);
        mHeartbeatHandler.postDelayed(mHeartbeatRunnable, 15000);
    }

    private byte[] buildCommandPayload(String path, byte[] data) {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + pathBytes.length + data.length);
        buffer.putInt(pathBytes.length);
        buffer.put(pathBytes);
        buffer.put(data);
        return buffer.array();
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

        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferences (Full Sync) - Items: " + all.size());

        // BUFFER Format: [4:count] { [4:keyLen][key][1:type][4:valLen][val][8:timestamp] }
        int totalSize = 4;
        List<byte[]> keyBytesList = new ArrayList<>();
        List<byte[]> valBytesList = new ArrayList<>();
        
        for (app.organicmaps.wear.SettingsSyncManager.SettingUpdate update : all) {
            byte[] kb = update.key.getBytes(StandardCharsets.UTF_8);
            keyBytesList.add(kb);
            byte[] vb = serializeValue(update.value);
            valBytesList.add(vb);
            totalSize += 4 + kb.length + 1 + 4 + vb.length + 8;
        }

        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferences - Calculated Buffer Size: " + totalSize + " bytes");
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(all.size());
        for (int i = 0; i < all.size(); i++) {
            app.organicmaps.wear.SettingsSyncManager.SettingUpdate update = all.get(i);
            byte[] kb = keyBytesList.get(i);
            byte[] vb = valBytesList.get(i);
            
            buffer.putInt(kb.length);
            buffer.put(kb);
            buffer.put(getValueType(update.value));
            buffer.putInt(vb.length);
            buffer.put(vb);
            buffer.putLong(update.timestamp);
        }
        
        sendRawMessage(context, MSG_TYPE_PREFERENCES, buffer.array());
        app.organicmaps.wear.SettingsSyncManager.getInstance(context).markAsSynced(all);
    }

    @Override
    public void syncPreferenceUpdates(@NonNull Context context, @NonNull List<app.organicmaps.wear.SettingsSyncManager.SettingUpdate> updates) {
        if (mIsApplyingPreferences || updates.isEmpty()) return;
        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferenceUpdates (Buffered) - Items: " + updates.size());

        int totalSize = 4;
        List<byte[]> keyBytesList = new ArrayList<>();
        List<byte[]> valBytesList = new ArrayList<>();
        
        for (app.organicmaps.wear.SettingsSyncManager.SettingUpdate update : updates) {
            byte[] kb = update.key.getBytes(StandardCharsets.UTF_8);
            keyBytesList.add(kb);
            byte[] vb = serializeValue(update.value);
            valBytesList.add(vb);
            totalSize += 4 + kb.length + 1 + 4 + vb.length + 8;
        }

        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferenceUpdates - Calculated Buffer Size: " + totalSize + " bytes");
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(updates.size());
        for (int i = 0; i < updates.size(); i++) {
            app.organicmaps.wear.SettingsSyncManager.SettingUpdate update = updates.get(i);
            Log.d(TAG, "DEBUG_BT_PIPELINE: Buffering setting for transmission: " + update.key + " = " + update.value);
            byte[] kb = keyBytesList.get(i);
            byte[] vb = valBytesList.get(i);
            
            buffer.putInt(kb.length);
            buffer.put(kb);
            buffer.put(getValueType(update.value));
            buffer.putInt(vb.length);
            buffer.put(vb);
            buffer.putLong(update.timestamp);
        }
        
        sendRawMessage(context, MSG_TYPE_PREFERENCES_UPDATES, buffer.array());
        app.organicmaps.wear.SettingsSyncManager.getInstance(context).markAsSynced(updates);
    }

    private byte getValueType(Object v) {
        if (v instanceof Boolean) return 1;
        if (v instanceof String) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        return 0;
    }

    private byte[] serializeValue(Object v) {
        if (v instanceof Boolean) return new byte[]{(byte)((Boolean)v ? 1 : 0)};
        if (v instanceof String) return ((String)v).getBytes(StandardCharsets.UTF_8);
        if (v instanceof Integer) return ByteBuffer.allocate(4).putInt((Integer)v).array();
        if (v instanceof Long) return ByteBuffer.allocate(8).putLong((Long)v).array();
        return new byte[0];
    }

    @Override
    public void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        byte[] streetBytes = info.nextStreet != null ? info.nextStreet.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] distBytes = info.distToTurn != null ? info.distToTurn.toString(context).getBytes(StandardCharsets.UTF_8) : new byte[0];
        
        // BUFFER Format: [1:active][1:carDir][1:pedDir][1:exit][4:progress][8:lat][8:lon][8:turnLat][8:turnLon][4:bearing][4:speed][4:limit][4:routeLen][4:streetLen][4:distLen][street][dist]
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 1 + 1 + 4 + 8 + 8 + 8 + 8 + 4 + 4 + 4 + 4 + 4 + 4 + streetBytes.length + distBytes.length);
        buffer.put((byte) (app.organicmaps.sdk.routing.RoutingController.get().isNavigating() ? 1 : 0)); 
        buffer.put((byte) info.carDirection.ordinal());
        buffer.put((byte) info.pedestrianDirection.ordinal());
        buffer.put((byte) info.exitNum);
        buffer.putFloat((float) info.completionPercent);
        buffer.putDouble(location != null ? location.getLatitude() : 0.0);
        buffer.putDouble(location != null ? location.getLongitude() : 0.0);
        buffer.putDouble(info.turnLat);
        buffer.putDouble(info.turnLon);
        
        buffer.putFloat(location != null && location.hasBearing() ? location.getBearing() : -1.0f);
        buffer.putFloat(location != null ? (float) location.getSpeed() : -1.0f);
        buffer.putFloat((float) info.speedLimitMps);

        buffer.putInt(0); // routeLen
        buffer.putInt(streetBytes.length);
        buffer.putInt(distBytes.length);
        buffer.put(streetBytes);
        buffer.put(distBytes);
        
        sendRawMessage(context, MSG_TYPE_NAV_STATUS, buffer.array());
    }

    @Override
    public void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        
        if (!isFrameworkReady()) return;
        
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

        // Send a message with just the route points if navigating
        if (app.organicmaps.sdk.routing.RoutingController.get().isNavigating() || app.organicmaps.sdk.routing.RoutingController.get().isBuilt()) {
             // BUFFER Format: [1:active][1:carDir][1:pedDir][1:exit][4:progress][8:lat][8:lon][8:turnLat][8:turnLon][4:bearing][4:speed][4:limit][4:routeLen][4:streetLen][4:distLen][street][dist][route]
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
             
             // route points
             for (float lat : routeLats) buffer.putFloat(lat);
             for (float lon : routeLons) buffer.putFloat(lon);
             
             sendRawMessage(context, MSG_TYPE_NAV_STATUS, buffer.array());
        }
    }

    @Override
    public void stopNavigation(@NonNull Context context) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) 0); // Inactive
        sendRawMessage(context, MSG_TYPE_NAV_STATUS, buffer.array());
    }

    @Override
    public void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        int count = Math.min(results.length, 15);
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendSearchResults - Results: " + results.length + " (sending " + count + "), isSearching: " + isSearching);
        int calcTotalSize = 1; // isSearching
        List<byte[]> nameBytesList = new ArrayList<>();
        List<byte[]> descBytesList = new ArrayList<>();
        List<byte[]> distBytesList = new ArrayList<>();
        List<byte[]> featureBytesList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SearchResult res = results[i];
            byte[] nb = res.getTitle(context).getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(nb);
            
            String desc = "";
            String dist = "";
            String feature = "";
            if (res.description != null) {
                if (res.description.localizedFeatureType != null) {
                    desc = res.description.localizedFeatureType;
                    feature = res.description.localizedFeatureType; // Use as fallback for icon
                }
                else if (res.description.region != null) desc = res.description.region;
                
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
            
            calcTotalSize += 4 + nb.length + 4 + db.length + 8 + 8 + 4 + distB.length + 4 + fb.length;
        }
        
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendSearchResults - Buffer size: " + calcTotalSize);
        ByteBuffer buffer = ByteBuffer.allocate(calcTotalSize);
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
        sendRawMessage(context, MSG_TYPE_SEARCH_RESULTS, buffer.array());
    }

    @Override
    public void sendSearchState(@NonNull Context context, boolean isSearching) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendSearchState: " + isSearching);
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) (isSearching ? 1 : 0));
        sendRawMessage(context, MSG_TYPE_SEARCH_RESULTS, buffer.array());
    }

    @Override
    public void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        int count = Math.min(SearchRecents.getSize(), 5);
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendSearchHistory - Items: " + count);
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
        sendRawMessage(context, MSG_TYPE_SEARCH_HISTORY, buffer.array());
    }

    @Override
    public void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        sendRawMessage(context, MSG_TYPE_MAP_DOWNLOAD, countryId.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features) {
        mExecutor.execute(() -> {
            byte[] dataToSend = features;
            boolean compressed = false;
            if (features.length > 512) {
                try {
                    dataToSend = GzipUtils.compress(features);
                    compressed = true;
                    Log.d(TAG, "DEBUG_BT_PIPELINE: sendMapTileResponse compressed: " + features.length + " -> " + dataToSend.length);
                } catch (IOException e) {
                    Log.w(TAG, "Compression failed, sending raw");
                }
            } else {
                Log.d(TAG, "DEBUG_BT_PIPELINE: sendMapTileResponse raw: " + features.length);
            }

            ByteBuffer buffer = ByteBuffer.allocate(8 + 1 + dataToSend.length);
            buffer.putLong(requestId);
            buffer.put((byte) (compressed ? 1 : 0));
            buffer.put(dataToSend);
            sendRawMessage(context, MSG_TYPE_MAP_TILE_RESPONSE, buffer.array());
        });
    }

    @Override
    public void sendMapChunk(@NonNull Context context, @NonNull String mapId, byte[] chunk, boolean isLast) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendMapChunk for " + mapId + " size=" + chunk.length + " isLast=" + isLast);
        byte[] mapIdBytes = mapId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + mapIdBytes.length + 1 + chunk.length);
        buffer.putInt(mapIdBytes.length);
        buffer.put(mapIdBytes);
        buffer.put((byte) (isLast ? 1 : 0));
        buffer.put(chunk);
        sendRawMessage(context, MSG_TYPE_MAP_CHUNK, buffer.array());
    }

    @Override
    public void sendMwmBytes(@NonNull Context context, @NonNull String mwmName, long offset, @NonNull byte[] data) {
        byte[] dataToSend = data;
        boolean compressed = false;
        
        if (data.length > 512) {
            try {
                dataToSend = GzipUtils.compress(data);
                compressed = true;
                Log.d(TAG, "DEBUG_BT_PIPELINE: sendMwmBytes compressed: " + data.length + " -> " + dataToSend.length);
            } catch (IOException e) {
                Log.w(TAG, "Compression failed for MwmBytes, sending raw");
            }
        } else {
            Log.d(TAG, "DEBUG_BT_PIPELINE: sendMwmBytes raw: " + data.length);
        }

        byte[] nameBytes = mwmName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + nameBytes.length + 8 + 1 + dataToSend.length);
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);
        buffer.putLong(offset);
        buffer.put((byte) (compressed ? 1 : 0));
        buffer.put(dataToSend);
        sendRawMessage(context, MSG_TYPE_VIRTUAL_MWM_DATA, buffer.array());
    }

    @Override
    public void sendMwmMetadata(@NonNull Context context, @NonNull String mwmName, long totalSize) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendMwmMetadata for " + mwmName + " size=" + totalSize);
        byte[] nameBytes = mwmName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + nameBytes.length + 8);
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);
        buffer.putLong(totalSize);

        sendRawMessage(context, MSG_TYPE_VIRTUAL_MWM_MOUNT, buffer.array()); 
    }

    @Override
    public void requestMwmMetadata(@NonNull Context context, @NonNull String mwmName) {
        // Phone doesn't usually request this
    }

    @Override
    public void sendPong(@NonNull Context context, @NonNull String nodeId) {
        // Send a command type message with path /pong
        byte[] pathBytes = "/pong".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + pathBytes.length);
        buffer.putInt(pathBytes.length);
        buffer.put(pathBytes);
        sendRawMessage(context, MSG_TYPE_COMMAND, buffer.array());
    }

    @Override
    public void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress) {
        byte[] countryBytes = countryId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + countryBytes.length + 4);
        buffer.putInt(countryBytes.length);
        buffer.put(countryBytes);
        buffer.putInt(progress);
        
        // MSG_TYPE 7 for progress
        sendRawMessage(context, MSG_TYPE_MAP_PROGRESS, buffer.array());
    }

    @Override
    public void sendRouteBuildProgress(@NonNull Context context, int progress) {
        Log.d(TAG, "sendRouteBuildProgress: " + progress + "%");
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(progress);
        sendRawMessage(context, MSG_TYPE_ROUTE_BUILD_PROGRESS, buffer.array());
    }

    @Override
    public void sendMapNotFound(@NonNull Context context, @NonNull String mapId) {
        byte[] pathBytes = "/map/download/not_found".getBytes(StandardCharsets.UTF_8);
        byte[] mapBytes = mapId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + pathBytes.length + mapBytes.length);
        buffer.putInt(pathBytes.length);
        buffer.put(pathBytes);
        buffer.put(mapBytes);
        sendRawMessage(context, MSG_TYPE_COMMAND, buffer.array());
    }

    @Override
    public void sendTrackRecordingStatus(@NonNull Context context, boolean isRecording) {
        long startTime = app.organicmaps.location.TrackRecordingService.getRecordingStartTime();
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8);
        buffer.put((byte) (isRecording ? 1 : 0));
        buffer.putLong(startTime);
        sendRawMessage(context, MSG_TYPE_TRACK_RECORDING, buffer.array());
    }

    @Override
    public void sendBookmarkCategories(@NonNull Context context, @NonNull List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories) {
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        int count = Math.min(categories.size(), 20);
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendBookmarkCategories - Count: " + categories.size() + " (sending " + count + ")");
        int totalSize = 4;
        List<byte[]> nameBytesList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = categories.get(i);
            byte[] nb = cat.getName().getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(nb);
            totalSize += 8 + 4 + nb.length + 1 + 4 + 4 + 8; // id(8) + nameLen(4) + name + visible(1) + bmkCount(4) + trkCount(4) + ts(8)
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(count);
        for (int i = 0; i < count; i++) {
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = categories.get(i);
            Log.d(TAG, "DEBUG_BT_PIPELINE: Buffering bookmark category for transmission: " + cat.getName() + " (ID: " + cat.getId() + ")");
            byte[] nb = nameBytesList.get(i);
            buffer.putLong(cat.getId());
            buffer.putInt(nb.length);
            buffer.put(nb);
            buffer.put((byte) (cat.isVisible() ? 1 : 0));
            buffer.putInt(cat.getBookmarksCount());
            buffer.putInt(cat.getTracksCount());
            buffer.putLong(syncPrefs.getLong(cat.getName(), 0));
        }
        sendRawMessage(context, MSG_TYPE_BOOKMARKS, buffer.array());
    }

    @Override
    public void sendBookmarkFile(@NonNull Context context, @NonNull String categoryName, @NonNull byte[] data, boolean isLast) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendBookmarkFile for " + categoryName + " size=" + data.length + " isLast=" + isLast);
        byte[] nameBytes = categoryName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + nameBytes.length + data.length);
        buffer.put((byte) (isLast ? 1 : 0));
        buffer.putInt(nameBytes.length);
        buffer.put(nameBytes);
        buffer.put(data);
        sendRawMessage(context, MSG_TYPE_BOOKMARK_FILE, buffer.array());
        
        if (isLast) {
            context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
                   .edit().putLong(categoryName, System.currentTimeMillis()).apply();
        }
    }

    @Override
    public void renameBookmarkCategory(@NonNull Context context, @NonNull String oldName, @NonNull String newName) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: renameBookmarkCategory: " + oldName + " -> " + newName);
        byte[] oldBytes = oldName.getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = newName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + oldBytes.length + 4 + newBytes.length);
        buffer.putInt(oldBytes.length);
        buffer.put(oldBytes);
        buffer.putInt(newBytes.length);
        buffer.put(newBytes);
        sendRawMessage(context, MSG_TYPE_BOOKMARK_RENAME, buffer.array());
        
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        long ts = syncPrefs.getLong(oldName, 0);
        syncPrefs.edit().remove(oldName).putLong(newName, ts > 0 ? ts : System.currentTimeMillis()).apply();
    }

    @Override
    public void deleteBookmarkCategory(@NonNull Context context, @NonNull String name) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: deleteBookmarkCategory: " + name);
        sendRawMessage(context, MSG_TYPE_BOOKMARK_DELETE, name.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
               .edit().remove(name).apply();
    }

    @Override
    public void sendBackendSwitch(@NonNull Context context, @NonNull String newBackend) {
        byte[] pathBytes = "/backend/switch".getBytes(StandardCharsets.UTF_8);
        byte[] backendBytes = newBackend.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + pathBytes.length + backendBytes.length);
        buffer.putInt(pathBytes.length);
        buffer.put(pathBytes);
        buffer.put(backendBytes);
        sendRawMessage(context, MSG_TYPE_COMMAND, buffer.array());
    }

    private final java.util.Map<String, Thread> mStreamingThreads = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void streamMapFile(@NonNull Context context, @NonNull String nodeId, @NonNull String mapId, @NonNull java.io.File file) {
        Thread thread = new Thread(() -> {
            Log.d(TAG, "DEBUG_BT_PIPELINE: Starting map stream thread for " + mapId + " (File size: " + file.length() + ")");
            long totalBytes = file.length();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[32 * 1024]; 
                int bytesRead;
                long totalSent = 0;
                int lastReportedProgress = -1;

                app.organicmaps.wear.WearCompanionNotificationManager.showServingNotification(context, mapId, 0);

                while ((bytesRead = fis.read(buffer)) != -1) {
                    if (Thread.interrupted()) {
                        Log.d(TAG, "DEBUG_BT_PIPELINE: Streaming for " + mapId + " was CANCELLED");
                        return;
                    }
                    byte[] chunk = bytesRead == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, bytesRead);
                    totalSent += bytesRead;
                    boolean isLast = totalSent >= totalBytes;
                    
                    sendMapChunk(context, mapId, chunk, isLast);
                    
                    int progress = (int) (totalSent * 100 / totalBytes);
                    if (progress > lastReportedProgress) {
                        lastReportedProgress = progress;
                        if (progress % 5 == 0) {
                            Log.d(TAG, "DEBUG_BT_PIPELINE: Streaming " + mapId + " progress: " + progress + "% (" + totalSent + "/" + totalBytes + ")");
                        }
                        app.organicmaps.wear.WearCompanionNotificationManager.showServingNotification(context, mapId, progress);
                        app.organicmaps.wear.WearSyncService.sendMapProgress(context, mapId, progress);
                    }
                    
                    try { Thread.sleep(10); } catch (InterruptedException e) {
                        Log.d(TAG, "DEBUG_BT_PIPELINE: Streaming for " + mapId + " was INTERRUPTED");
                        return;
                    }
                }
                Log.d(TAG, "DEBUG_BT_PIPELINE: Finished streaming " + mapId + " successfully.");
            } catch (java.io.IOException e) {
                Log.e(TAG, "DEBUG_BT_PIPELINE: Error streaming map " + mapId + ": " + e.getMessage());
            } finally {
                mStreamingThreads.remove(mapId);
                app.organicmaps.wear.WearCompanionNotificationManager.hideNotification(context, app.organicmaps.wear.WearCompanionNotificationManager.NOTIFICATION_ID_MAP_SYNC);
            }
        });
        mStreamingThreads.put(mapId, thread);
        thread.start();
    }

    @Override
    public void cancelStreaming(@NonNull String mapId) {
        Thread t = mStreamingThreads.remove(mapId);
        if (t != null) {
            Log.d(TAG, "Cancelling streaming thread for " + mapId);
            t.interrupt();
        }
    }

    @Override
    public void checkConnection(@NonNull Context context, @NonNull ConnectionCallback callback) {
        mExecutor.execute(() -> {
            BluetoothSocket socket = getOrConnectSocket(context);
            boolean appAlive = (System.currentTimeMillis() - mLastReceivedTime) < CONNECTION_TIMEOUT;
            if (socket != null && socket.isConnected() && appAlive) {
                callback.onConnectionResult(true, ConnectionType.BLUETOOTH);
            } else {
                callback.onConnectionResult(false, ConnectionType.NONE);
            }
        });
    }

    @Override
    public void parsePreferences(@NonNull Context context, @NonNull byte[] data, @NonNull android.content.SharedPreferences prefs) {
        List<app.organicmaps.wear.SettingsSyncManager.SettingUpdate> updates = parseUpdates(data);
        if (!updates.isEmpty()) {
            mIsApplyingPreferences = true;
            try {
                if (app.organicmaps.wear.SettingsSyncManager.getInstance(context).applyRemoteUpdates(updates)) {
                    // Re-initialize sync layer if backend changed, and notify UI
                    app.organicmaps.wear.WearSyncService.initSyncLayer(context);
                    context.sendBroadcast(new android.content.Intent("app.organicmaps.wear.SETTINGS_CHANGED"));
                }
            } finally {
                mIsApplyingPreferences = false;
            }
        }
    }

    private List<app.organicmaps.wear.SettingsSyncManager.SettingUpdate> parseUpdates(byte[] data) {
        List<app.organicmaps.wear.SettingsSyncManager.SettingUpdate> updates = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.remaining() < 4) return updates;
        int count = buffer.getInt();
        for (int i = 0; i < count; i++) {
            if (buffer.remaining() < 4) break;
            int keyLen = buffer.getInt();
            if (buffer.remaining() < keyLen) break;
            byte[] kb = new byte[keyLen];
            buffer.get(kb);
            String key = new String(kb, StandardCharsets.UTF_8);
            if (buffer.remaining() < 5) break;
            byte type = buffer.get();
            int valLen = buffer.getInt();
            if (buffer.remaining() < valLen + 8) break;
            byte[] vb = new byte[valLen];
            buffer.get(vb);
            Object value = deserializeValue(type, vb);
            long ts = buffer.getLong();
            if (value != null) updates.add(new app.organicmaps.wear.SettingsSyncManager.SettingUpdate(key, value, ts));
        }
        return updates;
    }

    private Object deserializeValue(byte type, byte[] b) {
        try {
            if (type == 1) return b[0] == 1;
            if (type == 2) return new String(b, StandardCharsets.UTF_8);
            if (type == 3) return ByteBuffer.wrap(b).getInt();
            if (type == 4) return ByteBuffer.wrap(b).getLong();
        } catch (Exception e) {
            Log.e(TAG, "Failed to deserialize value of type " + type, e);
        }
        return null;
    }

    @Override
    public boolean isIgnoringPreferenceChanges() {
        return mIsApplyingPreferences;
    }

    @Override
    public void addMessageListener(@NonNull MessageListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeMessageListener(@NonNull MessageListener listener) {
        mListeners.remove(listener);
    }

    private void sendRawMessage(@NonNull Context context, byte type, byte[] payload) {
        mExecutor.execute(() -> {
            BluetoothSocket socket;
            synchronized (this) {
                socket = mActiveSocket;
            }
            if (socket == null || !socket.isConnected()) {
                Log.w(TAG, "DEBUG_BT_PIPELINE: Cannot send (type=" + type + "), no active Bluetooth connection");
                return;
            }

            try {
                OutputStream out = socket.getOutputStream();
                ByteBuffer header = ByteBuffer.allocate(6);
                header.put(PROTOCOL_VERSION);
                header.put(type);
                header.putInt(payload.length);
                out.write(header.array());
                out.write(payload);
                out.flush();
                Log.d(TAG, "DEBUG_BT_PIPELINE: Sent message version=" + PROTOCOL_VERSION + ", type=" + type + ", payload=" + payload.length + " bytes. Total=" + (payload.length + 6));
            } catch (IOException e) {
                Log.e(TAG, "DEBUG_BT_PIPELINE: Bluetooth send failed: " + e.getMessage());
                closeSocket();
            }
        });
    }

    private void startConnectionListener() {
        if (mIsServerRunning) return;
        mIsServerRunning = true;
        new Thread(() -> {
            Log.d(TAG, "DEBUG_BT: Starting Bluetooth connection listener thread");
            while (mIsServerRunning) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    Log.w(TAG, "DEBUG_BT: Bluetooth adapter not found, retrying in 10s");
                    sleep(10000);
                    continue;
                }
                if (!adapter.isEnabled()) {
                    Log.w(TAG, "DEBUG_BT: Bluetooth adapter disabled, retrying in 10s");
                    sleep(10000);
                    continue;
                }

                try {
                    if (mServerSocket != null) {
                        try { mServerSocket.close(); } catch (IOException ignored) {}
                    }
                    mServerSocket = adapter.listenUsingRfcommWithServiceRecord("OrganicMapsSyncPhone", OM_WEAR_UUID);
                    Log.i(TAG, "DEBUG_BT: Bluetooth server listening on: " + OM_WEAR_UUID);
                    while (mIsServerRunning) {
                        BluetoothSocket socket = mServerSocket.accept();
                        if (socket != null) {
                            Log.i(TAG, "DEBUG_BT: Watch connected via Bluetooth: " + (socket.getRemoteDevice() != null ? socket.getRemoteDevice().getName() : "unknown"));
                            synchronized (this) {
                                if (mActiveSocket != null) {
                                    try { mActiveSocket.close(); } catch (IOException ignored) {}
                                }
                                mActiveSocket = socket;
                            }
                            startListening(socket);

                            // Sync current state to newly connected watch
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                if (isFrameworkReady()) {
                                    Log.d(TAG, "DEBUG_BT: Watch connected, triggering sync");
                                    app.organicmaps.wear.WearSyncService.onConnectionEstablished(app.organicmaps.MwmApplication.sInstance);
                                    sendBookmarkCategories(app.organicmaps.MwmApplication.sInstance, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());
                                    sendSearchHistory(app.organicmaps.MwmApplication.sInstance);
                                }
                            });
                            notifyMessageReceived("/connected", new byte[0], "bluetooth_watch");
                        }
                    }
                } catch (IOException | SecurityException e) {
                    if (mIsServerRunning) {
                        Log.e(TAG, "DEBUG_BT: Bluetooth server error: " + e.getMessage() + ". Retrying in 5s");
                        sleep(5000);
                    }
                }
            }
            Log.d(TAG, "DEBUG_BT: Bluetooth connection listener thread stopped");
        }).start();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private synchronized BluetoothSocket getOrConnectSocket(@NonNull Context context) {
        return mActiveSocket;
    }

    private synchronized void closeSocket() {
        if (mActiveSocket != null) {
            try { mActiveSocket.close(); } catch (IOException ignored) {}
            mActiveSocket = null;
        }
    }

    @Override
    public void launchWatchApp(@NonNull Context context) {
        byte[] pathBytes = "/launch".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + pathBytes.length);
        buffer.putInt(pathBytes.length);
        buffer.put(pathBytes);
        sendRawMessage(context, MSG_TYPE_COMMAND, buffer.array());
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping Bluetooth sync layer");
        mIsServerRunning = false;
        mHeartbeatHandler.removeCallbacks(mHeartbeatRunnable);
        mLastReceivedTime = 0;
        if (mServerSocket != null) {
            try { mServerSocket.close(); } catch (IOException ignored) {}
        }
        closeSocket();
        mExecutor.shutdownNow();
    }

    private void startListening(final BluetoothSocket socket) {
        new Thread(() -> {
            try {
                InputStream in = socket.getInputStream();
                while (mIsServerRunning && socket.isConnected()) {
                    byte[] header = new byte[6];
                    readFully(in, header);
                    
                    ByteBuffer hb = ByteBuffer.wrap(header);
                    byte version = hb.get();
                    byte type = hb.get();
                    int len = hb.getInt();

                    if (version != PROTOCOL_VERSION) {
                        Log.e(TAG, "DEBUG_BT_PIPELINE: Protocol version mismatch: received=" + version + ", expected=" + PROTOCOL_VERSION);
                        throw new IOException("Protocol version mismatch");
                    }

                    if (len < 0 || len > 15 * 1024 * 1024 || type < 0 || type > 20) {
                        Log.e(TAG, "DEBUG_BT_PIPELINE: Invalid message header: type=" + type + ", len=" + len + ". Stream desync?");
                        throw new IOException("Protocol desync");
                    }

                    Log.d(TAG, "DEBUG_BT_PIPELINE: Received message header: type=" + type + ", len=" + len);
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    Log.d(TAG, "DEBUG_BT_PIPELINE: Received message payload: " + len + " bytes");
                    
                    if (type == MSG_TYPE_COMMAND || type == MSG_TYPE_VIRTUAL_MWM_REQUEST) {
                        handleIncomingCommand(payload);
                    } else if (type == MSG_TYPE_PREFERENCES || type == MSG_TYPE_PREFERENCES_UPDATES) {
                        notifyMessageReceived("/preferences/watch", payload, "bluetooth_watch");
                    } else if (type == MSG_TYPE_MAP_PROGRESS) {
                        notifyMessageReceived("/map/download/progress", payload, "bluetooth_watch");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "DEBUG_BT_PIPELINE: Listen failed: " + e.getMessage());
                synchronized (this) {
                    if (mActiveSocket == socket) mActiveSocket = null;
                }
                try { socket.close(); } catch (IOException ignored) {}
            }
        }).start();
    }

    private void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) throw new IOException("EOF");
            offset += read;
        }
    }

    @Override
    public void notifyMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        mLastReceivedTime = System.currentTimeMillis();
        for (MessageListener listener : mListeners) {
            listener.onMessageReceived(path, data, sourceNodeId);
        }
    }

    private void handleIncomingCommand(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < 4) return;
        int pathLen = buffer.getInt();
        if (pathLen > 0 && buffer.remaining() >= pathLen) {
            byte[] pathBytes = new byte[pathLen];
            buffer.get(pathBytes);
            String path = new String(pathBytes, StandardCharsets.UTF_8);
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            
            notifyMessageReceived(path, data, "bluetooth_watch");
        }
    }


}
