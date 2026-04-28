package app.organicmaps.wear;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import app.organicmaps.sdk.routing.RoutingController;

/**
 * F-Droid implementation of WearMessageListenerService using raw Bluetooth RFCOMM Sockets.
 */
public class WearMessageListenerService extends Service {
    private static final String TAG = "WearMsgListenerFdroid";
    private static final UUID OM_WEAR_UUID = UUID.fromString("6d617073-7765-6172-6f73-73796e633130");

    private static final byte MSG_TYPE_COMMAND = 10;
    
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private WearMapTileRequestHandler mMapTileRequestHandler;
    private BluetoothServerSocket mServerSocket;
    private boolean mIsRunning = false;

    @Override
    public void onCreate() {
        super.onCreate() ;
        mMapTileRequestHandler = new WearMapTileRequestHandler(this);
        startListening();
    }

    @Override
    public void onDestroy() {
        mIsRunning = false;
        try { if (mServerSocket != null) mServerSocket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startListening() {
        mIsRunning = true;
        new Thread(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return;
            try {
                mServerSocket = adapter.listenUsingRfcommWithServiceRecord("OrganicMapsSync", OM_WEAR_UUID);
                while (mIsRunning) {
                    BluetoothSocket socket = mServerSocket.accept();
                    if (socket != null) {
                        handleClient(socket);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Bluetooth server error: " + e.getMessage());
            }
        }).start();
    }

    private void handleClient(BluetoothSocket socket) {
        new Thread(() -> {
            try {
                InputStream input = socket.getInputStream();
                while (mIsRunning && socket.isConnected()) {
                    int type = input.read();
                    if (type == -1) break;
                    
                    byte[] lenBuf = new byte[4];
                    readFully(input, lenBuf);
                    int length = ByteBuffer.wrap(lenBuf).getInt();
                    
                    byte[] payload = new byte[length];
                    readFully(input, payload);
                    
                    if (type == MSG_TYPE_COMMAND) {
                        processCommand(payload);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Connection lost: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void readFully(InputStream in, byte[] buffer) throws java.io.IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) throw new java.io.IOException("EOF");
            offset += read;
        }
    }

    private void processCommand(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int pathLen = buffer.getInt();
        String path = new String(data, buffer.position(), pathLen, StandardCharsets.UTF_8);
        buffer.position(buffer.position() + pathLen);
        
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);

        switch (path) {
            case "/navigation/stop":
                mMainHandler.post(() -> {
                    Log.d(TAG, "Stopping navigation");
                    RoutingController.get().cancel();
                });
                break;
            case "/search/query":
                String query = new String(payload, StandardCharsets.UTF_8);
                mMainHandler.post(() -> {
                    Log.d(TAG, "Search: " + query);
                    HeadlessSearchInteractor.getInstance(this).startSearch(query);
                });
                break;
            case "/search/select":
                ByteBuffer pb = ByteBuffer.wrap(payload);
                double lat = pb.getDouble();
                double lon = pb.getDouble();
                int routerType = pb.getInt();
                String name = new String(payload, pb.position(), pb.remaining(), StandardCharsets.UTF_8);
                mMainHandler.post(() -> {
                    Log.d(TAG, "Selected: " + name);
                    HeadlessRouteInteractor.getInstance(this).planRoute(lat, lon, routerType, name);
                });
                break;
            case "/search/history/request":
                mMainHandler.post(() -> {
                    WearSyncService.sendSearchHistory(getApplicationContext());
                });
                break;
            case "/map/tile/request":
                ByteBuffer tb = ByteBuffer.wrap(payload);
                int x = tb.getInt();
                int y = tb.getInt();
                int zoom = tb.getInt();
                double minLat = tb.getDouble();
                double minLon = tb.getDouble();
                double maxLat = tb.getDouble();
                double maxLon = tb.getDouble();
                // sourceNodeId is not used in F-Droid raw sync
                mMainHandler.post(() -> mMapTileRequestHandler.handle("", x, y, zoom, minLat, minLon, maxLat, maxLon));
                break;
        }
    }
}
