package app.organicmaps.sync;

import android.content.Context;
import android.location.Location;
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
    void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId);
    void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features);
    void sendPong(@NonNull Context context, @NonNull String nodeId);
    void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress);
    
    default void parsePreferences(@NonNull Context context, @NonNull byte[] data, @NonNull android.content.SharedPreferences prefs) {}

    interface MessageListener {
        void onMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId);
    }
    
    void addMessageListener(@NonNull MessageListener listener);
    void removeMessageListener(@NonNull MessageListener listener);
    
    void notifyMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId);
    
    default void stop() {}
}
