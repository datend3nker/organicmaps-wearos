package app.organicmaps.wear;

import android.content.Context;
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
import java.nio.ByteBuffer;

import app.organicmaps.sync.GmsSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearSyncService {
    private static final ISyncLayer sSyncLayer = new GmsSyncLayer();

    public static ISyncLayer getSyncLayer() {
        return sSyncLayer;
    }

    public static void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        sSyncLayer.sendMapRequestToWatch(context, countryId);
    }

    public static void syncPreferences(@NonNull Context context) {
        sSyncLayer.syncPreferences(context);
    }

    public static void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        sSyncLayer.updateNavigation(context, info, location);
    }

    public static void sendSearchState(@NonNull Context context, boolean isSearching) {
        sSyncLayer.sendSearchState(context, isSearching);
    }

    public static void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        sSyncLayer.sendSearchResults(context, results, isSearching);
    }

    public static void sendSearchHistory(@NonNull Context context) {
        sSyncLayer.sendSearchHistory(context);
    }

    public static void startNavigation(@NonNull Context context) {
        sSyncLayer.startNavigation(context);
    }
    
    public static void stopNavigation(@NonNull Context context) {
        sSyncLayer.stopNavigation(context);
    }

    public static void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId,
                                           long requestId, @NonNull byte[] features) {
        sSyncLayer.sendMapTileResponse(context, nodeId, requestId, features);
    }
}
