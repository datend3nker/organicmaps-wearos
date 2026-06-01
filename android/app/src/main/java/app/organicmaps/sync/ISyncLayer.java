package app.organicmaps.sync;

import android.content.Context;
import android.location.Location;
import java.util.List;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchResult;

/**
 * Abstraction for device-to-device synchronization.
 * Supports different transport layers (GMS Wearable, Bluetooth RFCOMM, etc.).
 */
public interface ISyncLayer {
    void syncPreferences(@NonNull Context context);
    void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location);
    void startNavigation(@NonNull Context context);
    void stopNavigation(@NonNull Context context);
    void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching);
    void sendSearchState(@NonNull Context context, boolean isSearching);
    void sendSearchHistory(@NonNull Context context);
    void requestMwmMetadata(@NonNull Context context, @NonNull String mwmName);
    void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId);
    void sendMapChunk(@NonNull Context context, @NonNull String mapId, byte[] chunk, boolean isLast);
    void sendPong(@NonNull Context context, @NonNull String nodeId);
    void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress);
    void sendMapNotFound(@NonNull Context context, @NonNull String mapId);
    void sendBackendSwitch(@NonNull Context context, @NonNull String newBackend);
    void sendTrackRecordingStatus(@NonNull Context context, boolean isRecording);
    void sendBookmarkCategories(@NonNull Context context, @NonNull List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories);
    void sendBookmarkFile(@NonNull Context context, long catId, @NonNull String fileName, @NonNull byte[] data, boolean isLast);
    void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features);
    void sendMwmBytes(@NonNull Context context, @NonNull String mwmName, long offset, @NonNull byte[] data);
    void sendMwmMetadata(@NonNull Context context, @NonNull String mwmName, long totalSize);
    void streamMapFile(@NonNull Context context, @NonNull String nodeId, @NonNull String mapId, @NonNull java.io.File file);
    void checkConnection(@NonNull Context context, @NonNull ConnectionCallback callback);
    void launchWatchApp(@NonNull Context context);

    default boolean isIgnoringPreferenceChanges() {
        return false;
    }

    interface ConnectionCallback {
        void onConnectionResult(boolean connected, ConnectionType type);
    }

    enum ConnectionType {
        NONE,
        BLUETOOTH,
        GMS
    }
    
    default void parsePreferences(@NonNull Context context, @NonNull byte[] data, @NonNull android.content.SharedPreferences prefs) {}

    interface MessageListener {
        void onMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId);
    }
    
    void addMessageListener(@NonNull MessageListener listener);
    void removeMessageListener(@NonNull MessageListener listener);
    
    void notifyMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId);
    
    default void stop() {}
}
