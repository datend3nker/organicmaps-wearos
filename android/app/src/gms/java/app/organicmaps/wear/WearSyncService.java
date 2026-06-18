package app.organicmaps.wear;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
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
    private static final Set<Long> sSilentSyncCategoryIds = new HashSet<>();
    private static boolean sIsApplyingRemoteUpdate = false;
    private static final Map<String, FileOutputStream> sBookmarkOutputStreams = new HashMap<>();

    private static final Runnable sSyncPrefsRunnable = () -> {
        Log.d("WearSync", "Debounced syncPreferences executing");
        Context context = app.organicmaps.MwmApplication.sInstance;
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

    private static byte[] sLastSentBookmarkUpsert = null;

    public static void syncBookmarksNow() {
        Context context = app.organicmaps.MwmApplication.sInstance;
        if (!isFrameworkReady()) return;

        // BookmarkSyncCore.buildUpsertBatch calls per-bookmark native getters that assert
        // CalledOnOriginalThread; bounce to the main thread if we were invoked off it.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            sHandler.post(WearSyncService::syncBookmarksNow);
            return;
        }

        // Re-send pending deletions first so the watch removes them even if nothing else changed.
        flushTombstones(context);

        byte[] payload = app.organicmaps.sdk.sync.BookmarkSyncCore.buildUpsertBatch(
            context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE);

        if (java.util.Arrays.equals(sLastSentBookmarkUpsert, payload)) {
            Log.d("WearSync", "Bookmark upsert batch unchanged, skipping sync push");
            return;
        }
        sLastSentBookmarkUpsert = payload;

        Log.d("WearSync", "Sending bookmark upsert batch to watch");
        getSyncLayer().sendRawMessage(context, app.organicmaps.sdk.sync.WearProtocol.TYPE_BOOKMARK_UPSERT, payload);
    }

    /** Force a bookmark push regardless of the unchanged-dedup (used on connect to reconcile). */
    public static void syncBookmarksMetadataForced() {
        sLastSentBookmarkUpsert = null;
        syncBookmarksNow();
    }

    /** Apply a per-bookmark LWW upsert batch received from the watch (content-addressed, no KMZ). */
    public static void handleIncomingBookmarkUpsert(Context context, byte[] payload) {
        sHandler.post(() -> {
            if (!isFrameworkReady()) return;
            sIsApplyingRemoteUpdate = true;
            try {
                app.organicmaps.sdk.sync.BookmarkSyncCore.applyUpsertBatch(
                    context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE, payload);
            } finally {
                sIsApplyingRemoteUpdate = false;
            }
            // Refresh snapshot so applied additions aren't later mistaken for local user edits.
            refreshIdentitySnapshot(context);
        });
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
            String safeName = categoryName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File tmpFile = new File(context.getCacheDir(), safeName + ".part");
            FileOutputStream fos = sBookmarkOutputStreams.get(categoryName);
            if (fos == null) {
                fos = new FileOutputStream(tmpFile);
                sBookmarkOutputStreams.put(categoryName, fos);
            }
            fos.write(chunk);
            if (isLast) {
                fos.close();
                sBookmarkOutputStreams.remove(categoryName);

                // The watch exports via prepareCategoriesForSharing, which ALWAYS zips the KML into a
                // KMZ. OM's loader picks the parser by file extension, so a ZIP saved as ".kml" fails
                // to parse and the import never sticks. Sniff the magic and use ".kmz" for ZIP.
                boolean isZip = false;
                try (java.io.FileInputStream sigIn = new java.io.FileInputStream(tmpFile)) {
                    byte[] sig = new byte[4];
                    isZip = sigIn.read(sig) == 4 && sig[0] == 0x50 && sig[1] == 0x4B && sig[2] == 0x03 && sig[3] == 0x04;
                } catch (Exception ignored) {}
                String fileName = safeName + (isZip ? ".kmz" : ".kml");
                File finalFile = new File(context.getCacheDir(), fileName);
                if (finalFile.exists() && !finalFile.delete()) {
                    Log.w("WearSync", "Failed to delete existing file: " + finalFile.getName());
                }
                if (!tmpFile.renameTo(finalFile)) {
                    Log.e("WearSync", "Failed to rename tmp file to " + finalFile.getName());
                }

                Log.d("WearSync", "Successfully received bookmark file from watch: " + categoryName + " (zip=" + isZip + ", merge=" + remoteMerge + ")");
                
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
                            
                            // Background sync is always a non-destructive union-merge: the native
                            // merge de-duplicates by name+position and resolves same-pin edits by
                            // timestamp (LWW), so re-importing never deletes locally-made bookmarks.
                            // (A destructive replace would drop edits made on this device while offline.)
                            if (existing != null) {
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

    /**
     * Diff each category's current bookmark identity set against the last snapshot; removed
     * identities become tombstones (recorded + pushed to the watch) and re-added identities clear a
     * stale tombstone. Runs only for genuine local edits (guarded by sIsApplyingRemoteUpdate in the
     * caller). Mirrors WatchBookmarkSyncManager.detectBookmarkDeletions.
     */
    private static void detectBookmarkDeletions(Context context) {
        try {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE;
            android.content.SharedPreferences snap = context.getSharedPreferences(SNAP_PREFS, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = snap.edit();
            long now = System.currentTimeMillis();
            for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : manager.getCategories()) {
                Set<String> current = app.organicmaps.sdk.sync.BookmarkTombstoneStore.currentIdentities(manager, cat);
                String key = "snap_" + cat.getName();
                Set<String> previous = snap.getStringSet(key, null);
                if (previous != null) {
                    for (String gone : previous) {
                        if (!current.contains(gone)) {
                            app.organicmaps.sdk.sync.BookmarkTombstoneStore.record(context, gone, now);
                            byte[] payload = app.organicmaps.sdk.sync.BookmarkTombstoneStore.encodeFromKey(gone, now);
                            if (payload != null) {
                                Log.d("WearSync", "Bookmark deleted locally -> tombstone " + gone);
                                getSyncLayer().sendRawMessage(context, app.organicmaps.sdk.sync.WearProtocol.TYPE_BOOKMARK_TOMBSTONE, payload);
                            }
                        }
                    }
                    for (String added : current) {
                        if (!previous.contains(added))
                            app.organicmaps.sdk.sync.BookmarkTombstoneStore.clear(context, added);
                    }
                }
                editor.putStringSet(key, current);
            }
            editor.apply();
        } catch (Exception e) {
            Log.w("WearSync", "detectBookmarkDeletions failed: " + e.getMessage());
        }
    }

    /** Re-send every stored tombstone to the watch (covers deletions made while disconnected). */
    private static void flushTombstones(Context context) {
        Map<String, Long> all = app.organicmaps.sdk.sync.BookmarkTombstoneStore.all(context);
        if (all.isEmpty()) return;
        for (Map.Entry<String, Long> e : all.entrySet()) {
            byte[] payload = app.organicmaps.sdk.sync.BookmarkTombstoneStore.encodeFromKey(e.getKey(), e.getValue());
            if (payload != null)
                getSyncLayer().sendRawMessage(context, app.organicmaps.sdk.sync.WearProtocol.TYPE_BOOKMARK_TOMBSTONE, payload);
        }
    }

    /** Apply a tombstone received from the watch: record it and delete the matching local bookmark. */
    public static void applyIncomingTombstone(Context context, byte[] payload) {
        app.organicmaps.sdk.sync.BookmarkTombstoneStore.Parsed parsed = app.organicmaps.sdk.sync.BookmarkTombstoneStore.decode(payload);
        if (parsed == null) return;
        app.organicmaps.sdk.sync.BookmarkTombstoneStore.record(context, parsed.key(), parsed.ts);
        sHandler.post(() -> {
            if (!isFrameworkReady()) return;
            app.organicmaps.sdk.bookmarks.data.BookmarkManager manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE;
            sIsApplyingRemoteUpdate = true;
            try {
                for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : manager.getCategories()) {
                    if (cat.getName().equalsIgnoreCase(parsed.categoryName))
                        app.organicmaps.sdk.sync.BookmarkTombstoneStore.applyToCategory(context, manager, cat);
                }
            } finally {
                sIsApplyingRemoteUpdate = false;
            }
            refreshIdentitySnapshot(context);
        });
    }

    /**
     * After every bookmark file merge, purge bookmarks the union-merge resurrected but that are
     * tombstoned locally, so deletions stay deleted across syncs.
     */
    private static final app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksLoadingListener sBookmarkLoadingListener =
        new app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksLoadingListener() {
            @Override
            public void onBookmarksLoadingFinished() {
                Context context = app.organicmaps.MwmApplication.sInstance;
                sHandler.post(() -> {
                    if (!isFrameworkReady()) return;
                    app.organicmaps.sdk.bookmarks.data.BookmarkManager manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE;
                    sIsApplyingRemoteUpdate = true;
                    try {
                        int removed = 0;
                        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : manager.getCategories())
                            removed += app.organicmaps.sdk.sync.BookmarkTombstoneStore.applyToCategory(context, manager, cat);
                        if (removed > 0)
                            Log.d("WearSync", "Purged " + removed + " resurrected bookmark(s) after merge");
                    } finally {
                        sIsApplyingRemoteUpdate = false;
                    }
                    refreshIdentitySnapshot(context);
                });
            }
        };

    /** Refresh the identity snapshot so a tombstone-driven purge is not later seen as a user deletion. */
    private static void refreshIdentitySnapshot(Context context) {
        try {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE;
            android.content.SharedPreferences snap = context.getSharedPreferences(SNAP_PREFS, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = snap.edit();
            for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : manager.getCategories())
                editor.putStringSet("snap_" + cat.getName(), app.organicmaps.sdk.sync.BookmarkTombstoneStore.currentIdentities(manager, cat));
            editor.apply();
        } catch (Exception e) {
            Log.w("WearSync", "refreshIdentitySnapshot failed: " + e.getMessage());
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

    public static void setSilentSyncInProgress(boolean inProgress) {
        // Not used with the new ID-based logic, but kept for compatibility or clearing
        if (!inProgress) {
            synchronized (sSilentSyncCategoryIds) {
                sSilentSyncCategoryIds.clear();
            }
        }
    }

    public static void markSilentSync(long catId) {
        synchronized (sSilentSyncCategoryIds) {
            sSilentSyncCategoryIds.add(catId);
        }
    }

    public static void setApplyingRemoteUpdate(boolean applying) {
        sIsApplyingRemoteUpdate = applying;
    }

    private static final app.organicmaps.sdk.bookmarks.data.BookmarkManager.BookmarksSharingListener sSharingListener = (result) -> {
        long[] catIds = result.getCategoriesIds();
        boolean isSilent;
        long targetCatId = (catIds != null && catIds.length > 0) ? catIds[0] : -1;
        
        synchronized (sSilentSyncCategoryIds) {
            isSilent = sSilentSyncCategoryIds.contains(targetCatId);
        }

        Log.d("WearSync", "onPreparedFileForSharing: " + result.getCode() + " silent=" + isSilent + " catId=" + targetCatId);
        
        if (result.getCode() == app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult.SUCCESS) {
            if (targetCatId == -1) return;
            String path = result.getSharingPath();
            
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.getCategoryById(targetCatId);
            String catName = cat != null ? cat.getName() : "sync_" + targetCatId;

            if (isSilent) {
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
                        // merge=true: the watch must union-merge this into its existing category
                        // (non-destructive). The native merge de-dups, so this is idempotent.
                        getSyncLayer().sendBookmarkFile(app.organicmaps.MwmApplication.sInstance, catName, chunk, isLast, true);
                        if (isLast) {
                            app.organicmaps.MwmApplication.sInstance.getSharedPreferences("bookmark_sync_state", Context.MODE_PRIVATE)
                                .edit().putLong("last_synced_" + catName, System.currentTimeMillis()).apply();
                            synchronized (sSilentSyncCategoryIds) {
                                sSilentSyncCategoryIds.remove(targetCatId);
                            }
                        }
                    }
                } catch (java.io.IOException e) {
                    Log.e("WearSync", "Failed to silent push bookmarks", e);
                    synchronized (sSilentSyncCategoryIds) {
                        sSilentSyncCategoryIds.remove(targetCatId);
                    }
                }
            }
        } else {
            synchronized (sSilentSyncCategoryIds) {
                sSilentSyncCategoryIds.remove(targetCatId);
            }
        }
    };

    private static final String SNAP_PREFS = "bookmark_identity_snapshot";

    private static final app.organicmaps.sdk.bookmarks.data.DataChangedListener sBookmarkListener = () -> {
        if (sIsApplyingRemoteUpdate) return;
        Context context = app.organicmaps.MwmApplication.sInstance;

        // A union-merge never removes, so per-bookmark deletions must be propagated as tombstones.
        detectBookmarkDeletions(context);

        // Bump per-identity LWW timestamps for any content change (add/rename/recolor/re-describe) so
        // edits — not just adds — propagate to the watch on the next push.
        app.organicmaps.sdk.sync.BookmarkSyncCore.stampLocalChange(
            context, app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE);

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
        return app.organicmaps.MwmApplication.sInstance.getOrganicMaps().arePlatformAndCoreInitialized();
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
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addLoadingListener(sBookmarkLoadingListener);
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(sPrefsListener);
            app.organicmaps.MwmApplication.sInstance.getOrganicMaps().getLocationHelper().addListener(sLocationListener);
            sListenersRegistered = true;
        }

        if ("BLUETOOTH".equals(backend)) {
            sSyncLayer = new BluetoothSyncLayer();
            context.startService(new Intent(context, BluetoothMessageListenerService.class));
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
        // Sync ANY mapped setting, not just keys prefixed "pref_wear_os_". Some synced settings
        // (e.g. pref_sync_notifications) use other key names and were silently dropped here,
        // breaking phone->watch live sync for them.
        SettingsSyncManager mgr = SettingsSyncManager.getInstance(app.organicmaps.MwmApplication.sInstance);
        if (mgr.getCanonicalToLocalMapping().containsValue(key)) {
            if (mgr.isApplyingRemoteUpdates()) return;
            if (getSyncLayer().isIgnoringPreferenceChanges()) return;

            Object value = prefs.getAll().get(key);
            mgr.onSettingChanged(key, value, true);
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

    public static void checkConnection(@NonNull Context context, @NonNull ISyncLayer.ConnectionCallback callback) {
        getSyncLayer().checkConnection(context, callback);
    }

    public static void launchWatchApp(@NonNull Context context) {
        getSyncLayer().launchWatchApp(context);
    }

    public static void onLocalTrafficSent() {
        // Implement logic if needed
    }
}
