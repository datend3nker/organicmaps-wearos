package app.organicmaps.wear;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.organicmaps.sdk.sync.WearProtocol;
import app.organicmaps.sync.ISyncLayer;

public class WearMessageListenerService extends WearableListenerService {
    private static final String TAG = "WearMessageListener";

    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        Log.d(TAG, "DEBUG_GMS: Phone WearMessageListenerService.onCreate()");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "DEBUG_GMS: Phone WearMessageListenerService.onDestroy()");
        super.onDestroy() ;
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        app.organicmaps.sdk.sync.WearLog.logReceived("PHONE", "GMS_RAW", messageEvent.getPath(), messageEvent.getData() != null ? messageEvent.getData().length : 0);
        super.onMessageReceived(messageEvent);
        
        // Forward TO the sync layer for global notification and routing
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
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                if (dataMap.containsKey("protocolVersion") && dataMap.getByte("protocolVersion") != WearProtocol.PROTOCOL_VERSION) {
                    continue;
                }

                if (WearProtocol.PATH_PREFERENCES_WATCH.equals(path) || WearProtocol.PATH_PREFERENCES_UPDATES.equals(path)) {
                    mMainHandler.post(() -> {
                        if (WearProtocol.PATH_PREFERENCES_UPDATES.equals(path)) {
                            List<SettingsSyncManager.SettingUpdate> updates = new ArrayList<>();
                            for (String key : dataMap.keySet()) {
                                if (key.equals("_trigger") || key.equals("protocolVersion")) continue;
                                DataMap item = dataMap.getDataMap(key);
                                if (item != null) {
                                    updates.add(new SettingsSyncManager.SettingUpdate(key, item.get("v"), item.getLong("t"), item.getLong("ver", 0)));
                                }
                            }
                            SettingsSyncManager.getInstance(this).applyRemoteUpdates(updates);
                            WearSyncService.onRemotePreferencesApplied();
                        } else {
                            List<SettingsSyncManager.SettingUpdate> updates = new ArrayList<>();
                            for (String key : dataMap.keySet()) {
                                if (key.startsWith("ts_") || key.startsWith("v_")) continue;
                                if (key.equals("timestamp") || key.equals("protocolVersion")) continue;
                                long ts = dataMap.getLong("ts_" + key, dataMap.getLong("timestamp", 0));
                                long ver = dataMap.getLong("v_" + key, 0);
                                updates.add(new SettingsSyncManager.SettingUpdate(key, dataMap.get(key), ts, ver));
                            }
                            SettingsSyncManager.getInstance(this).applyRemoteUpdates(updates);
                            WearSyncService.onRemotePreferencesApplied();
                        }
                    });
                } else if (WearProtocol.PATH_SEARCH_HISTORY_SYNC.equals(path)) {
                    ArrayList<String> history = dataMap.getStringArrayList("history");
                    if (history != null) {
                        mMainHandler.post(() -> {
                            for (String q : history) {
                                app.organicmaps.sdk.search.SearchRecents.add(q, this);
                            }
                        });
                    }
                } else if (WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS.equals(path)) {
                    String countryId = dataMap.getString("countryId");
                    int progress = dataMap.getInt("progress", 0);
                    if (countryId != null) {
                        byte[] cIdBytes = countryId.getBytes(StandardCharsets.UTF_8);
                        ByteBuffer buffer = ByteBuffer.allocate(4 + cIdBytes.length + 4);
                        buffer.putInt(cIdBytes.length);
                        buffer.put(cIdBytes);
                        buffer.putInt(progress);
                        onMessageReceived(WearProtocol.PATH_MAP_DOWNLOAD_PROGRESS, buffer.array(), host != null ? host : "unknown");
                    }
                }
            }
        }
    }

    private void onMessageReceived(String path, byte[] data, String sourceNodeId) {
        WearSyncService.getSyncLayer().notifyMessageReceived(path, data, sourceNodeId);
    }
}
