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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import app.organicmaps.sync.BluetoothSyncLayer;
import app.organicmaps.sync.GmsSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearSyncService {
    private static ISyncLayer sSyncLayer;
    private static final List<ISyncLayer.MessageListener> sListeners = new ArrayList<>();
    private static boolean sListenersRegistered = false;
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean sIsSilentSyncInProgress = false;
    private static boolean sIsApplyingRemoteUpdate = false;
    private static final Map<String, FileOutputStream> sBookmarkOutputStreams = new HashMap<>();
    private static final Set<String> sPendingMerges = new HashSet<>();

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

    private static final Runnable sSyncBookmarksRunnable = () -> {
        Log.d("WearSync", "Debounced syncBookmarksNow executing");
        syncBookmarksNow();
    };

    private static byte[] sLastSentBookmarkMetadata = null;

    public static void syncBookmarksNow() {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null || !isFrameworkReady()) return;
        
        List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories();
        android.content.SharedPreferences prefs = context.getSharedPreferences("bookmark_sync_state", Context.MODE_PRIVATE);
        
        ByteBuffer buffer = ByteBuffer.allocate(4 + categories.size() * 256);
        buffer.putInt(categories.size());
        
        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : categories) {
            byte[] nameBytes = cat.getName().getBytes(StandardCharsets.UTF_8);
            buffer.putInt(nameBytes.length);
            buffer.put(nameBytes);
            buffer.putInt(cat.getBookmarksCount());
            buffer.putInt(cat.getTracksCount());
            buffer.putLong(prefs.getLong("last_local_edit_" + cat.getName(), 0));
            buffer.putLong(prefs.getLong("last_synced_" + cat.getName(), 0));
        }
        
        byte[] payload = new byte[buffer.position()];
        buffer.flip();
        buffer.get(payload);
        
        if (java.util.Arrays.equals(sLastSentBookmarkMetadata, payload)) {
            Log.d("WearSync", "Bookmark metadata unchanged, skipping sync push");
            return;
        }
        sLastSentBookmarkMetadata = payload;
        
        Log.d("WearSync", "Sending bookmarks metadata to watch. Count: " + categories.size());
        getSyncLayer().sendRawMessage(context, app.organicmaps.sdk.sync.WearProtocol.TYPE_BOOKMARKS_METADATA, payload);
    }

    public static void handleIncomingBookmarksMetadata(Context context, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < 4) return;
        int count = buffer.getInt();
        
        List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> localCategories = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories();
        android.content.SharedPreferences prefs = context.getSharedPreferences("bookmark_sync_state", Context.MODE_PRIVATE);
        
        for (int i = 0; i < count; i++) {
            int nameLen = buffer.getInt();
            byte[] nameBytes = new byte[nameLen];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            buffer.getInt(); // remoteBmkCount
            buffer.getInt(); // remoteTrkCount
            long remoteLastEdit = buffer.getLong();
            long remoteLastSynced = buffer.getLong();
            
            long localLastEdit = prefs.getLong("last_local_edit_" + name, 0);
            long localLastSynced = prefs.getLong("last_synced_" + name, 0);
            
            boolean localChanged = localLastEdit > localLastSynced;
            boolean remoteChanged = remoteLastEdit > localLastSynced;
            
            if (localChanged && remoteChanged) {
                Log.w("WearSync", "CONFLICT detected for category: " + name);
                Intent intent = new Intent("app.organicmaps.wear.ACTION_BOOKMARK_CONFLICT");
                intent.putExtra("categoryName", name);
                context.sendBroadcast(intent);
            } else if (remoteChanged) {
                Log.i("WearSync", "Watch has newer updates for: " + name + ". Requesting PULL.");
                getSyncLayer().sendRawMessage(context, app.organicmaps.sdk.sync.WearProtocol.TYPE_COMMAND, buildCommandPayload(app.organicmaps.sdk.sync.WearProtocol.PATH_BOOKMARK_SYNC_REQUEST, name.getBytes(StandardCharsets.UTF_8)));
            } else if (localChanged) {
                Log.i("WearSync", "Phone has newer updates for: " + name + ". Triggering PUSH.");
                app.organicmaps.sdk.bookmarks.data.BookmarkCategory localCat = null;
                for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : localCategories) {
                    if (cat.getName().equals(name)) {
                        localCat = cat;
                        break;
                    }
                }
                if (localCat != null) {
                    sIsSilentSyncInProgress = true;
                    app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.prepareCategoriesForSharing(new long[]{localCat.getId()}, app.organicmaps.sdk.bookmarks.data.KmlFileType.Binary);
                }
            }
        }
    }

    public static void handleIncomingBookmarkFile(Context context, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < 5) return;
        byte flags = buffer.get();
        boolean isLast = (flags & 1) != 0;
        boolean remoteMerge = (flags & 2) != 0;
        int nameLen = buffer.getInt();
        if (buffer.remaining() < nameLen) return;
        byte[] nameBytes = new byte[nameLen];
        buffer.get(nameBytes);
        String categoryName = new String(nameBytes, StandardCharsets.UTF_8);
        
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        
        try {
            String fileName = categoryName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".kml";
            FileOutputStream fos = sBookmarkOutputStreams.get(categoryName);
            if (fos == null) {
                File file = new File(context.getCacheDir(), fileName + ".tmp");
                fos = new FileOutputStream(file);
                sBookmarkOutputStreams.put(categoryName, fos);
            }
            fos.write(chunk);
            if (isLast) {
                fos.close();
                sBookmarkOutputStreams.remove(categoryName);
                File tmpFile = new File(context.getCacheDir(), fileName + ".tmp");
                File finalFile = new File(context.getCacheDir(), fileName);
                if (finalFile.exists()) finalFile.delete();
                tmpFile.renameTo(finalFile);
                
                Log.d("WearSync", "Successfully received bookmark file from watch: " + categoryName + " (merge=" + remoteMerge + ")");
                
                sHandler.post(() -> {
                    if (isFrameworkReady()) {
                        sIsApplyingRemoteUpdate = true;
                        try {
                            app.organicmaps.sdk.bookmarks.data.BookmarkManager manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE;
                            app.organicmaps.sdk.bookmarks.data.BookmarkCategory existing = null;
                            for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : manager.getCategories()) {
                                if (cat.getName().equalsIgnoreCase(categoryName)) {
                                    existing = cat;
                                    break;
                                }
                            }
                            
                            boolean shouldMerge = remoteMerge || sPendingMerges.remove(categoryName);
                            
                            if (existing != null && !shouldMerge) {
                                manager.deleteCategory(existing.getId());
                                manager.loadBookmarksFile(finalFile.getAbsolutePath(), true);
                            } else if (existing != null) {
                                manager.loadBookmarksFile(finalFile.getAbsolutePath(), true, existing.getId());
                            } else {
                                manager.loadBookmarksFile(finalFile.getAbsolutePath(), true);
                            }
                            
                            context.getSharedPreferences("bookmark_sync_state", Context.MODE_PRIVATE)
                                .edit().putLong("last_synced_" + categoryName, System.currentTimeMillis()).apply();
                        } finally {
                            sIsApplyingRemoteUpdate = false;
                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e("WearSync", "Failed to handle incoming bookmark file", e);
            sBookmarkOutputStreams.remove(categoryName);
        }
    }

    public static byte[] buildCommandPayload(String path, byte[] data) {
        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + pathBytes.length + data.length);
        buffer.putInt(pathBytes.length);
        buffer.put(pathBytes);
        buffer.put(data);
        return buffer.array();
    }

    public static boolean isSilentSyncInProgress() {
        return sIsSilentSyncInProgress;
    }

    public static void setSilentSyncInProgress(boolean inProgress) {
        sIsSilentSyncInProgress = inProgress;
    }

    public static void setApplyingRemoteUpdate(boolean applying) {
        sIsApplyingRemoteUpdate = applying;
    }

    public static void addPendingMerge(String categoryName) {
        sPendingMerges.add(categoryName);
    }

    private static final app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksSharingListener sSharingListener = (result) -> {
        Log.d("WearSync", "onPreparedFileForSharing: " + result.getCode() + " silent=" + sIsSilentSyncInProgress);
        if (result.getCode() == app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult.SUCCESS) {
            String path = result.getSharingPath();
            long[] catIds = result.getCategoriesIds();
            if (catIds == null || catIds.length == 0) return;
            
            long catId = catIds[0];
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategoryById(catId);
            String catName = cat != null ? cat.getName() : "sync_" + catId;

            if (sIsSilentSyncInProgress) {
                java.io.File file = new java.io.File(path);
                long length = file.length();
                Log.i("WearSync", "Silent pushing bookmark file to watch: " + catName + " (" + length + " bytes)");
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    long sent = 0;
                    while ((read = fis.read(buffer)) != -1) {
                        sent += read;
                        boolean isLast = sent >= length;
                        byte[] chunk = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                        getSyncLayer().sendBookmarkFile(app.organicmaps.MwmApplication.sInstance, catName, chunk, isLast, false);
                        if (isLast) {
                            app.organicmaps.MwmApplication.sInstance.getSharedPreferences("bookmark_sync_state", Context.MODE_PRIVATE)
                                .edit().putLong("last_synced_" + catName, System.currentTimeMillis()).apply();
                            sIsSilentSyncInProgress = false;
                        }
                    }
                } catch (java.io.IOException e) {
                    Log.e("WearSync", "Failed to silent push bookmarks", e);
                    sIsSilentSyncInProgress = false;
                }
            }
        } else {
            sIsSilentSyncInProgress = false;
        }
    };

    private static final app.organicmaps.sdk.bookmarks.data.DataChangedListener sBookmarkListener = () -> {
        if (sIsApplyingRemoteUpdate) return;
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null) return;
        
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_state", Context.MODE_PRIVATE);
        List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories();
        
        boolean anyChanged = false;
        android.content.SharedPreferences.Editor editor = syncPrefs.edit();
        long now = System.currentTimeMillis();
        
        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : categories) {
            String name = cat.getName();
            // Create a fingerprint of the category to detect renames, edits, and count changes
            String fingerprint = name + ":" + cat.getBookmarksCount() + ":" + cat.getTracksCount() + ":" + cat.isVisible();
            String lastFingerprint = syncPrefs.getString("fp_" + name, "");
            
            if (!fingerprint.equals(lastFingerprint)) {
                Log.d("WearSync", "Bookmark change detected for " + name + " (User Action). Fingerprint: " + fingerprint);
                editor.putLong("last_local_edit_" + name, now);
                editor.putString("fp_" + name, fingerprint);
                anyChanged = true;
            }
        }
        if (anyChanged) {
            editor.apply();
            sHandler.removeCallbacks(sSyncBookmarksRunnable);
            sHandler.postDelayed(sSyncBookmarksRunnable, 1000);
        }
    };

    private static Location sLastSentLocation = null;
    private static long sLastNavStatusTime = 0;
    private static String sLastSentNextStreet = "";
    private static int sLastSentDistanceToTurn = -1;

    private static final app.organicmaps.sdk.location.LocationListener sLocationListener = (location) -> {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (context == null) return;
        if (isFrameworkReady()) {
            RoutingInfo info = app.organicmaps.sdk.routing.RoutingController.get().getCachedRoutingInfo();
            long now = android.os.SystemClock.elapsedRealtime();
            
            boolean isNavigating = app.organicmaps.sdk.routing.RoutingController.get().isNavigating();
            boolean forceUpdate = (now - sLastNavStatusTime > 10000); // 10s keep-alive
            
            if (!forceUpdate) {
                if (isNavigating && info != null) {
                    // Active navigation: update if street changed or significant distance change (5m)
                    double currentDist = info.distToTurn.mDistance;
                    if (!info.nextStreet.equals(sLastSentNextStreet) || Math.abs(currentDist - sLastSentDistanceToTurn) > 5) {
                        forceUpdate = true;
                    }
                } else if (sLastSentLocation != null) {
                    // Stationary: update if moved > 10 meters
                    if (location.distanceTo(sLastSentLocation) > 10) {
                        forceUpdate = true;
                    }
                } else {
                    forceUpdate = true;
                }
            }

            if (forceUpdate) {
                getSyncLayer().updateNavigation(context, info, location);
                sLastSentLocation = new Location(location);
                sLastNavStatusTime = now;
                if (info != null) {
                    sLastSentNextStreet = info.nextStreet;
                    sLastSentDistanceToTurn = (int) info.distToTurn.mDistance;
                }
            }
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
        String backend = "GMS";
        if (context != null) {
            backend = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("pref_wear_os_backend", "GMS");
        }
        
        if (sSyncLayer != null) {
            if ("BLUETOOTH".equals(backend) && sSyncLayer instanceof BluetoothSyncLayer) return;
            if ("GMS".equals(backend) && sSyncLayer instanceof GmsSyncLayer) return;
        }

        if (sSyncLayer != null) {
            if (context != null) {
                sSyncLayer.sendBackendSwitch(context, backend);
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

        if ("BLUETOOTH".equals(backend)) {
            sSyncLayer = new BluetoothSyncLayer();
            if (context != null) {
                context.startService(new Intent(context, BluetoothMessageListenerService.class));
            }
        } else {
            sSyncLayer = new GmsSyncLayer();
            if (context != null) {
                context.stopService(new Intent(context, BluetoothMessageListenerService.class));
            }
        }
        
        if (context != null) {
            if (!getSyncLayer().isIgnoringPreferenceChanges()) {
                syncPreferences(context);
            }
        }

        for (ISyncLayer.MessageListener listener : sListeners) {
            sSyncLayer.addMessageListener(listener);
        }
    }

    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener sPrefsListener = (prefs, key) -> {
        if (key == null) return;
        if (key.startsWith("pref_wear_os_") && !key.equals("pref_wear_os_last_sync_timestamp")) {
            if (SettingsSyncManager.getInstance(app.organicmaps.MwmApplication.sInstance).isApplyingRemoteUpdates()) return;
            if (getSyncLayer().isIgnoringPreferenceChanges()) return;

            Object value = prefs.getAll().get(key);
            SettingsSyncManager.getInstance(app.organicmaps.MwmApplication.sInstance).onSettingChanged(key, value, true);
            syncPreferences(app.organicmaps.MwmApplication.sInstance);
        }
    };

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
        sHandler.postDelayed(sSyncPrefsRunnable, 100); 
    }

    public static void onRemotePreferencesApplied() {
    }

    public static void onConnectionEstablished(@NonNull Context context) {
        getSyncLayer().sendHandshake(context);
        List<SettingsSyncManager.SettingUpdate> dirty = SettingsSyncManager.getInstance(context).getDirtyUpdates();
        if (!dirty.isEmpty()) {
            getSyncLayer().syncPreferenceUpdates(context, dirty);
        } else {
            getSyncLayer().syncPreferences(context);
        }
        syncBookmarksNow();
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

    public static void updateNavigation(@NonNull Context context, @Nullable RoutingInfo info, @Nullable Location location, @Nullable float[] lats, @Nullable float[] lons) {
        if (isFrameworkReady())
            getSyncLayer().updateNavigation(context, info, location);
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
        sIsApplyingRemoteUpdate = true;
        try {
            for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                if (cat.getName().equals(oldName)) {
                    cat.setName(newName);
                    break;
                }
            }
        } finally {
            sIsApplyingRemoteUpdate = false;
        }

        getSyncLayer().renameBookmarkCategory(context, oldName, newName);
        
        android.content.SharedPreferences syncPrefs = context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE);
        long ts = syncPrefs.getLong(oldName, 0);
        syncPrefs.edit().remove(oldName).putLong(newName, ts > 0 ? ts : System.currentTimeMillis()).apply();
    }

    public static void deleteBookmarkCategory(@NonNull Context context, @NonNull String name) {
        sIsApplyingRemoteUpdate = true;
        try {
            for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategories()) {
                if (cat.getName().equals(name)) {
                    app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.deleteCategory(cat.getId());
                    break;
                }
            }
        } finally {
            sIsApplyingRemoteUpdate = false;
        }

        getSyncLayer().deleteBookmarkCategory(context, name);
        context.getSharedPreferences("bookmark_sync_timestamps", Context.MODE_PRIVATE)
               .edit().remove(name).apply();
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

    public static boolean isWatchAppConnected() {
        return getSyncLayer().isLinked();
    }

    public static void onLocalTrafficSent() {
        // Implement logic if needed
    }
}
