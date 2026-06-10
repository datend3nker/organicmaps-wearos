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
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.settings.RoadType;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.sync.BluetoothSyncConnection;
import app.organicmaps.sdk.sync.SyncConnection;
import app.organicmaps.sdk.sync.TcpSyncConnection;
import app.organicmaps.sdk.util.GzipUtils;

import app.organicmaps.sdk.sync.BaseSettingsSyncManager;
import app.organicmaps.sdk.sync.WearProtocol;
import app.organicmaps.sdk.sync.WearProtocolDataConverter;

/**
 * OSS implementation of ISyncLayer using standard Bluetooth RFCOMM Sockets.
 */
public class BluetoothSyncLayer implements ISyncLayer {
    private static final String TAG = "BluetoothSyncLayer";
    private static final UUID OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130");

    private final ExecutorService mExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>());
    private SyncConnection mActiveConnection = null;
    private BluetoothServerSocket mServerSocket = null;
    private java.net.ServerSocket mTcpServerSocket = null;
    private final List<MessageListener> mListeners = new CopyOnWriteArrayList<>();
    private boolean mIsListening = false;
    private boolean mIsServerRunning = false;
    private long mLastReceivedTime = 0;
    private long mLastPingSentTime = 0;
    private long mCurrentPingInterval = 15000; // 15 seconds
    private static final long CONNECTION_TIMEOUT = 45000; 
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
                    app.organicmaps.sdk.sync.WearLog.logState("PHONE", "Heartbeat (" + (isAlive ? "Alive" : "Backoff") + " " + mCurrentPingInterval + "ms) - sending ping");
                    sendRawMessage(app.organicmaps.MwmApplication.sInstance, WearProtocol.TYPE_COMMAND, buildCommandPayload(WearProtocol.PATH_PING, new byte[0]));
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
        if (mExecutor.isShutdown() || mIsApplyingPreferences) return;
        
        app.organicmaps.wear.SettingsSyncManager manager = app.organicmaps.wear.SettingsSyncManager.getInstance(context);
        List<BaseSettingsSyncManager.SettingUpdate> all = manager.getAllSettings();

        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferences (Full Sync) - Items: " + all.size());

        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        for (BaseSettingsSyncManager.SettingUpdate update : all) {
            keys.add(update.key);
            values.add(update.value);
            timestamps.add(update.timestamp);
        }

        byte[] payload = WearProtocolDataConverter.encodePreferenceUpdates(keys, values, timestamps);
        sendRawMessage(context, WearProtocol.TYPE_PREFERENCES, payload);
        manager.markAsSynced(all);
    }

    @Override
    public void syncPreferenceUpdates(@NonNull Context context, @NonNull List<BaseSettingsSyncManager.SettingUpdate> updates) {
        if (mExecutor.isShutdown() || mIsApplyingPreferences || updates.isEmpty()) return;
        Log.d(TAG, "DEBUG_BT_PIPELINE: syncPreferenceUpdates (Buffered) - Items: " + updates.size());

        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        for (BaseSettingsSyncManager.SettingUpdate update : updates) {
            keys.add(update.key);
            values.add(update.value);
            timestamps.add(update.timestamp);
        }

        byte[] payload = WearProtocolDataConverter.encodePreferenceUpdates(keys, values, timestamps);
        sendRawMessage(context, WearProtocol.TYPE_PREFERENCES_UPDATES, payload);
        app.organicmaps.wear.SettingsSyncManager.getInstance(context).markAsSynced(updates);
    }

    @Override
    public void updateNavigation(@NonNull Context context, @Nullable RoutingInfo info, @Nullable Location location) {
        if (mExecutor.isShutdown() || mActiveConnection == null) return;
        
        byte[] payload = WearProtocolDataConverter.encodeNavigationStatus(context, 
            app.organicmaps.sdk.routing.RoutingController.get().isNavigating(), 
            info, location, null, null);
        
        sendRawMessage(context, WearProtocol.TYPE_NAV_STATUS, payload);
    }

    @Override
    public void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        
        if (!isFrameworkReady()) return;
        
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

        if (app.organicmaps.sdk.routing.RoutingController.get().isNavigating() || app.organicmaps.sdk.routing.RoutingController.get().isBuilt()) {
             byte[] payload = WearProtocolDataConverter.encodeNavigationStatus(context, true, null, null, routeLats, routeLons);
             sendRawMessage(context, WearProtocol.TYPE_NAV_STATUS, payload);
        }
    }

    @Override
    public void stopNavigation(@NonNull Context context) {
        byte[] payload = WearProtocolDataConverter.encodeNavigationStatus(context, false, null, null, null, null);
        sendRawMessage(context, WearProtocol.TYPE_NAV_STATUS, payload);
    }

    @Override
    public void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        byte[] payload = WearProtocolDataConverter.encodeSearchResults(context, results, isSearching, 15);
        sendRawMessage(context, WearProtocol.TYPE_SEARCH_RESULTS, payload);
    }

    @Override
    public void sendSearchState(@NonNull Context context, boolean isSearching) {
        byte[] payload = WearProtocolDataConverter.encodeSearchResults(context, new SearchResult[0], isSearching, 0);
        sendRawMessage(context, WearProtocol.TYPE_SEARCH_RESULTS, payload);
    }

    @Override
    public void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        List<String> history = new ArrayList<>();
        for (int i = 0; i < Math.min(SearchRecents.getSize(), 5); i++) {
            history.add(SearchRecents.get(i));
        }
        byte[] payload = WearProtocolDataConverter.encodeSearchHistory(history, 5);
        sendRawMessage(context, WearProtocol.TYPE_SEARCH_HISTORY, payload);
    }

    @Override
    public void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        sendRawMessage(context, WearProtocol.TYPE_MAP_DOWNLOAD_REQUEST, countryId.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features) {
        if (mExecutor.isShutdown()) return;
        mExecutor.execute(new PriorityRunnable(WearProtocol.PRIORITY_LOW, () -> {
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
            sendRawMessage(context, WearProtocol.TYPE_MAP_TILE_RESPONSE, buffer.array());
        }));
    }

    @Override
    public void sendMapChunk(@NonNull Context context, @NonNull String mapId, byte[] chunk, long offset, long totalSize) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendMapChunk for " + mapId + " offset=" + offset + " size=" + chunk.length + " total=" + totalSize);
        byte[] mapIdBytes = mapId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + mapIdBytes.length + 8 + 8 + chunk.length);
        buffer.putInt(mapIdBytes.length);
        buffer.put(mapIdBytes);
        buffer.putLong(offset);
        buffer.putLong(totalSize);
        buffer.put(chunk);
        sendRawMessage(context, WearProtocol.TYPE_MAP_CHUNK, buffer.array());
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
        sendRawMessage(context, WearProtocol.TYPE_VIRTUAL_MWM_DATA, buffer.array());
    }

    @Override
    public void sendMwmMetadata(@NonNull Context context, @NonNull String mwmName, long totalSize, @Nullable byte[] header, @Nullable byte[] footer) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendMwmMetadata for " + mwmName + " size=" + totalSize);
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

        sendRawMessage(context, WearProtocol.TYPE_VIRTUAL_MWM_MOUNT, buffer.array()); 
    }

    @Override
    public void sendDownloadedMaps(@NonNull Context context, @NonNull List<String> mapIds) {
        Log.d(TAG, "DEBUG_BT: Sending downloaded maps list (" + mapIds.size() + ")");
        byte[] payload = WearProtocolDataConverter.encodeSearchHistory(mapIds, 100); // Reusing search history encoding (Int count + [Int len + string]*)
        sendRawMessage(context, WearProtocol.TYPE_MAP_PHONE_DOWNLOADED, payload);
    }

    @Override
    public void requestMwmMetadata(@NonNull Context context, @NonNull String mwmName) {
        // Phone doesn't usually request this
    }

    @Override
    public void sendPong(@NonNull Context context, @NonNull String nodeId) {
        sendRawMessage(context, WearProtocol.TYPE_COMMAND, buildCommandPayload(WearProtocol.PATH_PONG, new byte[0]));
    }

    @Override
    public void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress) {
        byte[] countryBytes = countryId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + countryBytes.length + 4);
        buffer.putInt(countryBytes.length);
        buffer.put(countryBytes);
        buffer.putInt(progress);
        
        sendRawMessage(context, WearProtocol.TYPE_MAP_DOWNLOAD_PROGRESS, buffer.array());
    }

    @Override
    public void sendRouteBuildProgress(@NonNull Context context, int progress) {
        Log.d(TAG, "sendRouteBuildProgress: " + progress + "%");
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(progress);
        sendRawMessage(context, WearProtocol.TYPE_ROUTE_BUILD_PROGRESS, buffer.array());
    }

    @Override
    public void sendMapNotFound(@NonNull Context context, @NonNull String mapId) {
        sendRawMessage(context, WearProtocol.TYPE_COMMAND, buildCommandPayload(WearProtocol.PATH_MAP_DOWNLOAD_NOT_FOUND, mapId.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void sendTrackRecordingStatus(@NonNull Context context, boolean isRecording) {
        long startTime = app.organicmaps.location.TrackRecordingService.getRecordingStartTime();
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8);
        buffer.put((byte) (isRecording ? 1 : 0));
        buffer.putLong(startTime);
        sendRawMessage(context, WearProtocol.TYPE_TRACK_RECORDING, buffer.array());
    }

    @Override
    public void sendBookmarkCategories(@NonNull Context context, @NonNull List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories) {
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        Log.d(TAG, "DEBUG_BT_PIPELINE: sendBookmarkCategories - Count: " + categories.size());
        byte[] payload = WearProtocolDataConverter.encodeBookmarkCategories(categories, syncPrefs, 20);
        sendRawMessage(context, WearProtocol.TYPE_BOOKMARKS, payload);
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
        sendRawMessage(context, WearProtocol.TYPE_BOOKMARK_FILE, buffer.array());
        
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
        sendRawMessage(context, WearProtocol.TYPE_BOOKMARK_RENAME, buffer.array());
        
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        long ts = syncPrefs.getLong(oldName, 0);
        syncPrefs.edit().remove(oldName).putLong(newName, ts > 0 ? ts : System.currentTimeMillis()).apply();
    }

    @Override
    public void deleteBookmarkCategory(@NonNull Context context, @NonNull String name) {
        Log.d(TAG, "DEBUG_BT_PIPELINE: deleteBookmarkCategory: " + name);
        sendRawMessage(context, WearProtocol.TYPE_BOOKMARK_DELETE, name.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
               .edit().remove(name).apply();
    }

    @Override
    public void sendBackendSwitch(@NonNull Context context, @NonNull String newBackend) {
        sendRawMessage(context, WearProtocol.TYPE_COMMAND, buildCommandPayload(WearProtocol.PATH_BACKEND_SWITCH, newBackend.getBytes(StandardCharsets.UTF_8)));
    }

    private final java.util.Map<String, Thread> mStreamingThreads = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void streamMapFile(@NonNull Context context, @NonNull String nodeId, @NonNull String mapId, @NonNull java.io.File file, long offset) {
        Thread thread = new Thread(() -> {
            Log.d(TAG, "DEBUG_BT_PIPELINE: Starting map stream thread for " + mapId + " from offset " + offset + " (File size: " + file.length() + ")");
            long totalBytes = file.length();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                if (offset > 0) fis.skip(offset);
                
                byte[] buffer = new byte[32 * 1024]; 
                int bytesRead;
                long totalSent = offset;
                int lastReportedProgress = -1;

                app.organicmaps.wear.WearCompanionNotificationManager.showServingNotification(context, mapId, (int)(offset * 100 / totalBytes));

                while ((bytesRead = fis.read(buffer)) != -1) {
                    if (Thread.interrupted()) {
                        Log.d(TAG, "DEBUG_BT_PIPELINE: Streaming for " + mapId + " was CANCELLED");
                        return;
                    }
                    byte[] chunk = bytesRead == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, bytesRead);
                    
                    sendMapChunk(context, mapId, chunk, totalSent, totalBytes);
                    totalSent += bytesRead;
                    
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
        if (mExecutor.isShutdown()) {
            callback.onConnectionResult(false, ConnectionType.NONE);
            return;
        }
        mExecutor.execute(new PriorityRunnable(WearProtocol.PRIORITY_HIGH, () -> {
            SyncConnection connection;
            synchronized (this) {
                connection = mActiveConnection;
            }
            boolean appAlive = (System.currentTimeMillis() - mLastReceivedTime) < CONNECTION_TIMEOUT;
            if (connection != null && connection.isConnected() && appAlive) {
                callback.onConnectionResult(true, ConnectionType.BLUETOOTH);
            } else {
                callback.onConnectionResult(false, ConnectionType.NONE);
            }
        }));
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
            Object value = WearProtocolDataConverter.deserializeValue(type, vb);
            long ts = buffer.getLong();
            if (value != null) updates.add(new app.organicmaps.wear.SettingsSyncManager.SettingUpdate(key, value, ts));
        }
        return updates;
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
        if (mExecutor.isShutdown()) return;
        int priority = WearProtocol.getPriority(type);
        mExecutor.execute(new PriorityRunnable(priority, () -> {
            SyncConnection connection;
            synchronized (this) {
                connection = mActiveConnection;
            }
            if (connection == null || !connection.isConnected()) {
                Log.w(TAG, "DEBUG_BT_PIPELINE: Cannot send (type=" + type + "), no active connection");
                return;
            }

            try {
                OutputStream out = connection.getOutputStream();
                ByteBuffer header = ByteBuffer.allocate(6);
                header.put(WearProtocol.PROTOCOL_VERSION);
                header.put(type);
                header.putInt(payload.length);
                out.write(header.array());
                out.write(payload);
                out.flush();
                app.organicmaps.sdk.sync.WearLog.logSent("PHONE", "BLUETOOTH", "type=" + type, payload.length);
            } catch (IOException e) {
                app.organicmaps.sdk.sync.WearLog.e("DEBUG_BT_PIPELINE: Send failed: " + e.getMessage());
                closeConnection();
            }
        }));
    }

    private static class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
        private final int priority;
        private final Runnable runnable;

        PriorityRunnable(int priority, Runnable runnable) {
            this.priority = priority;
            this.runnable = runnable;
        }

        @Override
        public void run() {
            runnable.run();
        }

        @Override
        public int compareTo(@NonNull PriorityRunnable other) {
            return Integer.compare(this.priority, other.priority);
        }
    }

    private void startConnectionListener() {
        if (mIsServerRunning) return;
        mIsServerRunning = true;
        new Thread(() -> {
            Log.d(TAG, "REF_TCP_RFCOMM_SUCCESS: Starting Server connection listener threads");
            
            // Start TCP server in its own thread (always, to support adb forward for emulators)
            new Thread(this::runTcpServer).start();
            
            // Start RFCOMM server if not an emulator
            boolean isEmulator = android.os.Build.PRODUCT.contains("sdk") || android.os.Build.PRODUCT.contains("vbox");
            if (!isEmulator) {
                runRfcommServer();
            }
        }).start();
    }

    private void runTcpServer() {
        int retryCount = 0;
        while (mIsServerRunning && retryCount < 5) {
            try {
                mTcpServerSocket = new java.net.ServerSocket();
                mTcpServerSocket.setReuseAddress(true);
                mTcpServerSocket.bind(new java.net.InetSocketAddress(5610));
                Log.i(TAG, "REF_TCP_RFCOMM_SUCCESS: TCP server listening on port 5610 (Emulator mode)");
                while (mIsServerRunning) {
                    java.net.Socket socket = mTcpServerSocket.accept();
                    if (socket != null) {
                        Log.i(TAG, "DEBUG_BT: Watch connected via TCP: " + socket.getInetAddress());
                        handleNewConnection(new TcpSyncConnection(socket));
                    }
                }
                break;
            } catch (IOException e) {
                if (mIsServerRunning) {
                    Log.e(TAG, "DEBUG_BT: TCP server error (attempt " + (retryCount + 1) + "): " + e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("EADDRINUSE")) {
                        retryCount++;
                        closeTcpServerSocket();
                        sleep(2000);
                        continue;
                    }
                }
                break;
            } finally {
                closeTcpServerSocket();
            }
        }
    }

    private void runRfcommServer() {
        while (mIsServerRunning) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                sleep(5000);
                continue;
            }
            try {
                try {
                    mServerSocket = adapter.listenUsingRfcommWithServiceRecord("OrganicMapsSync", OM_WEAR_UUID);
                } catch (SecurityException e) {
                    Log.e(TAG, "DEBUG_BT: RFCOMM listen failed due to missing permission: " + e.getMessage());
                    sleep(10000);
                    continue;
                }
                Log.i(TAG, "DEBUG_BT: RFCOMM server listening...");
                while (mIsServerRunning) {
                    BluetoothSocket socket = mServerSocket.accept();
                    if (socket != null) {
                        Log.i(TAG, "DEBUG_BT: Watch connected via RFCOMM");
                        handleNewConnection(new BluetoothSyncConnection(socket));
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "DEBUG_BT: RFCOMM server error: " + e.getMessage());
                sleep(5000);
            } finally {
                if (mServerSocket != null) {
                    try { mServerSocket.close(); } catch (IOException ignored) {}
                    mServerSocket = null;
                }
            }
        }
    }

    private synchronized void handleNewConnection(SyncConnection connection) {
        closeConnection();
        mActiveConnection = connection;
        startListening(connection);

        // Handshake: Trigger sync immediately when app link established
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context != null) {
            app.organicmaps.wear.WearSyncService.onConnectionEstablished(context);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private synchronized SyncConnection getOrConnectConnection(@NonNull Context context) {
        return mActiveConnection;
    }

    private synchronized void closeConnection() {
        if (mActiveConnection != null) {
            try { mActiveConnection.close(); } catch (IOException ignored) {}
            mActiveConnection = null;
        }
    }

    @Override
    public void launchWatchApp(@NonNull Context context) {
        if (mExecutor.isShutdown()) return;
        sendRawMessage(context, WearProtocol.TYPE_COMMAND, buildCommandPayload(WearProtocol.PATH_LAUNCH, new byte[0]));
    }

    @Override
    public void sendHandshake(@NonNull Context context) {
        byte[] payload = WearProtocolDataConverter.encodeHandshake(app.organicmaps.BuildConfig.VERSION_CODE, (byte) 0);
        sendRawMessage(context, WearProtocol.TYPE_HANDSHAKE, payload);
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
        closeTcpServerSocket();
        closeConnection();
        mExecutor.shutdownNow();
    }

    private synchronized void closeTcpServerSocket() {
        if (mTcpServerSocket != null) {
            try { mTcpServerSocket.close(); } catch (IOException ignored) {}
            mTcpServerSocket = null;
        }
    }

    private void startListening(final SyncConnection connection) {
        new Thread(() -> {
            try {
                InputStream in = connection.getInputStream();
                while (mIsServerRunning && connection.isConnected()) {
                    byte[] header = new byte[6];
                    readFully(in, header);
                    
                    ByteBuffer hb = ByteBuffer.wrap(header);
                    byte version = hb.get();
                    byte type = hb.get();
                    int len = hb.getInt();

                    if (version != WearProtocol.PROTOCOL_VERSION) {
                        Log.e(TAG, "DEBUG_BT_PIPELINE: Protocol version mismatch: received=" + version + ", expected=" + WearProtocol.PROTOCOL_VERSION);
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
                    
                    if (type == WearProtocol.TYPE_COMMAND || type == WearProtocol.TYPE_VIRTUAL_MWM_REQUEST) {
                        handleIncomingCommand(payload);
                    } else if (type == WearProtocol.TYPE_PREFERENCES || type == WearProtocol.TYPE_PREFERENCES_UPDATES) {
                        notifyMessageReceived(WearProtocol.PATH_PREFERENCES_WATCH, payload, "bluetooth_watch");
                    } else if (type == WearProtocol.TYPE_MAP_DOWNLOAD_PROGRESS) {
                        notifyMessageReceived(WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS, payload, "bluetooth_watch");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "DEBUG_BT_PIPELINE: Listen failed: " + e.getMessage());
                synchronized (this) {
                    if (mActiveConnection == connection) mActiveConnection = null;
                }
                try { connection.close(); } catch (IOException ignored) {}
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
        app.organicmaps.sdk.sync.WearLog.logReceived("PHONE", "BLUETOOTH", path, data.length);
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
            
            Log.d(TAG, "DEBUG_BT: Received incoming command: " + path + " (payload=" + data.length + " bytes)");
            notifyMessageReceived(path, data, "bluetooth_watch");
        }
    }

    @Override
    public boolean isLinked() {
        return (System.currentTimeMillis() - mLastReceivedTime) < CONNECTION_TIMEOUT;
    }
}
