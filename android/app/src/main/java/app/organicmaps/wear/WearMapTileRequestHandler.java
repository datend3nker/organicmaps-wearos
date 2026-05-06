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

    public void handle(@NonNull String nodeId, long requestId, double minLat, double minLon,
                       double maxLat, double maxLon, int routerType) {
        try {
            boolean asyncInit = MwmApplication.from(mContext).initOrganicMaps(() ->
                sendMapTileResponse(nodeId, requestId, minLat, minLon, maxLat, maxLon, routerType));
            if (!asyncInit) {
                sendMapTileResponse(nodeId, requestId, minLat, minLon, maxLat, maxLon, routerType);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to init Organic Maps for tile streaming", e);
            WearSyncService.sendMapTileResponse(mContext, nodeId, requestId, new byte[0]);
        }
    }

    private void sendMapTileResponse(@NonNull String nodeId, long requestId, double minLat, double minLon,
                                     double maxLat, double maxLon, int routerType) {
        // Zoom 16 on phone matches scale/depth needed for watch display
        byte[] features = MapFeaturesExtractor.extract(minLat, minLon, maxLat, maxLon, 16, routerType);
        WearSyncService.sendMapTileResponse(mContext, nodeId, requestId, features);
    }
}
