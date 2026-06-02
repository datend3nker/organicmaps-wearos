package app.organicmaps.wear;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchRecents;
import app.organicmaps.sdk.search.SearchResult;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.Node;

import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;

import app.organicmaps.BuildConfig;
import app.organicmaps.sync.BluetoothSyncLayer;
import app.organicmaps.sync.GmsSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearSyncService {
    private static ISyncLayer sSyncLayer;
    private static final List<ISyncLayer.MessageListener> sListeners = new ArrayList<>();
    private static boolean sListenersRegistered = false;
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable sSyncPrefsRunnable = () -> {
        Log.d("WearSync", "Debounced syncPreferences executing");
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null) return;
        List<SettingsSyncManager.SettingUpdate> dirty = SettingsSyncManager.getInstance(context).getDirtyUpdates();
        if (!dirty.isEmpty()) {
            getSyncLayer().syncPreferenceUpdates(context, dirty);
        } else {
            getSyncLayer().syncPreferences(context);
        }
    };

    private static final app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksSharingListener sSharingListener = (result) -> {
        android.util.Log.d("WearSync", "onPreparedFileForSharing: " + result.getCode() + " path: " + result.getSharingPath());
        if (result.getCode() == app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult.SUCCESS) {
            String path = result.getSharingPath();
            long[] catIds = result.getCategoriesIds();
            if (catIds == null || catIds.length == 0) {
                android.util.Log.w("WearSync", "No category IDs in sharing result");
                return;
            }
            long catId = catIds[0];
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategoryById(catId);
            String catName = cat != null ? cat.getName() : "sync_" + catId;

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
                    android.util.Log.d("WearSync", "Sending bookmark chunk for " + catName + " isLast=" + isLast);
                    getSyncLayer().sendBookmarkFile(app.organicmaps.MwmApplication.sInstance, catName, chunk, isLast);
                    
                    // Report progress back to watch so UI can show it
                    int progress = (int) (sent * 100 / length);
                    getSyncLayer().sendMapProgress(app.organicmaps.MwmApplication.sInstance, "Bookmarks: " + catName, progress);
                }
            }
