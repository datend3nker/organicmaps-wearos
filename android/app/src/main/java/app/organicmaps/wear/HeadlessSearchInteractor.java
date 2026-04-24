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
        Log.d(TAG, "Starting headless search for: " + query);
        try {
            if (MwmApplication.from(mContext).getOrganicMaps().arePlatformAndCoreInitialized()) {
                performSearch(query);
                return;
            }
            boolean asyncInit = MwmApplication.from(mContext).initOrganicMaps(() -> {
                Log.d(TAG, "Framework initialized headless.");
                performSearch(query);
            });
            if (!asyncInit) {
                performSearch(query);
            }
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to init organic maps: ", e);
            WearSyncService.sendSearchState(mContext, false);
        }
    }

    private void performSearch(@NonNull String query) {
        WearSyncService.sendSearchState(mContext, true);
        mLastResults = null;
        mLastSearchTimestamp = System.nanoTime();
        SearchEngine.INSTANCE.cancel();
        
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
        
        boolean hasLocation = (loc != null);
        double lat = hasLocation ? loc.getLatitude() : 0.0;
        double lon = hasLocation ? loc.getLongitude() : 0.0;
        
        // Initialize the viewport for the search engine, otherwise searches are endlessly delayed
        // since the search API waits for the map to be rendered and `OnViewportChanged` to be called.
        // On headless Wear OS we never render the map, so we set a synthetic viewport.
        int zoom = hasLocation ? 16 : 1;
        Framework.nativeSetSearchViewport(lat, lon, zoom);

        boolean success = SearchEngine.INSTANCE.search(mContext, query, false, mLastSearchTimestamp, hasLocation, lat, lon);
        Log.d(TAG, "Started search? " + success);
        if (!success) {
            WearSyncService.sendSearchState(mContext, false);
        }
    }

    @Override
    public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp) {
        if (timestamp != mLastSearchTimestamp) return;
        Log.d(TAG, "onResultsUpdate: " + results.length);
        mLastResults = results;
        WearSyncService.sendSearchResults(mContext, results, true);
    }

    @Override
    public void onResultsEnd(long timestamp) {
        if (timestamp != mLastSearchTimestamp) return;
        Log.d(TAG, "onResultsEnd");
        if (mLastResults != null) {
            WearSyncService.sendSearchResults(mContext, mLastResults, false);
        } else {
            WearSyncService.sendSearchState(mContext, false);
        }
    }
}
