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
                       double maxLat, double maxLon, int scale, int routerType, int poiCategoriesMask) {
        try {
            boolean asyncInit = MwmApplication.from(mContext).initOrganicMaps(() ->
                sendMapTileResponse(nodeId, requestId, minLat, minLon, maxLat, maxLon, scale, routerType, poiCategoriesMask));
            if (!asyncInit) {
                sendMapTileResponse(nodeId, requestId, minLat, minLon, maxLat, maxLon, scale, routerType, poiCategoriesMask);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to init Organic Maps for tile streaming", e);
            WearSyncService.sendMapTileResponse(mContext, nodeId, requestId, new byte[0]);
        }
    }

    private void sendMapTileResponse(@NonNull String nodeId, long requestId, double minLat, double minLon,
                                     double maxLat, double maxLon, int scale, int routerType, int poiCategoriesMask) {
        // Use the scale requested by the watch to ensure appropriate detail level
        byte[] features = MapFeaturesExtractor.extract(minLat, minLon, maxLat, maxLon, scale, routerType, poiCategoriesMask);
        WearSyncService.sendMapTileResponse(mContext, nodeId, requestId, features);
    }
}
