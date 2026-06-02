package app.organicmaps.wear;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;

import app.organicmaps.sdk.settings.StoragePathManager;
import app.organicmaps.sync.ISyncLayer;

public class WearMapStreamingHelper {
    private static final String TAG = "WearMapStreaming";

    public static void streamMapToWatch(@NonNull Context context, @NonNull String nodeId, @NonNull String mapId) {
        ISyncLayer syncLayer = WearSyncService.getSyncLayer();

        Log.d(TAG, "DEBUG_WEAR_PIPELINE: streamMapToWatch: " + mapId + " to " + nodeId);
        
        // Find the map file using the centralized StoragePathManager
        String writableDir = StoragePathManager.findMapsStorage(context);
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Map storage path: " + writableDir);
        
        File mapFile = null;
        File rootDir = new File(writableDir);
        
        long dataVersion = 0;
        try {
            dataVersion = app.organicmaps.sdk.Framework.nativeGetDataVersion();
            Log.d(TAG, "DEBUG_WEAR_PIPELINE: Data version: " + dataVersion);
        } catch (Throwable ignored) {}
        
        if (dataVersion > 0) {
            File versionDir = new File(rootDir, String.valueOf(dataVersion));
            if (versionDir.exists() && versionDir.isDirectory()) {
                File candidate = new File(versionDir, mapId + ".mwm");
                if (candidate.exists()) {
                    mapFile = candidate;
                }
            }
        }

        if (mapFile == null) {
            File[] versionDirs = rootDir.listFiles(File::isDirectory);
            if (versionDirs != null) {
                for (File dir : versionDirs) {
                    File candidate = new File(dir, mapId + ".mwm");
                    if (candidate.exists()) {
                        mapFile = candidate;
                        break;
                    }
                }
            }
        }
        
        if (mapFile == null) {
            File directFile = new File(rootDir, mapId + ".mwm");
            if (directFile.exists()) mapFile = directFile;
        }

        if (mapFile == null || !mapFile.exists()) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: Map file NOT FOUND: " + mapId);
            syncLayer.sendMapNotFound(context, mapId);
            return;
        }

        // Send metadata first to allow watch to "mount" the virtual MWM (ghost file)
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Sending MWM metadata (mount) for " + mapId + " size: " + mapFile.length());
        syncLayer.sendMwmMetadata(context, mapId, mapFile.length());

        syncLayer.streamMapFile(context, nodeId, mapId, mapFile);
    }
}
