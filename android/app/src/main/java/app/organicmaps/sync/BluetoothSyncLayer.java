package app.organicmaps.sync;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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
import app.organicmaps.util.GzipUtils;

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
    private static final byte MSG_TYPE_COMMAND = 10;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private BluetoothSocket mActiveSocket = null;
    private final List<MessageListener> mListeners = new CopyOnWriteArrayList<>();
    private boolean mIsListening = false;

    public BluetoothSyncLayer() {
        startConnectionListener();
    }

    @Override
    public void syncPreferences(@NonNull Context context) {
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean standaloneMode = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_standalone_mode), false);
        boolean mapEnabled = standaloneMode || prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_map_enabled), false);
        boolean watchLocalMode = standaloneMode || prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_watch_local_mode), false);
        String mapDownloadMode = prefs.getString(context.getString(app.organicmaps.R.string.pref_wear_os_map_download_mode), "BLUETOOTH_ONLY");
        String backend = prefs.getString(context.getString(app.organicmaps.R.string.pref_wear_os_backend), "GMS");
        boolean autoDownload = prefs.getBoolean("autoDownloadRouteMaps", true);
        int poiMask = prefs.getInt("poiCategoriesMask", 0x3F);

        // Map-specific settings
        boolean is3dEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_3d), true);
        boolean is3dBuildingsEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_3d_buildings), true);
        boolean isAutoZoomEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_auto_zoom), true);
        int mUnits = Integer.parseInt(prefs.getString(context.getString(app.organicmaps.R.string.pref_munits), "0"));
        String mapStyle = prefs.getString(context.getString(app.organicmaps.R.string.pref_map_style), "default");

        // Routing options
        boolean avoidTolls = RoutingOptions.hasOption(RoadType.Toll);
        boolean avoidMotorways = RoutingOptions.hasOption(RoadType.Motorway);
        boolean avoidFerries = RoutingOptions.hasOption(RoadType.Ferry);
        boolean avoidUnpaved = RoutingOptions.hasOption(RoadType.Dirty);

        byte[] modeBytes = mapDownloadMode.getBytes(StandardCharsets.UTF_8);
        byte[] backendBytes = backend.getBytes(StandardCharsets.UTF_8);
        byte[] styleBytes = mapStyle.getBytes(StandardCharsets.UTF_8);

        // BUFFER Format: [1:mapEnabled][1:watchLocal][1:standalone][1:autoDownload][4:modeLen][mode][4:backendLen][backend][4:poiMask][1:3d][1:3dBld][1:autoZoom][4:mUnits][4:styleLen][style][1:toll][1:mtw][1:ferry][1:dirty][8:timestamp]
        ByteBuffer buffer = ByteBuffer.allocate(39 + modeBytes.length + backendBytes.length + styleBytes.length);
        buffer.put((byte) (mapEnabled ? 1 : 0));
        buffer.put((byte) (watchLocalMode ? 1 : 0));
        buffer.put((byte) (standaloneMode ? 1 : 0));
        buffer.put((byte) (autoDownload ? 1 : 0));
        buffer.putInt(modeBytes.length);
        buffer.put(modeBytes);
        buffer.putInt(backendBytes.length);
        buffer.put(backendBytes);
        buffer.putInt(poiMask);
        
        buffer.put((byte) (is3dEnabled ? 1 : 0));
        buffer.put((byte) (is3dBuildingsEnabled ? 1 : 0));
        buffer.put((byte) (isAutoZoomEnabled ? 1 : 0));
        buffer.putInt(mUnits);
        buffer.putInt(styleBytes.length);
        buffer.put(styleBytes);

        buffer.put((byte) (avoidTolls ? 1 : 0));
        buffer.put((byte) (avoidMotorways ? 1 : 0));
        buffer.put((byte) (avoidFerries ? 1 : 0));
        buffer.put((byte) (avoidUnpaved ? 1 : 0));

        buffer.putLong(System.currentTimeMillis());
        sendRawMessage(context, MSG_TYPE_PREFERENCES, buffer.array());
    }

    @Override
    public void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        byte[] streetBytes = info.nextStreet != null ? info.nextStreet.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] distBytes = info.distToTurn != null ? info.distToTurn.toString(context).getBytes(StandardCharsets.UTF_8) : new byte[0];
        
        // BUFFER Format: [1:active][1:carDir][1:pedDir][1:exit][4:progress][8:lat][8:lon][8:turnLat][8:turnLon][4:bearing][4:speed][4:limit][4:streetLen][4:distLen][street][dist]
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 1 + 1 + 4 + 8 + 8 + 8 + 8 + 4 + 4 + 4 + 4 + 4 + streetBytes.length + distBytes.length);
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

        buffer.putInt(streetBytes.length);
        buffer.putInt(distBytes.length);
        buffer.put(streetBytes);
        buffer.put(distBytes);
        
        sendRawMessage(context, MSG_TYPE_NAV_STATUS, buffer.array());
    }

    @Override
    public void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        RoutingInfo info = app.organicmaps.sdk.Framework.nativeGetRouteFollowingInfo();
        if (info != null) {
            updateNavigation(context, info, null);
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
        int calcTotalSize = 1; // isSearching
        List<byte[]> nameBytesList = new ArrayList<>();
        List<byte[]> descBytesList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SearchResult res = results[i];
            byte[] nb = res.getTitle(context).getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(nb);
            
            String desc = "";
            if (res.description != null) {
                if (res.description.localizedFeatureType != null) desc = res.description.localizedFeatureType;
                else if (res.description.region != null) desc = res.description.region;
            }
            byte[] db = desc.getBytes(StandardCharsets.UTF_8);
            descBytesList.add(db);
            
            calcTotalSize += 4 + nb.length + 4 + db.length + 8 + 8;
        }
        
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
        }
        sendRawMessage(context, MSG_TYPE_SEARCH_RESULTS, buffer.array());
    }

    @Override
    public void sendSearchState(@NonNull Context context, boolean isSearching) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) (isSearching ? 1 : 0));
        sendRawMessage(context, MSG_TYPE_SEARCH_RESULTS, buffer.array());
    }

    @Override
    public void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        int count = Math.min(SearchRecents.getSize(), 5);
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
                } catch (IOException e) {
                    Log.w(TAG, "Compression failed, sending raw");
                }
            }

            ByteBuffer buffer = ByteBuffer.allocate(8 + 1 + dataToSend.length);
            buffer.putLong(requestId);
            buffer.put((byte) (compressed ? 1 : 0));
            buffer.put(dataToSend);
            sendRawMessage(context, MSG_TYPE_MAP_TILE_RESPONSE, buffer.array());
        });
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
        sendRawMessage(context, (byte) 7, buffer.array());
    }

    @Override
    public void parsePreferences(@NonNull Context context, @NonNull byte[] data, @NonNull android.content.SharedPreferences prefs) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.remaining() < 4) return;
        
        boolean mapEnabled = buffer.get() == 1;
        boolean watchLocalMode = buffer.get() == 1;
        boolean standaloneMode = buffer.get() == 1;
        boolean autoDownloadRouteMaps = buffer.get() == 1;

        String mapDownloadMode = "BLUETOOTH_ONLY";
        if (buffer.remaining() >= 4) {
            int len = buffer.getInt();
            if (len > 0 && buffer.remaining() >= len) {
                byte[] b = new byte[len];
                buffer.get(b);
                mapDownloadMode = new String(b, StandardCharsets.UTF_8);
            }
        }

        String backend = "GMS";
        if (buffer.remaining() >= 4) {
            int len = buffer.getInt();
            if (len > 0 && buffer.remaining() >= len) {
                byte[] b = new byte[len];
                buffer.get(b);
                backend = new String(b, StandardCharsets.UTF_8);
            }
        }
        
        int poiMask = 0x3F;
        if (buffer.remaining() >= 4) {
            poiMask = buffer.getInt();
        }

        boolean is3dEnabled = true;
        boolean is3dBuildingsEnabled = true;
        boolean isAutoZoomEnabled = true;
        if (buffer.remaining() >= 3) {
            is3dEnabled = buffer.get() == 1;
            is3dBuildingsEnabled = buffer.get() == 1;
            isAutoZoomEnabled = buffer.get() == 1;
        }

        int measurementUnits = 0;
        if (buffer.remaining() >= 4) {
            measurementUnits = buffer.getInt();
        }

        String mapStyle = "default";
        if (buffer.remaining() >= 4) {
            int len = buffer.getInt();
            if (len > 0 && buffer.remaining() >= len) {
                byte[] b = new byte[len];
                buffer.get(b);
                mapStyle = new String(b, StandardCharsets.UTF_8);
            }
        }

        boolean avoidTolls = false;
        boolean avoidMotorways = false;
        boolean avoidFerries = false;
        boolean avoidUnpaved = false;
        if (buffer.remaining() >= 4) {
            avoidTolls = buffer.get() == 1;
            avoidMotorways = buffer.get() == 1;
            avoidFerries = buffer.get() == 1;
            avoidUnpaved = buffer.get() == 1;
        }

        long timestamp = 0;
        if (buffer.remaining() >= 8) {
            timestamp = buffer.getLong();
        }

        long lastApplied = prefs.getLong("pref_wear_os_last_sync_timestamp", 0);
        if (timestamp > 0 && timestamp < lastApplied) return;

        prefs.edit()
            .putLong("pref_wear_os_last_sync_timestamp", timestamp)
            .putBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_map_enabled), mapEnabled)
            .putBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_watch_local_mode), watchLocalMode)
            .putBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_standalone_mode), standaloneMode)
            .putBoolean("autoDownloadRouteMaps", autoDownloadRouteMaps)
            .putString(context.getString(app.organicmaps.R.string.pref_wear_os_backend), backend)
            .putString(context.getString(app.organicmaps.R.string.pref_wear_os_map_download_mode), mapDownloadMode)
            .putInt("poiCategoriesMask", poiMask)
            .putBoolean(context.getString(app.organicmaps.R.string.pref_3d), is3dEnabled)
            .putBoolean(context.getString(app.organicmaps.R.string.pref_3d_buildings), is3dBuildingsEnabled)
            .putBoolean(context.getString(app.organicmaps.R.string.pref_auto_zoom), isAutoZoomEnabled)
            .putString(context.getString(app.organicmaps.R.string.pref_munits), String.valueOf(measurementUnits))
            .putString(context.getString(app.organicmaps.R.string.pref_map_style), mapStyle)
            .putBoolean("avoid_tolls", avoidTolls)
            .putBoolean("avoid_motorways", avoidMotorways)
            .putBoolean("avoid_ferries", avoidFerries)
            .putBoolean("avoid_dirty_roads", avoidUnpaved)
            .apply();
        
        if (avoidTolls) RoutingOptions.addOption(RoadType.Toll); else RoutingOptions.removeOption(RoadType.Toll);
        if (avoidMotorways) RoutingOptions.addOption(RoadType.Motorway); else RoutingOptions.removeOption(RoadType.Motorway);
        if (avoidFerries) RoutingOptions.addOption(RoadType.Ferry); else RoutingOptions.removeOption(RoadType.Ferry);
        if (avoidUnpaved) RoutingOptions.addOption(RoadType.Dirty); else RoutingOptions.removeOption(RoadType.Dirty);
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
        final Context appContext = context.getApplicationContext();
        mExecutor.execute(() -> {
            try {
                BluetoothSocket socket = getOrConnectSocket(appContext);
                if (socket == null) return;
                
                OutputStream out = socket.getOutputStream();
                ByteBuffer header = ByteBuffer.allocate(5);
                header.put(type);
                header.putInt(payload.length);
                out.write(header.array());
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                Log.e(TAG, "Bluetooth send failed: " + e.getMessage());
                closeSocket();
            }
        });
    }

    private synchronized BluetoothSocket getOrConnectSocket(@NonNull Context context) {
        if (mActiveSocket != null && mActiveSocket.isConnected()) return mActiveSocket;
        
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) return null;
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return null;
        
        try {
            Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
            if (pairedDevices == null) return null;
            for (BluetoothDevice device : pairedDevices) {
                try {
                    BluetoothSocket socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID);
                    socket.connect();
                    mActiveSocket = socket;
                    startListening();
                    return socket;
                } catch (IOException ignored) {}
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth permission missing", e);
        }
        return null;
    }

    private synchronized void closeSocket() {
        if (mActiveSocket != null) {
            try { mActiveSocket.close(); } catch (IOException ignored) {}
            mActiveSocket = null;
        }
        mIsListening = false;
    }

    @Override
    public void stop() {
        closeSocket();
        mExecutor.shutdown();
    }

    private void startListening() {
        if (mIsListening) return;
        mIsListening = true;
        new Thread(() -> {
            try {
                InputStream in = mActiveSocket.getInputStream();
                while (mIsListening) {
                    byte[] header = new byte[5];
                    if (in.read(header) != 5) break;
                    
                    ByteBuffer hb = ByteBuffer.wrap(header);
                    byte type = hb.get();
                    int len = hb.getInt();
                    
                    byte[] payload = new byte[len];
                    int read = 0;
                    while (read < len) {
                        int r = in.read(payload, read, len - read);
                        if (r == -1) break;
                        read += r;
                    }
                    
                    if (type == MSG_TYPE_COMMAND) {
                        handleIncomingCommand(payload);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Listen failed: " + e.getMessage());
                closeSocket();
            }
        }).start();
    }

    @Override
    public void notifyMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
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

    private void startConnectionListener() {
        // In a real app, we might want to listen for incoming connections too
        // or periodically try to connect. For now, we try on each send.
    }
}
