package app.organicmaps.wear

import android.content.Context
import android.os.StatFs
import app.organicmaps.sdk.settings.StoragePathManager

/**
 * Free-space awareness for the maps storage, used to guide the user between full
 * "Copy to Watch" (when storage is ample) and bounded viewport streaming (when it is tight).
 */
object WatchStorage {
    // Require this much headroom beyond a region's size before recommending a full copy.
    private const val COPY_HEADROOM_NUM = 13L
    private const val COPY_HEADROOM_DEN = 10L

    fun freeBytes(context: Context): Long = try {
        StatFs(StoragePathManager.findMapsStorage(context)).availableBytes
    } catch (e: Exception) {
        0L
    }

    fun totalBytes(context: Context): Long = try {
        StatFs(StoragePathManager.findMapsStorage(context)).totalBytes
    } catch (e: Exception) {
        0L
    }

    /** True if [regionBytes] fits with comfortable headroom → recommend full Copy to Watch. */
    fun fitsComfortably(context: Context, regionBytes: Long): Boolean =
        regionBytes > 0 && freeBytes(context) > regionBytes * COPY_HEADROOM_NUM / COPY_HEADROOM_DEN

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        else String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}
