package app.organicmaps.wear;

import android.content.Context;
import android.location.Location;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchResult;

import java.util.ArrayList;
import java.util.List;

import app.organicmaps.sync.BluetoothSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearSyncService {
    private static ISyncLayer sSyncLayer;
    private static final List<ISyncLayer.MessageListener> sListeners = new ArrayList<>();
    private static boolean sListenersRegistered = false;
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable sSyncPrefsRunnable = () -> {
        android.util.Log.d("WearSync", "Debounced syncPreferences executing");
        getSyncLayer().syncPreferences(app.organicmaps.MwmApplication.sInstance);
    };

    private static final app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksSharingListener sSharingListener = (result) -> {
        android.util.Log.d("WearSync", "onPreparedFileForSharing: " + result.getCode() + " path: " + result.getSharingPath());
        if (result.getCode() == app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult.SUCCESS) {
            String path = result.getSharingPath();
            long catId = result.getCategoriesIds()[0];
            String ext = result.getMimeType().contains("kmz") ? ".kmz" : ".kml";
            if (result.getMimeType().contains("gpx")) ext = ".gpx";
            if (result.getMimeType().contains("kmb")) ext = ".kmb";
            String fileName = "sync_" + catId + ext;

            java.io.File file = new java.io.File(path);
            long length = file.length();
            long sent = 0;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    sent += read;
                    boolean isLast = sent >= length;
                    byte[] chunk = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                    android.util.Log.d("WearSync", "Sending bookmark chunk for cat " + catId + " (" + fileName + ") isLast=" + isLast);
                    getSyncLayer().sendBookmarkFile(app.organicmaps.MwmApplication.sInstance, catId, fileName, chunk, isLast);
                    
                    // Report progress back to watch so UI can show it
                    int progress = (int) (sent * 100 / length);
                    getSyncLayer().sendMapProgress(app.organicmaps.MwmApplication.sInstance, "Bookmarks: " + catId, progress);
                }
            } catch (java.io.IOException e) {
                android.util.Log.e("WearSync", "Failed to send bookmark file", e);
            }
        } else {
            android.util.Log.w("WearSync", "Bookmark preparation failed with code: " + result.getCode());
        }
    };

    private static final app.organicmaps.sdk.bookmarks.data.DataChangedListener sBookmarkListener = () -> sendBookmarkCategories(app.organicmaps.MwmApplication.sInstance, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());

    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener sPrefsListener = (prefs, key) -> {
        if (key == null) return;
        if (key.startsWith("pref_wear_os_") && !key.equals("pref_wear_os_last_sync_timestamp")) {
            ISyncLayer syncLayer = getSyncLayer();
            if (syncLayer instanceof BluetoothSyncLayer && ((BluetoothSyncLayer) syncLayer).isIgnoringPreferenceChanges()) {
                return;
            }
            syncPreferences(app.organicmaps.MwmApplication.sInstance);
        }
    };

    public static synchronized ISyncLayer getSyncLayer() {
        if (sSyncLayer == null) {
            initSyncLayer(app.organicmaps.MwmApplication.sInstance);
        }
        return sSyncLayer;
    }

    private static boolean isFrameworkReady() {
        return app.organicmaps.MwmApplication.sInstance != null && 
               app.organicmaps.MwmApplication.sInstance.getOrganicMaps().arePlatformAndCoreInitialized();
    }

    public static synchronized void initSyncLayer(@Nullable Context context) {
        if (sSyncLayer != null) {
            sSyncLayer.stop();
        }

        if (!sListenersRegistered && context != null) {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addCategoriesUpdatesListener(sBookmarkListener);
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addSharingListener(sSharingListener);
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(sPrefsListener);
            sListenersRegistered = true;
        }

        sSyncLayer = new BluetoothSyncLayer();
        
        // Initial sync
        if (context != null) {
            syncPreferences(context);
            if (isFrameworkReady()) {
                sendBookmarkCategories(context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());
                sendSearchHistory(context);
            }
        }

        // Re-register all listeners to the new sync layer
        for (ISyncLayer.MessageListener listener : sListeners) {
            sSyncLayer.addMessageListener(listener);
        }
    }

    public static synchronized void addMessageListener(ISyncLayer.MessageListener listener) {
        sListeners.add(listener);
        getSyncLayer().addMessageListener(listener);
    }

    public static synchronized void removeMessageListener(ISyncLayer.MessageListener listener) {
        sListeners.remove(listener);
        getSyncLayer().removeMessageListener(listener);
    }

    public static void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        getSyncLayer().sendMapRequestToWatch(context, countryId);
    }

    public static void syncPreferences(@NonNull Context context) {
        sHandler.removeCallbacks(sSyncPrefsRunnable);
        sHandler.postDelayed(sSyncPrefsRunnable, 500); // 500ms debounce
    }

    public static void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        if (isFrameworkReady())
            getSyncLayer().updateNavigation(context, info, location);
    }

    public static void sendSearchState(@NonNull Context context, boolean isSearching) {
        if (isFrameworkReady())
            getSyncLayer().sendSearchState(context, isSearching);
    }

    public static void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        if (isFrameworkReady())
            getSyncLayer().sendSearchResults(context, results, isSearching);
    }

    public static void sendSearchHistory(@NonNull Context context) {
        getSyncLayer().sendSearchHistory(context);
    }

    public static void startNavigation(@NonNull Context context) {
        if (isFrameworkReady())
            getSyncLayer().startNavigation(context);
    }
    
    public static void stopNavigation(@NonNull Context context) {
        if (isFrameworkReady())
            getSyncLayer().stopNavigation(context);
    }

    public static void sendTrackRecordingStatus(@NonNull Context context, boolean isRecording) {
        if (isFrameworkReady())
            getSyncLayer().sendTrackRecordingStatus(context, isRecording);
    }

    public static void sendBookmarkCategories(@NonNull Context context, @NonNull List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories) {
        if (isFrameworkReady())
            getSyncLayer().sendBookmarkCategories(context, categories);
    }

    public static void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId,
                                           long requestId, @NonNull byte[] features) {
        if (isFrameworkReady())
            getSyncLayer().sendMapTileResponse(context, nodeId, requestId, features);
    }

    public static void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress) {
        if (isFrameworkReady())
            getSyncLayer().sendMapProgress(context, countryId, progress);
    }

    public static void checkConnection(@NonNull Context context, @NonNull ISyncLayer.ConnectionCallback callback) {
        getSyncLayer().checkConnection(context, callback);
    }

    public static void launchWatchApp(@NonNull Context context) {
        getSyncLayer().launchWatchApp(context);
    }
}
