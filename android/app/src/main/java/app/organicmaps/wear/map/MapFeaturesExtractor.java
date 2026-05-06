package app.organicmaps.wear.map;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.Framework;

/**
 * Shared app module helper for extracting simplified map geometry for the watch.
 */
public final class MapFeaturesExtractor {
    private MapFeaturesExtractor() {}

    @NonNull
    public static byte[] extract(double minLat, double minLon, double maxLat, double maxLon, int scale, int routerType) {
        int clampedScale = Math.max(1, Math.min(20, scale));
        // Use a higher scale for extraction to ensure enough points are returned for the watch display
        return Framework.nativeGetWearMapFeatures(minLat, minLon, maxLat, maxLon, Math.max(clampedScale, 18), routerType, 0);
    }
}
