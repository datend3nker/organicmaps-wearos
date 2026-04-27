package app.organicmaps.wear;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.search.SearchResult;

/**
 * F-Droid implementation of WearSyncService using standard Bluetooth RFCOMM Sockets.
 * This avoids dependency on Google Play Services / Wearable Data Layer.
 */
public class WearSyncService {
    private static final String TAG = "WearSyncServiceFdroid";
    private static final UUID OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130"); // "maps-wearos-sync10"

    private static final byte MSG_TYPE_NAV_STATUS = 1;
    private static final byte MSG_TYPE_SEARCH_RESULTS = 2;
    private static final byte MSG_TYPE_SEARCH_HISTORY = 3;
    private static final byte MSG_TYPE_PREFERENCES = 4;
    private static final byte MSG_TYPE_MAP_DOWNLOAD = 5;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static BluetoothSocket sActiveSocket = null;

    public static void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        Log.d(TAG, "F-Droid: Requesting watch to download: " + countryId);
        byte[] data = countryId.getBytes(StandardCharsets.UTF_8);
        sendRawMessage(MSG_TYPE_MAP_DOWNLOAD, data);
    }

    public static void syncPreferences(@NonNull Context context) {
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean mapEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_map_enabled), false);
        boolean offlineMapsEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_offline_maps_enabled), false);
        
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put((byte) (mapEnabled ? 1 : 0));
        buffer.put((byte) (offlineMapsEnabled ? 1 : 0));
        sendRawMessage(MSG_TYPE_PREFERENCES, buffer.array());
    }

    public static void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        // Build a compact binary payload for navigation
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

    public static void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        // Signaling start by sending a status message with active=1
        updateNavigation(context, app.organicmaps.sdk.Framework.nativeGetRouteFollowingInfo(), null);
    }

    public static void stopNavigation(@NonNull Context context) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) 0); // Inactive
        sendRawMessage(MSG_TYPE_NAV_STATUS, buffer.array());
    }

    public static void sendSearchResults(@NonNull Context context, SearchResult[] results, boolean isSearching) {
        // Simplified results for F-Droid Bluetooth sync to save bandwidth
        int count = Math.min(results.length, 10);
        int totalSize = 1; // isSearching
        ArrayList<byte[]> nameBytesList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] b = results[i].getTitle(context).getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(b);
            totalSize += 4 + b.length + 8 + 8; // length + string + lat + lon
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

    public static void sendSearchState(@NonNull Context context, boolean isSearching) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) (isSearching ? 1 : 0));
        sendRawMessage(MSG_TYPE_SEARCH_RESULTS, buffer.array());
    }

    public static void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        int count = Math.min(SearchRecents.getSize(), 5);
        int totalSize = 4; // count
        ArrayList<byte[]> historyBytes = new ArrayList<>();
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

    public static void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, int x, int y, int zoom, @NonNull byte[] features) {
        // Map tiles are large; on F-Droid we prefer local maps on the watch.
        // We skip Bluetooth streaming of tiles unless requested specifically, but for now we stub it.
    }

    private static void sendRawMessage(byte type, byte[] payload) {
        sExecutor.execute(() -> {
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

    private static synchronized BluetoothSocket getOrConnectSocket() {
        if (sActiveSocket != null && sActiveSocket.isConnected()) return sActiveSocket;
        
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return null;
        
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            // In a real app, we'd filter for the watch or let user select.
            // For Organic Maps Wear, we try connecting to all paired devices until one accepts.
            try {
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(OM_WEAR_UUID);
                socket.connect();
                sActiveSocket = socket;
                return socket;
            } catch (IOException ignored) {}
        }
        return null;
    }

    private static synchronized void closeSocket() {
        if (sActiveSocket != null) {
            try { sActiveSocket.close(); } catch (IOException ignored) {}
            sActiveSocket = null;
        }
    }
}