catch (java.io.IOException e) {
                android.util.Log.e("WearSync", "Failed to send bookmark file", e);
            }
        } else {
            android.util.Log.w("WearSync", "Bookmark preparation failed with code: " + result.getCode());
        }
    };

    private static final app.organicmaps.sdk.bookmarks.data.DataChangedListener sBookmarkListener = () -> sendBookmarkCategories(app.organicmaps.MwmApplication.sInstance, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());

    private static final app.organicmaps.sdk.location.LocationListener sLocationListener = (location) -> {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null) return;
        
        // Sync location even when not navigating (for companion mode)
        if (isFrameworkReady()) {
            RoutingInfo info = app.organicmaps.sdk.routing.RoutingController.get().getCachedRoutingInfo();
            getSyncLayer().updateNavigation(context, info, location);
        }
    };

    private static long sLastLocalChangeTime = 0;
    private static long sLastRemoteAppliedTime = 0;

    public static void onLocalTrafficSent() {
        sLastLocalChangeTime = System.currentTimeMillis();
    }

    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener sPrefsListener = (prefs, key) -> {
        if (key == null) return;
        if (key.startsWith("pref_wear_os_") && !key.equals("pref_wear_os_last_sync_timestamp")) {
            if (SettingsSyncManager.getInstance(app.organicmaps.MwmApplication.sInstance).isApplyingRemoteUpdates()) {
                return;
            }
            
            ISyncLayer syncLayer = getSyncLayer();
            if (syncLayer.isIgnoringPreferenceChanges()) {
                return;
            }

            Object value = prefs.getAll().get(key);
            SettingsSyncManager.getInstance(app.organicmaps.MwmApplication.sInstance).onSettingChanged(key, value, true);
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
        android.util.Log.d("WearSync", "initSyncLayer called. Flavor: " + BuildConfig.FLAVOR);

        String backend = "GMS";
        if (context != null) {
            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
            backend = prefs.getString("pref_wear_os_backend", "GMS");
        }

        if (sSyncLayer != null) {
            if (context != null) {
                android.util.Log.d("WearSync", "Notifying watch about backend switch to: " + backend);
                sSyncLayer.sendBackendSwitch(context, backend);
                sSyncLayer.syncPreferences(context);
            }
            sSyncLayer.stop();
        }

        if (!sListenersRegistered && context != null) {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addCategoriesUpdatesListener(sBookmarkListener);
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addSharingListener(sSharingListener);
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(sPrefsListener);
            app.organicmaps.MwmApplication.sInstance.getOrganicMaps().getLocationHelper().addListener(sLocationListener);
            sListenersRegistered = true;
        }

        if (BuildConfig.FLAVOR.equals("oss")) {
            sSyncLayer = new BluetoothSyncLayer();
        } else {
            if (context != null) {
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                backend = prefs.getString("pref_wear_os_backend", "GMS");
            }

            if ("BLUETOOTH".equals(backend)) {
                android.util.Log.d("WearSync", "Selecting BLUETOOTH backend");
                sSyncLayer = new BluetoothSyncLayer();
                if (context != null) {
                    context.startService(new Intent(context, BluetoothMessageListenerService.class));
                }
            } else {
                android.util.Log.d("WearSync", "Selecting GMS backend");
                sSyncLayer = new GmsSyncLayer();
                if (context != null) {
                    context.stopService(new Intent(context, BluetoothMessageListenerService.class));
                }
            }
            if (context != null) {
                sSyncLayer.sendBackendSwitch(context, backend);
            }
        }
        
        // Initial sync ONLY if not already applying
        if (context != null) {
            ISyncLayer syncLayer = getSyncLayer();
            if (!syncLayer.isIgnoringPreferenceChanges()) {
                syncPreferences(context);
            }
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

    public static void syncPreferences(@NonNull Context context) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            sHandler.post(() -> syncPreferences(context));
            return;
        }
        
        sHandler.removeCallbacks(sSyncPrefsRunnable);
        sHandler.postDelayed(sSyncPrefsRunnable, 100); // 100ms debounce for live feel
    }

    public static void onRemotePreferencesApplied() {
        sLastRemoteAppliedTime = System.currentTimeMillis();
    }

    public static void onConnectionEstablished(@NonNull Context context) {
        Log.d("WearSync", "Connection established, syncing pending settings");
        List<SettingsSyncManager.SettingUpdate> dirty = SettingsSyncManager.getInstance(context).getDirtyUpdates();
        if (!dirty.isEmpty()) {
            getSyncLayer().syncPreferenceUpdates(context, dirty);
        } else {
            // Even if nothing is dirty, send a full sync periodically to ensure consistency
            getSyncLayer().syncPreferences(context);
        }
    }

    public static void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        getSyncLayer().sendMapRequestToWatch(context, countryId);
    }

    public static void updateNavigation(@NonNull Context context, @Nullable RoutingInfo info, @Nullable Location location) {
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
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            sHandler.post(() -> sendSearchHistory(context));
            return;
        }
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
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            sHandler.post(() -> sendBookmarkCategories(context, categories));
            return;
        }
        if (isFrameworkReady())
            getSyncLayer().sendBookmarkCategories(context, categories);
    }

    public static void renameBookmarkCategory(@NonNull Context context, @NonNull String oldName, @NonNull String newName) {
        getSyncLayer().renameBookmarkCategory(context, oldName, newName);
    }

    public static void deleteBookmarkCategory(@NonNull Context context, @NonNull String name) {
        getSyncLayer().deleteBookmarkCategory(context, name);
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

    public static void sendRouteBuildProgress(@NonNull Context context, int progress) {
        getSyncLayer().sendRouteBuildProgress(context, progress);
    }

    public static void sendMwmBytes(@NonNull Context context, @NonNull String mwmName, long offset, @NonNull byte[] data) {
        getSyncLayer().sendMwmBytes(context, mwmName, offset, data);
    }

    public static void checkConnection(@NonNull Context context, @NonNull ISyncLayer.ConnectionCallback callback) {
        getSyncLayer().checkConnection(context, callback);
    }

    public static void launchWatchApp(@NonNull Context context) {
        getSyncLayer().launchWatchApp(context);
    }
}
