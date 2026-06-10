package app.organicmaps.wear;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

import app.organicmaps.sdk.settings.StoragePathManager;
import app.organicmaps.sync.ISyncLayer;

public class WearMapStreamingHelper {
    private static final String TAG = "WearMapStreaming";

    public static void streamMapToWatch(@NonNull Context context, @NonNull String nodeId, @NonNull String mapId, long offset) {
        ISyncLayer syncLayer = WearSyncService.getSyncLayer();

        Log.d(TAG, "DEBUG_WEAR_PIPELINE: streamMapToWatch: " + mapId + " to " + nodeId + " from offset: " + offset);
        
        File mapFile = findMapFile(context, mapId);

        if (mapFile == null || !mapFile.exists()) {
            Log.e(TAG, "DEBUG_WEAR_PIPELINE: Map file NOT FOUND: " + mapId);
            syncLayer.sendMapNotFound(context, mapId);
            return;
        }

        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Successfully found map file for streaming: " + mapFile.getAbsolutePath());

        // Send metadata first to allow watch to "mount" the virtual MWM (ghost file).
        // Include header and footer for immediate registration success.
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Sending MWM metadata (mount) for " + mapId + " size: " + mapFile.length());
        
        int footerSize = (int) Math.min(mapFile.length(), 64 * 1024);
        long footerOffset = mapFile.length() - footerSize;
        byte[] footerData = app.organicmaps.sdk.Framework.nativeGetMwmBytes(mapId, footerOffset, footerSize);
        
        int headerSize = (int) Math.min(mapFile.length(), 16 * 1024);
        byte[] headerData = app.organicmaps.sdk.Framework.nativeGetMwmBytes(mapId, 0, headerSize);
        
        syncLayer.sendMwmMetadata(context, mapId, mapFile.length(), headerData, footerData);

        syncLayer.streamMapFile(context, nodeId, mapId, mapFile, offset);
    }

    @Nullable
    public static File findMapFile(@NonNull Context context, @NonNull String mapId) {
        // Find the map file using the centralized StoragePathManager
        String writableDir = StoragePathManager.findMapsStorage(context);
        Log.d(TAG, "DEBUG_WEAR_PIPELINE: Map storage path: " + writableDir);
        
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
                    return candidate;
                }
            }
        }

        File[] versionDirs = rootDir.listFiles(File::isDirectory);
        if (versionDirs != null) {
            for (File dir : versionDirs) {
                File candidate = new File(dir, mapId + ".mwm");
                if (candidate.exists()) {
                    return candidate;
                }
            }
        }
        
        File directFile = new File(rootDir, mapId + ".mwm");
        if (directFile.exists()) return directFile;

        return null;
    }
}
