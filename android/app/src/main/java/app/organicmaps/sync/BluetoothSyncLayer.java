package app.organicmaps.sync;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
        boolean mapEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_map_enabled), false);
        boolean offlineMapsEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_offline_maps_enabled), false);
        
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put((byte) (mapEnabled ? 1 : 0));
        buffer.put((byte) (offlineMapsEnabled ? 1 : 0));
        sendRawMessage(MSG_TYPE_PREFERENCES, buffer.array());
    }

    @Override
    public void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        byte[] streetBytes = info.nextStreet != null ? info.nextStreet.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] distBytes = info.distToTurn != null ? info.distToTurn.toString(context).getBytes(StandardCharsets.UTF_8) : new byte[0];
        
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 1 + 1 + 4 + 8 + 8 + 8 + 8 + 4 + 4 + streetBytes.length + distBytes.length);
        buffer.put((byte) 1); // Active
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
        
        sendRawMessage(MSG_TYPE_NAV_STATUS, buffer.array());
    }

    @Override
    public void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        updateNavigation(context, app.organicmaps.sdk.Framework.nativeGetRouteFollowingInfo(), null);
    }

    @Override
    public void stopNavigation(@NonNull Context context) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) 0); // Inactive
        sendRawMessage(MSG_TYPE_NAV_STATUS, buffer.array());
    }

    @Override
    public void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        int count = Math.min(results.length, 10);
        int totalSize = 1; // isSearching
        List<byte[]> nameBytesList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] b = results[i].getTitle(context).getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(b);
            totalSize += 4 + b.length + 8 + 8;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.put((byte) (isSearching ? 1 : 0));
        for (int i = 0; i < count; i++) {
            buffer.putInt(nameBytesList.get(i).length);
            buffer.put(nameBytesList.get(i));
            buffer.putDouble(results[i].lat);
            buffer.putDouble(results[i].lon);
        }
        sendRawMessage(MSG_TYPE_SEARCH_RESULTS, buffer.array());
    }

    @Override
    public void sendSearchState(@NonNull Context context, boolean isSearching) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) (isSearching ? 1 : 0));
        sendRawMessage(MSG_TYPE_SEARCH_RESULTS, buffer.array());
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
        sendRawMessage(MSG_TYPE_SEARCH_HISTORY, buffer.array());
    }

    @Override
    public void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        sendRawMessage(MSG_TYPE_MAP_DOWNLOAD, countryId.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + features.length);
        buffer.putLong(requestId);
        buffer.put(features);
        sendRawMessage(MSG_TYPE_MAP_TILE_RESPONSE, buffer.array());
    }

    @Override
    public void addMessageListener(@NonNull MessageListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeMessageListener(@NonNull MessageListener listener) {
        mListeners.remove(listener);
    }

    private void sendRawMessage(byte type, byte[] payload) {
        mExecutor.execute(() -> {
            try {
                BluetoothSocket socket = getOrConnectSocket();
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

    private synchronized BluetoothSocket getOrConnectSocket() {
        if (mActiveSocket != null && mActiveSocket.isConnected()) return mActiveSocket;
        
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
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

    private void handleIncomingCommand(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int pathLen = buffer.getInt();
        byte[] pathBytes = new byte[pathLen];
        buffer.get(pathBytes);
        String path = new String(pathBytes, StandardCharsets.UTF_8);
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        
        for (MessageListener listener : mListeners) {
            listener.onMessageReceived(path, data, "bluetooth_watch");
        }
    }

    private void startConnectionListener() {
        // In a real app, we might want to listen for incoming connections too
        // or periodically try to connect. For now, we try on each send.
    }
}
