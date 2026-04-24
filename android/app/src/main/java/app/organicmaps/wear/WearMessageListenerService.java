package app.organicmaps.wear;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import app.organicmaps.SplashActivity;
import app.organicmaps.sdk.routing.RoutingController;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class WearMessageListenerService extends WearableListenerService {
    private static final String TAG = "WearMessageListener";
    private static final int SEARCH_SELECT_MIN_SIZE = Double.BYTES * 2 + Integer.BYTES;
    private static final int MAP_TILE_REQUEST_SIZE = 4 * 3 + 8 * 4;

    private static final String PATH_STOP_NAVIGATION = "/navigation/stop";
    private static final String PATH_SEARCH_QUERY = "/search/query";
    private static final String PATH_SEARCH_SELECT = "/search/select";
    private static final String PATH_SEARCH_HISTORY_REQUEST = "/search/history/request";
    private static final String PATH_MAP_TILE_REQUEST = "/map/tile/request";
    private static final String PATH_PING = "/ping";
    private static final String PATH_PONG = "/pong";

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private WearMapTileRequestHandler mMapTileRequestHandler;

    @NonNull
    private WearMapTileRequestHandler getMapTileRequestHandler() {
        if (mMapTileRequestHandler == null) {
            mMapTileRequestHandler = new WearMapTileRequestHandler(this);
        }
        return mMapTileRequestHandler;
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        Log.d(TAG, "onMessageReceived: " + messageEvent.getPath());
        final String path = messageEvent.getPath();

        switch (path) {
            case PATH_STOP_NAVIGATION -> mMainHandler.post(() -> {
                Log.d(TAG, "Stopping navigation per watch request");
                RoutingController.get().cancel();
            });
            case PATH_SEARCH_QUERY -> {
                String query = new String(messageEvent.getData(), StandardCharsets.UTF_8);
                mMainHandler.post(() -> {
                    Log.d(TAG, "Starting headless search for: " + query);
                    HeadlessSearchInteractor.getInstance(this).startSearch(query);
                });
            }
            case PATH_SEARCH_SELECT -> {
                ByteBuffer buffer = ByteBuffer.wrap(messageEvent.getData());
                if (buffer.remaining() < SEARCH_SELECT_MIN_SIZE) {
                    Log.w(TAG, "Malformed search select payload.");
                    return;
                }

                double lat = buffer.getDouble();
                double lon = buffer.getDouble();
                int routerType = buffer.getInt();
                byte[] nameBytes = new byte[buffer.remaining()];
                buffer.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                mMainHandler.post(() -> {
                    Log.d(TAG, "Watch selected: " + name + " (" + lat + ", " + lon + ") Mode: " + routerType);
                    HeadlessRouteInteractor.getInstance(this).planRoute(lat, lon, routerType, name);
                });
            }
            case PATH_SEARCH_HISTORY_REQUEST -> mMainHandler.post(() -> {
                Log.d(TAG, "Sending search history to watch");
                WearSyncService.sendSearchHistory(getApplicationContext());
            });
            case PATH_MAP_TILE_REQUEST -> {
                ByteBuffer buffer = ByteBuffer.wrap(messageEvent.getData());
                if (buffer.remaining() < MAP_TILE_REQUEST_SIZE) {
                    Log.w(TAG, "Malformed map tile request payload.");
                    return;
                }

                int x = buffer.getInt();
                int y = buffer.getInt();
                int zoom = buffer.getInt();
                double minLat = buffer.getDouble();
                double minLon = buffer.getDouble();
                double maxLat = buffer.getDouble();
                double maxLon = buffer.getDouble();
                String sourceNodeId = messageEvent.getSourceNodeId();

                mMainHandler.post(() -> getMapTileRequestHandler().handle(
                    sourceNodeId, x, y, zoom, minLat, minLon, maxLat, maxLon));
            }
            case PATH_PING -> {
                String sourceNodeId = messageEvent.getSourceNodeId();
                Log.d(TAG, "Ping received from " + sourceNodeId);
                // Pong currently disabled in main to avoid dependency issues on some flavors
            }
        }
    }
}
