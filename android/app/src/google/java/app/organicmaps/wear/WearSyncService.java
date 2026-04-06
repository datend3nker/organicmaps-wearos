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

public class WearSyncService {
    private static final String TAG = "WearSyncService";
    private static final String PATH_NAVIGATION = "/navigation/status";
    private static final String PATH_START_NAVIGATION = "/navigation/start";
    private static final String PATH_SEARCH_RESULTS = "/search/results";
    private static final String PATH_SEARCH_HISTORY = "/search/history";
    private static final String PATH_PREFERENCES = "/preferences";

    public static void syncPreferences(@NonNull Context context) {
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean mapEnabled = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_map_enabled), false);
        String mapDownloadMode = prefs.getString(context.getString(app.organicmaps.R.string.pref_wear_os_map_download_mode), "BLUETOOTH_ONLY");

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_PREFERENCES);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("mapEnabled", mapEnabled);
        map.putString("mapDownloadMode", mapDownloadMode);
        
        var putDataReq = putDataMapReq.asPutDataRequest();
        Task<DataItem> putDataTask = Wearable.getDataClient(context).putDataItem(putDataReq);
        putDataTask.addOnSuccessListener(dataItem -> Log.d(TAG, "Sent updated preferences to watch"))
                   .addOnFailureListener(e -> Log.e(TAG, "Failed to send preferences: " + e.getMessage()));
    }

    public static void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();

        map.putString("distToTurn", info.distToTurn.toString(context));
        map.putString("nextStreet", info.nextStreet);
        map.putInt("carDirection", info.carDirection.ordinal());
        map.putInt("pedestrianDirection", info.pedestrianDirection.ordinal());
        map.putInt("exitNum", info.exitNum);
        map.putBoolean("active", true);
        map.putDouble("completionPercent", info.completionPercent);
        map.putString("distToTarget", info.distToTarget.toString(context));
        map.putInt("eta", info.totalTimeInSeconds);
        map.putDouble("speedLimitMps", info.speedLimitMps);
        
        if (location != null) {
            double speed = location.getSpeed();
            Log.d(TAG, "Syncing speed: " + speed + " m/s");
            map.putDouble("speedMps", speed);
        } else {
            map.putDouble("speedMps", -1.0);
        }
        
        map.putLong("timestamp", System.currentTimeMillis());

        var putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send navigation data", e));
    }

    public static void sendSearchState(@NonNull Context context, boolean isSearching) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_SEARCH_RESULTS);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("isSearching", isSearching);
        map.putLong("timestamp", System.currentTimeMillis());

        var putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    public static void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        Log.d(TAG, "sendSearchResults: count=" + results.length + ", isSearching=" + isSearching);
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_SEARCH_RESULTS);
        DataMap map = putDataMapReq.getDataMap();
        
        ArrayList<DataMap> resultList = new ArrayList<>();
        int count = Math.min(results.length, 30);
        for (int i = 0; i < count; i++) {
            SearchResult res = results[i];
            DataMap resMap = new DataMap();
            resMap.putString("name", res.getTitle(context));
            resMap.putString("description", res.description != null ? res.description.localizedFeatureType : "");
            resMap.putDouble("lat", res.lat);
            resMap.putDouble("lon", res.lon);
            resMap.putInt("type", res.type);
            resultList.add(resMap);
        }
        map.putDataMapArrayList("results", resultList);
        map.putBoolean("isSearching", isSearching);
        map.putLong("timestamp", System.currentTimeMillis());

        var putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "Successfully sent search results"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send search results", e));
    }

    public static void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        int size = SearchRecents.getSize();
        Log.d(TAG, "sendSearchHistory: count=" + size);
        
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_SEARCH_HISTORY);
        DataMap map = putDataMapReq.getDataMap();
        
        ArrayList<String> history = new ArrayList<>();
        int count = Math.min(size, 10);
        for (int i = 0; i < count; i++) {
            history.add(SearchRecents.get(i));
        }
        map.putStringArrayList("history", history);
        map.putLong("timestamp", System.currentTimeMillis());

        var putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "Successfully sent history"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send history", e));
    }

    public static void startNavigation(@NonNull Context context) {
        Log.d(TAG, "Sending start navigation signal to Wear");
        syncPreferences(context);
        
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("active", true);
        map.putLong("timestamp", System.currentTimeMillis());

        var putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);

        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                Wearable.getMessageClient(context).sendMessage(node.getId(), PATH_START_NAVIGATION, new byte[0]);
            }
        });
    }
    
    public static void stopNavigation(@NonNull Context context) {
        Log.d(TAG, "Sending stop navigation signal to Wear");
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("active", false);
        map.putLong("timestamp", System.currentTimeMillis());

        var putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }
}
