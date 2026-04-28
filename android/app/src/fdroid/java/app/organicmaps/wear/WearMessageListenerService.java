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
import app.organicmaps.sync.ISyncLayer;

public class WearMessageListenerService extends Service implements ISyncLayer.MessageListener {
    private static final String TAG = "WearMsgListenerFdroid";
    private static final int SEARCH_SELECT_MIN_SIZE = Double.BYTES * 2 + Integer.BYTES;
    private static final int MAP_TILE_REQUEST_SIZE = 8 + 8 * 4;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private WearMapTileRequestHandler mMapTileRequestHandler;

    @Override
    public void onCreate() {
        super.onCreate() ;
        mMapTileRequestHandler = new WearMapTileRequestHandler(this);
        WearSyncService.getSyncLayer().addMessageListener(this);
    }

    @Override
    public void onDestroy() {
        WearSyncService.getSyncLayer().removeMessageListener(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        Log.d(TAG, "onMessageReceived: " + path);
        switch (path) {
            case "/navigation/stop" -> mMainHandler.post(() -> {
                Log.d(TAG, "Stopping navigation");
                RoutingController.get().cancel();
            });
            case "/search/query" -> {
                String query = new String(data, StandardCharsets.UTF_8);
                mMainHandler.post(() -> {
                    Log.d(TAG, "Search: " + query);
                    HeadlessSearchInteractor.getInstance(this).startSearch(query);
                });
            }
            case "/search/select" -> {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                if (buffer.remaining() < SEARCH_SELECT_MIN_SIZE) return;
                double lat = buffer.getDouble();
                double lon = buffer.getDouble();
                int routerType = buffer.getInt();
                byte[] nameBytes = new byte[buffer.remaining()];
                buffer.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                mMainHandler.post(() -> {
                    Log.d(TAG, "Selected: " + name);
                    HeadlessRouteInteractor.getInstance(this).planRoute(lat, lon, routerType, name);
                });
            }
            case "/search/history/request" -> mMainHandler.post(() -> {
                WearSyncService.sendSearchHistory(getApplicationContext());
            });
            case "/map/tile/request" -> {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                if (buffer.remaining() < MAP_TILE_REQUEST_SIZE) return;
                long requestId = buffer.getLong();
                double minLat = buffer.getDouble();
                double minLon = buffer.getDouble();
                double maxLat = buffer.getDouble();
                double maxLon = buffer.getDouble();
                mMainHandler.post(() -> mMapTileRequestHandler.handle(sourceNodeId, requestId, minLat, minLon, maxLat, maxLon));
            }
        }
    }
}
