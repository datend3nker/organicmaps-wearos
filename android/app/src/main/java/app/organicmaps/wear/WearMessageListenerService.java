package app.organicmaps.wear;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import app.organicmaps.MwmActivity;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class WearMessageListenerService extends WearableListenerService {
    private static final String TAG = "WearMessageListener";
    private static final String PATH_STOP_NAVIGATION = "/navigation/stop";
    private static final String PATH_SEARCH_QUERY = "/search/query";
    private static final String PATH_SEARCH_SELECT = "/search/select";
    private static final String PATH_SEARCH_HISTORY_REQUEST = "/search/history/request";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        Log.d(TAG, "onMessageReceived: " + messageEvent.getPath());
        final String path = messageEvent.getPath();
        
        if (path.equals(PATH_STOP_NAVIGATION)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Stopping navigation per watch request");
                RoutingController.get().cancel();
            });
        } else if (path.equals(PATH_SEARCH_QUERY)) {
            String query = new String(messageEvent.getData(), StandardCharsets.UTF_8);
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Starting headless search for: " + query);
                HeadlessSearchInteractor.getInstance(this).startSearch(query);
            });
        } else if (path.equals(PATH_SEARCH_SELECT)) {
            ByteBuffer buffer = ByteBuffer.wrap(messageEvent.getData());
            double lat = buffer.getDouble();
            double lon = buffer.getDouble();
            int routerType = buffer.getInt();
            byte[] nameBytes = new byte[buffer.remaining()];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Watch selected: " + name + " (" + lat + ", " + lon + ") Mode: " + routerType);
                
                Intent intent = new Intent(this, MwmActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                intent.putExtra("wear_route_lat", lat);
                intent.putExtra("wear_route_lon", lon);
                intent.putExtra("wear_route_router", routerType);
                intent.putExtra("wear_route_name", name);
                startActivity(intent);
            });
        } else if (path.equals(PATH_SEARCH_HISTORY_REQUEST)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Sending search history to watch");
                WearSyncService.sendSearchHistory(getApplicationContext());
            });
        }
    }
}
