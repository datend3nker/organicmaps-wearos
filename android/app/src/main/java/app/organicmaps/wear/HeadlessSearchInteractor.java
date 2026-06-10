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
        WearCompanionNotificationManager.showSearchNotification(mContext, query);
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
        Log.i(TAG, "performSearch START: '" + query + "' at " + lat + ", " + lon);
        
        // Ensure engine is alive and initialized
        SearchEngine.INSTANCE.initialize();
        
        String storagePath = app.organicmaps.sdk.settings.StoragePathManager.findMapsStorage(mContext);
        Log.d(TAG, "Native storage path: " + storagePath);

        int worldStatus = app.organicmaps.sdk.downloader.MapManager.nativeGetStatus("World");
        Log.i(TAG, "DEBUG_WEAR_PIPELINE: World map status: " + worldStatus);
        
        if (worldStatus != 4) { // STATUS_DONE
            Log.e(TAG, "CRITICAL: World map is missing or incomplete (Status: " + worldStatus + "). Search WILL fail.");
        }

        WearSyncService.sendSearchState(mContext, true);
        mLastResults = null;
        mLastSearchTimestamp = System.nanoTime();
        SearchEngine.INSTANCE.cancel();
        Framework.nativeRestoreDownloadQueue();
        
        boolean hasLocation = (lat != 0.0 || lon != 0.0);
        if (!hasLocation) {
            Location loc = MwmApplication.from(mContext).getLocationHelper().getSavedLocation();
            if (loc != null) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
                hasLocation = true;
                Log.d(TAG, "Using last saved position for headless search: " + lat + ", " + lon);
            }
        }
        
        final double finalLat = lat;
        final double finalLon = lon;
        final boolean finalHasLocation = hasLocation;

        // Initialize the viewport for the search engine - FIX: Ensure rect is calculated
        int zoom = hasLocation ? 13 : 1;
        Log.d(TAG, "Setting search viewport: " + finalLat + ", " + finalLon + " zoom: " + zoom);
        Framework.nativeSetSearchViewport(finalLat, finalLon, zoom);

        Log.d(TAG, "Calling SearchEngine.search...");
        boolean success = SearchEngine.INSTANCE.search(mContext, query, false, mLastSearchTimestamp, finalHasLocation, finalLat, finalLon);
        
        if (!success) {
            Log.w(TAG, "SearchEngine.search failed immediately.");
            WearSyncService.sendSearchState(mContext, false);
        } else {
            Log.i(TAG, "SearchEngine.search call success? true");
        }
    }

    @Override
    public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp) {
        if (timestamp != mLastSearchTimestamp) {
            Log.w(TAG, "Ignoring stale results update");
            return;
        }
        Log.i(TAG, "onResultsUpdate: Received " + results.length + " results for query at " + timestamp);
        mLastResults = results;
        WearSyncService.sendSearchResults(mContext, results, true);
    }

    @Override
    public void onResultsEnd(long timestamp) {
        if (timestamp != mLastSearchTimestamp) {
            Log.w(TAG, "Ignoring stale results end");
            return;
        }
        Log.i(TAG, "onResultsEnd. Final count: " + (mLastResults != null ? mLastResults.length : 0));
        WearCompanionNotificationManager.hideNotification(mContext, WearCompanionNotificationManager.NOTIFICATION_ID_SEARCH);
        if (mLastResults != null) {
            WearSyncService.sendSearchResults(mContext, mLastResults, false);
        } else {
            // Explicitly notify watch that search is DONE even if no results
            WearSyncService.sendSearchState(mContext, false);
        }
    }
}
