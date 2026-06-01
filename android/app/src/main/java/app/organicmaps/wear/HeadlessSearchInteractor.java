package app.organicmaps.wear;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;

import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchListener;
import app.organicmaps.sdk.search.SearchResult;

public class HeadlessSearchInteractor implements SearchListener {
    private static final String TAG = "HeadlessSearch";
    private final Context mContext;
    private static HeadlessSearchInteractor sInstance;
    private long mLastSearchTimestamp;
    private SearchResult[] mLastResults;

    private HeadlessSearchInteractor(Context context) {
        mContext = context.getApplicationContext();
        SearchEngine.INSTANCE.addListener(this);
    }

    public static HeadlessSearchInteractor getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HeadlessSearchInteractor(context);
        }
        return sInstance;
    }

    public void startSearch(@NonNull String query) {
        startSearch(query, 0.0, 0.0);
    }

    public void startSearch(@NonNull String query, double lat, double lon) {
        Log.d(TAG, "startSearch requested for: " + query);
        try {
            if (MwmApplication.from(mContext).getOrganicMaps().arePlatformAndCoreInitialized()) {
                Log.d(TAG, "Framework already initialized, performing search.");
                performSearch(query, lat, lon);
                return;
            }
            Log.d(TAG, "Initializing framework for headless search...");
            boolean asyncInit = MwmApplication.from(mContext).initOrganicMaps(() -> {
                Log.d(TAG, "Framework initialization callback triggered.");
                performSearch(query, lat, lon);
            });
            if (!asyncInit) {
                Log.d(TAG, "Framework initialized synchronously.");
                performSearch(query, lat, lon);
            }
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to init organic maps for search: ", e);
            WearSyncService.sendSearchState(mContext, false);
        }
    }

    private void performSearch(@NonNull String query, double lat, double lon) {
        Log.d(TAG, "performSearch: " + query + " around " + lat + ", " + lon);
        WearSyncService.sendSearchState(mContext, true);
        mLastResults = null;
        mLastSearchTimestamp = System.nanoTime();
        SearchEngine.INSTANCE.cancel();
        Framework.nativeRestoreDownloadQueue();
        
        boolean hasLocation = (lat != 0.0 || lon != 0.0);
        if (!hasLocation) {
            Location loc = MwmApplication.from(mContext).getLocationHelper().getSavedLocation();
            if (loc == null) {
                try {
                    android.location.LocationManager lm = (android.location.LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
                    if (lm != null) {
                        Location lastGps = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                        Location lastNetwork = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                        if (lastGps != null) loc = lastGps;
                        else if (lastNetwork != null) loc = lastNetwork;
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "No location permission for headless search");
                }
            }
            if (loc != null) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
                hasLocation = true;
            }
        }
        
        // Initialize the viewport for the search engine, otherwise searches are endlessly delayed
        // since the search API waits for the map to be rendered and `OnViewportChanged` to be called.
        // On headless Wear OS we never render the map, so we set a synthetic viewport.
        int zoom = hasLocation ? 13 : 1; // Slightly tighter zoom for better address matching
        Framework.nativeSetSearchViewport(lat, lon, zoom);

        boolean success = SearchEngine.INSTANCE.search(mContext, query, false, mLastSearchTimestamp, hasLocation, lat, lon);
        Log.d(TAG, "SearchEngine.search success? " + success);
        if (!success) {
            WearSyncService.sendSearchState(mContext, false);
        }
    }

    @Override
    public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp) {
        if (timestamp != mLastSearchTimestamp) {
            Log.w(TAG, "Ignoring stale results update");
            return;
        }
        Log.d(TAG, "onResultsUpdate: " + results.length + " items");
        mLastResults = results;
        WearSyncService.sendSearchResults(mContext, results, true);
    }

    @Override
    public void onResultsEnd(long timestamp) {
        if (timestamp != mLastSearchTimestamp) {
            Log.w(TAG, "Ignoring stale results end");
            return;
        }
        Log.d(TAG, "onResultsEnd. Final count: " + (mLastResults != null ? mLastResults.length : 0));
        if (mLastResults != null) {
            WearSyncService.sendSearchResults(mContext, mLastResults, false);
        } else {
            WearSyncService.sendSearchState(mContext, false);
        }
    }
}
