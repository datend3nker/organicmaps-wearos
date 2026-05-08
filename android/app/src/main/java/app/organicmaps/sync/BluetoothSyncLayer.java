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

        byte[] modeBytes = mapDownloadMode.getBytes(StandardCharsets.UTF_8);
        byte[] backendBytes = backend.getBytes(StandardCharsets.UTF_8);

        ByteBuffer buffer = ByteBuffer.allocate(2 + 1 + 4 + modeBytes.length + 4 + backendBytes.length);
        buffer.put((byte) (mapEnabled ? 1 : 0));
        buffer.put((byte) (watchLocalMode ? 1 : 0));
        buffer.put((byte) (standaloneMode ? 1 : 0));
        buffer.putInt(modeBytes.length);
        buffer.put(modeBytes);
        buffer.putInt(backendBytes.length);
        buffer.put(backendBytes);
        sendRawMessage(context, MSG_TYPE_PREFERENCES, buffer.array());
    }

    @Override
    public void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        byte[] streetBytes = info.nextStreet != null ? info.nextStreet.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] distBytes = info.distToTurn != null ? info.distToTurn.toString(context).getBytes(StandardCharsets.UTF_8) : new byte[0];
        
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 1 + 1 + 4 + 8 + 8 + 8 + 8 + 4 + 4 + streetBytes.length + distBytes.length);
        buffer.put((byte) (app.organicmaps.sdk.routing.RoutingController.get().isNavigating() ? 1 : 0)); // Active
        buffer.put((byte) info.carDirection.ordinal());
        buffer.put((byte) info.pedestrianDirection.ordinal());
        buffer.put((byte) info.exitNum);
        buffer.putFloat((float) info.completionPercent);
        buffer.putDouble(location != null ? location.getLatitude() : 0.0);
        buffer.putDouble(location != null ? location.getLongitude() : 0.0);
        buffer.putDouble(info.turnLat);
        buffer.putDouble(info.turnLon);
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
        int pathLen = buffer.getInt();
        byte[] pathBytes = new byte[pathLen];
        buffer.get(pathBytes);
        String path = new String(pathBytes, StandardCharsets.UTF_8);
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        
        notifyMessageReceived(path, data, "bluetooth_watch");
    }

    private void startConnectionListener() {
        // In a real app, we might want to listen for incoming connections too
        // or periodically try to connect. For now, we try on each send.
    }
}
