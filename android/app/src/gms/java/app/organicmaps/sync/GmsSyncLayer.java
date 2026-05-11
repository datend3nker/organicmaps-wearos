package app.organicmaps.sync;

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
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.MessageClient;

import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GmsSyncLayer implements ISyncLayer {
    private static final String TAG = "GmsSyncLayer";
    private static final String PATH_NAVIGATION = "/navigation/status";
    private static final String PATH_START_NAVIGATION = "/navigation/start";
    private static final String PATH_SEARCH_RESULTS = "/search/results";
    private static final String PATH_SEARCH_HISTORY = "/search/history";
    private static final String PATH_PREFERENCES = "/preferences";
    private static final String PATH_MAP_TILE_RESPONSE = "/map/tile/response";

    private final List<MessageListener> mListeners = new CopyOnWriteArrayList<>();

    @Override
    public void syncPreferences(@NonNull Context context) {
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        boolean standaloneMode = prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_standalone_mode), false);
        boolean mapEnabled = standaloneMode || prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_map_enabled), false);
        boolean watchLocalMode = standaloneMode || prefs.getBoolean(context.getString(app.organicmaps.R.string.pref_wear_os_watch_local_mode), false);
        String mapDownloadMode = prefs.getString(context.getString(app.organicmaps.R.string.pref_wear_os_map_download_mode), "BLUETOOTH_ONLY");
        String backend = prefs.getString(context.getString(app.organicmaps.R.string.pref_wear_os_backend), "GMS");
        int poiMask = prefs.getInt("poiCategoriesMask", 0x3F);

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_PREFERENCES);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("mapEnabled", mapEnabled);
        map.putBoolean("watchLocalMode", watchLocalMode);
        map.putBoolean("standaloneMode", standaloneMode);
        map.putString("mapDownloadMode", mapDownloadMode);
        map.putString("backend", backend);
        map.putInt("poiCategoriesMask", poiMask);
        map.putLong("timestamp", System.currentTimeMillis());
        
        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnSuccessListener(dataItem -> Log.d(TAG, "Sent updated preferences to watch"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send preferences: " + e.getMessage()));
    }

    @Override
    public void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();

        map.putString("distToTurn", info.distToTurn != null ? info.distToTurn.toString(context) : "");
        map.putString("nextStreet", info.nextStreet != null ? info.nextStreet : "");
        map.putInt("carDirection", info.carDirection.ordinal());
        map.putInt("pedestrianDirection", info.pedestrianDirection.ordinal());
        map.putInt("exitNum", info.exitNum);
        map.putBoolean("active", app.organicmaps.sdk.routing.RoutingController.get().isNavigating());
        map.putDouble("completionPercent", info.completionPercent);
        map.putString("distToTarget", info.distToTarget != null ? info.distToTarget.toString(context) : "");
        map.putInt("eta", info.totalTimeInSeconds);
        map.putDouble("speedLimitMps", info.speedLimitMps);
        map.putInt("routerType", app.organicmaps.sdk.Router.get().ordinal());
        map.putDouble("distToTurnMeters", info.distToTurn != null ? info.distToTurn.mDistance : -1.0);
        map.putDouble("turnLat", info.turnLat);
        map.putDouble("turnLon", info.turnLon);
        
        if (location != null) {
            map.putDouble("speedMps", location.getSpeed());
            map.putDouble("lat", location.getLatitude());
            map.putDouble("lon", location.getLongitude());
            map.putFloat("bearing", location.hasBearing() ? location.getBearing() : -1f);
        } else {
            map.putDouble("speedMps", -1.0);
        }
        
        map.putLong("timestamp", System.currentTimeMillis());

        try {
            app.organicmaps.sdk.routing.JunctionInfo[] junctions = app.organicmaps.sdk.Framework.nativeGetRouteJunctionPoints(20.0);
            if (junctions != null && junctions.length > 0) {
                float[] lats = new float[junctions.length];
                float[] lons = new float[junctions.length];
                for (int i = 0; i < junctions.length; i++) {
                    lats[i] = (float) junctions[i].mLat;
                    lons[i] = (float) junctions[i].mLon;
                }
                map.putFloatArray("routeLats", lats);
                map.putFloatArray("routeLons", lons);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract route junctions", e);
        }

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send navigation data", e));
    }

    @Override
    public void startNavigation(@NonNull Context context) {
        syncPreferences(context);
        
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("active", true);
        map.putLong("timestamp", System.currentTimeMillis());
        
        String[] missingMaps = app.organicmaps.sdk.routing.RoutingController.get().getLastMissingMaps();
        if (missingMaps != null && missingMaps.length > 0) {
            ArrayList<String> missingList = new ArrayList<>();
            for (String m : missingMaps) missingList.add(m);
            map.putStringArrayList("missingMaps", missingList);
        }

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);

        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                Wearable.getMessageClient(context).sendMessage(node.getId(), PATH_START_NAVIGATION, new byte[0]);
            }
        });
    }

    @Override
    public void stopNavigation(@NonNull Context context) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("active", false);
        map.putLong("timestamp", System.currentTimeMillis());

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void sendSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_SEARCH_RESULTS);
        DataMap map = putDataMapReq.getDataMap();
        
        ArrayList<DataMap> resultList = new ArrayList<>();
        int count = Math.min(results.length, 30);
        for (int i = 0; i < count; i++) {
            SearchResult res = results[i];
            DataMap resMap = new DataMap();
            resMap.putString("name", res.getTitle(context) != null ? res.getTitle(context) : "");
            String desc = "";
            if (res.description != null) {
                if (res.description.localizedFeatureType != null) desc = res.description.localizedFeatureType;
                else if (res.description.region != null) desc = res.description.region;
            }
            resMap.putString("description", desc);
            resMap.putDouble("lat", res.lat);
            resMap.putDouble("lon", res.lon);
            resMap.putInt("type", res.type);
            resultList.add(resMap);
        }
        map.putDataMapArrayList("results", resultList);
        map.putBoolean("isSearching", isSearching);
        map.putLong("timestamp", System.currentTimeMillis());

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void sendSearchState(@NonNull Context context, boolean isSearching) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_SEARCH_RESULTS);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("isSearching", isSearching);
        map.putLong("timestamp", System.currentTimeMillis());

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void sendSearchHistory(@NonNull Context context) {
        SearchRecents.refresh();
        int size = SearchRecents.getSize();
        
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_SEARCH_HISTORY);
        DataMap map = putDataMapReq.getDataMap();
        
        ArrayList<String> history = new ArrayList<>();
        int count = Math.min(size, 10);
        for (int i = 0; i < count; i++) {
            history.add(SearchRecents.get(i));
        }
        map.putStringArrayList("history", history);
        map.putLong("timestamp", System.currentTimeMillis());

        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void sendMapRequestToWatch(@NonNull Context context, @NonNull String countryId) {
        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                Wearable.getMessageClient(context).sendMessage(node.getId(), "/map/download/request", countryId.getBytes());
            }
        });
    }

    @Override
    public void sendMapTileResponse(@NonNull Context context, @NonNull String nodeId, long requestId, @NonNull byte[] features) {
        byte[] dataToSend = features;
        boolean compressed = false;
        if (features.length > 512) {
            try {
                dataToSend = app.organicmaps.util.GzipUtils.compress(features);
                compressed = true;
            } catch (java.io.IOException e) {
                Log.w(TAG, "Compression failed, sending raw");
            }
        }

        ByteBuffer payload = ByteBuffer.allocate(8 + 1 + dataToSend.length);
        payload.putLong(requestId);
        payload.put((byte) (compressed ? 1 : 0));
        payload.put(dataToSend);

        Wearable.getMessageClient(context)
                .sendMessage(nodeId, PATH_MAP_TILE_RESPONSE, payload.array())
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send map tile response", e));
    }

    @Override
    public void sendPong(@NonNull Context context, @NonNull String nodeId) {
        Wearable.getMessageClient(context).sendMessage(nodeId, "/pong", new byte[0]);
    }

    @Override
    public void sendMapProgress(@NonNull Context context, @NonNull String countryId, int progress) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/map/download/progress");
        DataMap map = putDataMapReq.getDataMap();
        map.putString("countryId", countryId);
        map.putInt("progress", progress);
        map.putLong("timestamp", System.currentTimeMillis());
        
        com.google.android.gms.wearable.PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }

    @Override
    public void addMessageListener(@NonNull MessageListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeMessageListener(@NonNull MessageListener listener) {
        mListeners.remove(listener);
    }

    @Override
    public void notifyMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        for (MessageListener listener : mListeners) {
            listener.onMessageReceived(path, data, sourceNodeId);
        }
    }

    // This would be called from a WearableListenerService proxy
    public void notifyMessageReceived(MessageEvent event) {
        notifyMessageReceived(event.getPath(), event.getData(), event.getSourceNodeId());
    }
}
