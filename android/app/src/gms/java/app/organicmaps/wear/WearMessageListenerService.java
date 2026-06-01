package app.organicmaps.wear;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import app.organicmaps.SplashActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.settings.RoadType;
import app.organicmaps.location.TrackRecordingService;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import app.organicmaps.sync.GmsSyncLayer;
import app.organicmaps.sync.ISyncLayer;

public class WearMessageListenerService extends WearableListenerService implements ISyncLayer.MessageListener {
    private static final String TAG = "WearMessageListener";
    private static final int SEARCH_SELECT_MIN_SIZE = Double.BYTES * 2 + Integer.BYTES;

    private static final String PATH_STOP_NAVIGATION = "/navigation/stop";
    private static final String PATH_SEARCH_QUERY = "/search/query";
    private static final String PATH_SEARCH_SELECT = "/search/select";
    private static final String PATH_SEARCH_HISTORY_REQUEST = "/search/history/request";
    private static final String PATH_PING = "/ping";
    private static final String PATH_PREFERENCES_REQUEST = "/preferences/request";
    private static final String PATH_START_NAVIGATION_REQUEST = "/navigation/start/request";
    private static final String PATH_TRACK_RECORDING_TOGGLE = "/track/recording/toggle";
    private static final String PATH_BOOKMARK_VISIBLE_TOGGLE = "/bookmark/visible/toggle";
    private static final String PATH_BOOKMARK_SYNC_REQUEST = "/bookmark/sync/request";
    private static final String PATH_BOOKMARKS_REQUEST = "/bookmarks/request";
    private static final String PATH_MAP_DOWNLOAD_REQUEST = "/map/download/request";
    private static final String PATH_VIRTUAL_MWM_REQUEST = "/virtual_mwm/request";
    private static final String PATH_VIRTUAL_MWM_METADATA_REQUEST = "/virtual_mwm/metadata_request";

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());



    @Override
    public void onCreate() {
        Log.d(TAG, "DEBUG_GMS: Phone WearMessageListenerService.onCreate()");
        super.onCreate();
        WearSyncService.addMessageListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "DEBUG_GMS: Phone WearMessageListenerService.onStartCommand()");
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "DEBUG_GMS: Phone WearMessageListenerService.onDestroy()");
        super.onDestroy();
        WearSyncService.removeMessageListener(this);
    }

    @Override
    public void onPeerConnected(@NonNull com.google.android.gms.wearable.Node node) {
        super.onPeerConnected(node);
        Log.d(TAG, "Watch connected: " + node.getDisplayName());
        mMainHandler.post(() -> {
            android.widget.Toast.makeText(this, "Watch connected: " + node.getDisplayName(), android.widget.Toast.LENGTH_SHORT).show();
            WearSyncService.syncPreferences(this);
        });
        // Verify capability
        Wearable.getCapabilityClient(this)
            .getCapability("organic_maps_watch_app", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener(capabilityInfo -> {
                if (capabilityInfo.getNodes().contains(node)) {
                    Log.d(TAG, "Watch verified via capability: " + node.getDisplayName());
                }
            });
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        Log.d(TAG, "DEBUG_GMS: Phone onMessageReceived: " + messageEvent.getPath() + " from " + messageEvent.getSourceNodeId());
        WearSyncService.getSyncLayer().notifyMessageReceived(
            messageEvent.getPath(), messageEvent.getData(), messageEvent.getSourceNodeId());
    }

    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEventBuffer) {
        super.onDataChanged(dataEventBuffer);
        Log.d(TAG, "DEBUG_GMS: onDataChanged: received " + dataEventBuffer.getCount() + " events");
        for (DataEvent event : dataEventBuffer) {
            String path = event.getDataItem().getUri().getPath();
            String host = event.getDataItem().getUri().getHost();
            Log.d(TAG, "DEBUG_GMS: onDataChanged path: " + path + " type: " + event.getType() + " host: " + host);
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                if ("/preferences/watch".equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    Log.d(TAG, "DEBUG_GMS: Applying preferences from watch. Timestamp: " + dataMap.getLong("timestamp", 0) + " host: " + host);
                    mMainHandler.post(() -> {
                        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
                        WearSyncService.getSyncLayer().parsePreferences(this, dataMap.toByteArray(), prefs);
                        // Re-init happens inside applyPreferencesFromDataMap which is called by parsePreferences in GmsSyncLayer
                    });
                } else if ("/search/history/sync".equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    ArrayList<String> history = dataMap.getStringArrayList("history");
                    Log.d(TAG, "DEBUG_GMS: Received search history sync. Size: " + (history != null ? history.size() : 0));
                    if (history != null) {
                        mMainHandler.post(() -> {
                            for (String q : history) {
                                app.organicmaps.sdk.search.SearchRecents.add(q, this);
                            }
                        });
                    }
                } else if ("/map/download/progress".equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    String countryId = dataMap.getString("countryId");
                    int progress = dataMap.getInt("progress", 0);
                    Log.d(TAG, "DEBUG_GMS: Received map progress sync. " + countryId + " -> " + progress + "%");
                    
                    if (countryId != null) {
                        byte[] cIdBytes = countryId.getBytes(StandardCharsets.UTF_8);
                        ByteBuffer buffer = ByteBuffer.allocate(4 + cIdBytes.length + 4);
                        buffer.putInt(cIdBytes.length);
                        buffer.put(cIdBytes);
                        buffer.putInt(progress);
                        onMessageReceived("/map/download/progress", buffer.array(), host != null ? host : "unknown");
                    }
                }
            } else if (event.getType() == DataEvent.TYPE_DELETED) {
                Log.d(TAG, "DEBUG_GMS: Data deleted: " + path);
            }
        }
    }

    @Override
    public void onMessageReceived(@NonNull String path, @NonNull byte[] data, @NonNull String sourceNodeId) {
        WearMessageRouter.onMessageReceived(this, path, data, sourceNodeId);
    }

    // Removed streamMapToWatch - logic moved to WearMapStreamingHelper
}
