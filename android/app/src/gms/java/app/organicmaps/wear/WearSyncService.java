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
import java.util.List;
import java.nio.ByteBuffer;

import app.organicmaps.BuildConfig;
import app.organicmaps.sync.BluetoothSyncLayer;
import app.organicmaps.sync.GmsSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearSyncService {
    private static ISyncLayer sSyncLayer;
    private static final List<ISyncLayer.MessageListener> sListeners = new ArrayList<>();

    public static synchronized ISyncLayer getSyncLayer() {
        if (sSyncLayer == null) {
            initSyncLayer(null);
        }
        return sSyncLayer;
    }

    public static synchronized void initSyncLayer(@Nullable Context context) {
        if (sSyncLayer != null) {
            sSyncLayer.stop();
        }

        if (BuildConfig.FLAVOR.equals("oss")) {
            sSyncLayer = new BluetoothSyncLayer();
        } else {
            String backend = "GMS";
            if (context != null) {
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
                backend = prefs.getString("pref_wear_os_backend", "GMS");
            }

            if ("BLUETOOTH".equals(backend)) {
                sSyncLayer = new BluetoothSyncLayer();
            } else {
                sSyncLayer = new GmsSyncLayer();
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

    public static void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress) {
        sSyncLayer.sendMapProgress(context, countryId, progress);
    }
}
