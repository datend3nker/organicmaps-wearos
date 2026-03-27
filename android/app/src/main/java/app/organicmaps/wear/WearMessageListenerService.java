package app.organicmaps.wear;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.RoutingController;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class WearMessageListenerService extends WearableListenerService {
    private static final String TAG = "WearMessageListener";
    private static final String PATH_STOP_NAVIGATION = "/navigation/stop";

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        Log.d(TAG, "onMessageReceived: " + messageEvent.getPath());
        if (messageEvent.getPath().equals(PATH_STOP_NAVIGATION)) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Stopping navigation per watch request");
                RoutingController.get().cancel();
            });
        }
    }
}
