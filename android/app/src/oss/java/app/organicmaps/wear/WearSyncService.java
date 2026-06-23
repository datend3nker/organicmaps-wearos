package app.organicmaps.wear;

import android.content.Context;
import android.location.Location;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.organicmaps.sync.BluetoothSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearSyncService {
    private static ISyncLayer sSyncLayer;
    private static final List<ISyncLayer.MessageListener> sListeners = new ArrayList<>();
    private static boolean sListenersRegistered = false;
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Set<Long> sSilentSyncCategoryIds = new HashSet<>();
    private static boolean sIsApplyingRemoteUpdate = false;

    private static final Runnable sSyncPrefsRunnable = () -> {
        android.util.Log.d("WearSync", "Debounced syncPreferences executing");
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null) return;
        List<SettingsSyncManager.SettingUpdate> dirty = SettingsSyncManager.getInstance(context).getDirtyUpdates();
        if (!dirty.isEmpty()) {
            getSyncLayer().syncPreferenceUpdates(context, dirty);
        } else {
            getSyncLayer().syncPreferences(context);
        }
    };

    private static final Runnable sSyncBookmarksRunnable = () -> {
        android.util.Log.d("WearSync", "Debounced sendBookmarkCategories executing");
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null) return;
        sendBookmarkCategories(context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());
    };

    private static long sLastRemoteAppliedTime = 0;

    private static final app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksSharingListener sSharingListener = (result) -> {
        long[] catIds = result.getCategoriesIds();
        boolean isSilent;
        long targetCatId = (catIds != null && catIds.length > 0) ? catIds[0] : -1;

        synchronized (sSilentSyncCategoryIds) {
            isSilent = sSilentSyncCategoryIds.contains(targetCatId);
        }

        android.util.Log.d("WearSync", "onPreparedFileForSharing: " + result.getCode() + " silent=" + isSilent + " catId=" + targetCatId);
        
        if (result.getCode() == app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult.SUCCESS) {
            if (targetCatId == -1) return;
            String path = result.getSharingPath();

            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategoryById(targetCatId);
            String catName = cat != null ? cat.getName() : "sync_" + targetCatId;

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
                    getSyncLayer().sendBookmarkFile(app.organicmaps.MwmApplication.sInstance, catName, chunk, isLast, false);
                    
                    // Report progress back to watch so UI can show it
                    int progress = (int) (sent * 100 / length);
                    getSyncLayer().sendMapProgress(app.organicmaps.MwmApplication.sInstance, "Bookmarks: " + catName, progress);

                    if (isLast && isSilent) {
                        synchronized (sSilentSyncCategoryIds) {
                            sSilentSyncCategoryIds.remove(targetCatId);
                        }
                    }
                }
            } catch (java.io.IOException e) {
                android.util.Log.e("WearSync", "Failed to send bookmark file", e);
                synchronized (sSilentSyncCategoryIds) {
                    sSilentSyncCategoryIds.remove(targetCatId);
                }
            }
        } else {
            synchronized (sSilentSyncCategoryIds) {
                sSilentSyncCategoryIds.remove(targetCatId);
            }
            android.util.Log.w("WearSync", "Bookmark preparation failed with code: " + result.getCode());
        }
    };

    private static final app.organicmaps.sdk.bookmarks.data.DataChangedListener sBookmarkListener = () -> {
        sHandler.removeCallbacks(sSyncBookmarksRunnable);
        sHandler.postDelayed(sSyncBookmarksRunnable, 1000);
    };

    private static final app.organicmaps.sdk.location.LocationListener sLocationListener = (location) -> {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null) return;
        
        // Sync location even when not navigating (for companion mode)
        if (isFrameworkReady()) {
            RoutingInfo info = app.organicmaps.sdk.routing.RoutingController.get().getCachedRoutingInfo();
            getSyncLayer().updateNavigation(context, info, location);
        }
    };

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

    public static void pushDownloadedMaps(@Nullable Context context) {
        if (context == null || !isFrameworkReady()) return;
        java.util.List<app.organicmaps.sdk.downloader.CountryItem> downloaded = new java.util.ArrayList<>();
        app.organicmaps.sdk.downloader.MapManager.nativeListItems(null, 0, 0, false, true, downloaded);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (app.organicmaps.sdk.downloader.CountryItem item : downloaded)
            if (item.present) ids.add(item.id);
        getSyncLayer().sendDownloadedMaps(context.getApplicationContext(), ids);
    }

    private static final app.organicmaps.sdk.downloader.MapManager.StorageCallback sStorageCallback =
        new app.organicmaps.sdk.downloader.MapManager.StorageCallback() {
            @Override
            public void onStatusChanged(java.util.List<app.organicmaps.sdk.downloader.MapManager.StorageCallbackData> data) {
                for (app.organicmaps.sdk.downloader.MapManager.StorageCallbackData d : data) {
                    if (d.newStatus == app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE) {
                        pushDownloadedMaps(app.organicmaps.MwmApplication.sInstance);
                        return;
                    }
                }
            }
            @Override
            public void onProgress(String countryId, long localSize, long remoteSize) {}
        };

    public static synchronized void initSyncLayer(@Nullable Context context) {
        if (sSyncLayer != null) {
            sSyncLayer.stop();
            if (context != null) {
                context.stopService(new android.content.Intent(context, BluetoothMessageListenerService.class));
            }
        }

        if (!sListenersRegistered && context != null) {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addCategoriesUpdatesListener(sBookmarkListener);
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addSharingListener(sSharingListener);
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(sPrefsListener);
            app.organicmaps.MwmApplication.sInstance.getOrganicMaps().getLocationHelper().addListener(sLocationListener);
            app.organicmaps.sdk.downloader.MapManager.nativeSubscribe(sStorageCallback);
            sListenersRegistered = true;
        }

        sSyncLayer = new BluetoothSyncLayer();
        
        if (context != null) {
            context.startService(new android.content.Intent(context, BluetoothMessageListenerService.class));
        }

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

    public static void sendHandshakeToWatch(@NonNull Context context) {
        getSyncLayer().sendHandshake(context);
    }

    public static void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        getSyncLayer().sendMapRequestToWatch(context, countryId);
    }

    public static void syncPreferences(@NonNull Context context) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            sHandler.post(() -> syncPreferences(context));
            return;
        }
        sHandler.removeCallbacks(sSyncPrefsRunnable);
        sHandler.postDelayed(sSyncPrefsRunnable, 100); // 100ms debounce
    }

    public static void onRemotePreferencesApplied() {
        sLastRemoteAppliedTime = System.currentTimeMillis();
    }

    public static void onConnectionEstablished(@NonNull Context context) {
        android.util.Log.d("WearSync", "Connection established, syncing pending settings");
        List<SettingsSyncManager.SettingUpdate> dirty = SettingsSyncManager.getInstance(context).getDirtyUpdates();
        if (!dirty.isEmpty()) {
            getSyncLayer().syncPreferenceUpdates(context, dirty);
        } else {
            getSyncLayer().syncPreferences(context);
        }
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

    public static void sendRouteBuildProgress(@NonNull Context context, int progress) {
        if (isFrameworkReady())
            getSyncLayer().sendRouteBuildProgress(context, progress);
    }

    public static void deleteBookmarkCategory(@NonNull Context context, @NonNull String name) {
        getSyncLayer().deleteBookmarkCategory(context, name);
    }

    public static void renameBookmarkCategory(@NonNull Context context, @NonNull String oldName, @NonNull String newName) {
        getSyncLayer().renameBookmarkCategory(context, oldName, newName);
    }

    public static void checkConnection(@NonNull Context context, @NonNull ISyncLayer.ConnectionCallback callback) {
        getSyncLayer().checkConnection(context, callback);
    }

    public static boolean isSilentSyncInProgress() {
        synchronized (sSilentSyncCategoryIds) {
            return !sSilentSyncCategoryIds.isEmpty();
        }
    }

    public static void setSilentSyncInProgress(boolean inProgress) {
        if (!inProgress) {
            synchronized (sSilentSyncCategoryIds) {
                sSilentSyncCategoryIds.clear();
            }
        }
    }

    public static void setApplyingRemoteUpdate(boolean applying) {
        sIsApplyingRemoteUpdate = applying;
    }

    public static void markSilentSync(long catId) {
        synchronized (sSilentSyncCategoryIds) {
            sSilentSyncCategoryIds.add(catId);
        }
    }

    public static void addPendingMerge(String categoryName) {
        // Stub for OSS
    }

    public static byte[] buildCommandPayload(String path, byte[] data) {
        // Stub for OSS
        return new byte[0];
    }

    public static void handleIncomingBookmarksMetadata(Context context, byte[] payload) {
        // Stub for OSS
    }

    public static void handleIncomingBookmarkFile(Context context, byte[] payload) {
        // Stub for OSS
    }

    public static void handleIncomingBookmarkUpsert(Context context, byte[] payload) {
        // Stub for OSS
    }

    public static void applyIncomingTombstone(Context context, byte[] payload) {
        // Stub for OSS
    }

    public static void syncBookmarksMetadataForced() {
        // Stub for OSS
    }

    public static void syncBookmarksNow() {
        android.util.Log.d("WearSync", "syncBookmarksNow stub called");
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null || !isFrameworkReady()) return;
        sendBookmarkCategories(context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories());
    }

    public static void launchWatchApp(@NonNull Context context) {
        getSyncLayer().launchWatchApp(context);
    }

    public static boolean isWatchAppConnected() {
        return getSyncLayer().isLinked();
    }
}
