package app.organicmaps.wear.map;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.Framework;

/**
 * Shared app module helper for extracting simplified map geometry for the watch.
 */
public final class MapFeaturesExtractor {
    private MapFeaturesExtractor() {}

    @NonNull
    public static byte[] extract(double minLat, double minLon, double maxLat, double maxLon, int scale, int routerType, int poiCategoriesMask) {
        // Use the requested scale directly to ensure we find features visible at that zoom level
        int clampedScale = Math.max(1, Math.min(20, scale));
        return Framework.nativeGetWearMapFeatures(minLat, minLon, maxLat, maxLon, clampedScale, routerType, poiCategoriesMask);
    }
}
