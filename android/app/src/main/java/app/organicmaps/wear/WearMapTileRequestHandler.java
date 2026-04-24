package app.organicmaps.wear;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;

import app.organicmaps.MwmApplication;
import app.organicmaps.wear.map.MapFeaturesExtractor;

/**
 * Handles watch map tile requests without coupling request parsing logic to the listener service.
 */
public final class WearMapTileRequestHandler {
    private static final String TAG = "WearMapTileRequest";

    private final Context mContext;

    public WearMapTileRequestHandler(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    public void handle(@NonNull String nodeId, int x, int y, int zoom, double minLat, double minLon,
                       double maxLat, double maxLon) {
        try {
            boolean asyncInit = MwmApplication.from(mContext).initOrganicMaps(() ->
                sendMapTileResponse(nodeId, x, y, zoom, minLat, minLon, maxLat, maxLon));
            if (!asyncInit) {
                sendMapTileResponse(nodeId, x, y, zoom, minLat, minLon, maxLat, maxLon);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to init Organic Maps for tile streaming", e);
            WearSyncService.sendMapTileResponse(mContext, nodeId, x, y, zoom, new byte[0]);
        }
    }

    private void sendMapTileResponse(@NonNull String nodeId, int x, int y, int zoom, double minLat, double minLon,
                                     double maxLat, double maxLon) {
        // Zoom 16 on phone matches scale/depth needed for watch display
        byte[] features = MapFeaturesExtractor.extract(minLat, minLon, maxLat, maxLon, 16);
        WearSyncService.sendMapTileResponse(mContext, nodeId, x, y, zoom, features);
    }
}
