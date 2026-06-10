package app.organicmaps.sdk.sync;

import android.util.Log;
import androidx.annotation.NonNull;

public class WearLog {
    private static final String TAG = "OM_WEAR_SYNC";

    public static void i(@NonNull String msg) {
        Log.i(TAG, msg);
    }

    public static void d(@NonNull String msg) {
        Log.d(TAG, msg);
    }

    public static void w(@NonNull String msg) {
        Log.w(TAG, msg);
    }

    public static void e(@NonNull String msg) {
        Log.e(TAG, msg);
    }

    public static void e(@NonNull String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    public static void v(@NonNull String msg) {
        Log.v(TAG, msg);
    }

    public static void logSent(@NonNull String side, @NonNull String backend, @NonNull String path, int size) {
        i(String.format("[%s] [SENT] [%s] %s (%d bytes)", side.toUpperCase(), backend.toUpperCase(), path, size));
    }

    public static void logReceived(@NonNull String side, @NonNull String backend, @NonNull String path, int size) {
        i(String.format("[%s] [RECEIVED] [%s] %s (%d bytes)", side.toUpperCase(), backend.toUpperCase(), path, size));
    }

    public static void logState(@NonNull String side, @NonNull String msg) {
        i(String.format("[%s] [STATE] %s", side.toUpperCase(), msg));
    }
}
