package app.organicmaps.wear;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.util.log.Logger;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.Node;

public class WearSyncService {
    private static final String TAG = WearSyncService.class.getSimpleName();
    private static final String PATH_NAVIGATION = "/navigation/status";
    private static final String PATH_START_NAVIGATION = "/navigation/start";

    public static void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info) {
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
        map.putLong("timestamp", System.currentTimeMillis());

        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        
        Task<DataItem> putDataTask = Wearable.getDataClient(context).putDataItem(putDataReq);
        putDataTask.addOnFailureListener(e -> Logger.e(TAG, "Failed to send navigation data to Wear", e));
        putDataTask.addOnSuccessListener(dataItem -> Logger.d(TAG, "Successfully sent navigation data to Wear: " + dataItem.getUri()));
    }

    public static void startNavigation(@NonNull Context context) {
        Logger.d(TAG, "Sending start navigation signal to Wear");
        
        // Update data state
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("active", true);
        map.putLong("timestamp", System.currentTimeMillis());

        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq)
                .addOnFailureListener(e -> Logger.e(TAG, "Failed to send start signal (data) to Wear", e))
                .addOnSuccessListener(dataItem -> Logger.d(TAG, "Successfully sent start signal (data) to Wear: " + dataItem.getUri()));

        // Also send a direct message for immediate wake-up
        Wearable.getNodeClient(context).getConnectedNodes().addOnSuccessListener(nodes -> {
            for (Node node : nodes) {
                Wearable.getMessageClient(context).sendMessage(node.getId(), PATH_START_NAVIGATION, new byte[0])
                        .addOnSuccessListener(requestId -> Logger.d(TAG, "Sent start message to node: " + node.getDisplayName()))
                        .addOnFailureListener(e -> Logger.e(TAG, "Failed to send start message to node: " + node.getDisplayName(), e));
            }
        });
    }
    
    public static void stopNavigation(@NonNull Context context) {
        Logger.d(TAG, "Sending stop navigation signal to Wear");
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(PATH_NAVIGATION);
        DataMap map = putDataMapReq.getDataMap();
        map.putBoolean("active", false);
        map.putLong("timestamp", System.currentTimeMillis());

        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
        putDataReq.setUrgent();
        Wearable.getDataClient(context).putDataItem(putDataReq);
    }
}
