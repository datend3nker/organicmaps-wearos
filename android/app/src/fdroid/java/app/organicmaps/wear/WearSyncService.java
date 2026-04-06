package app.organicmaps.wear;

import android.content.Context;
import android.location.Location;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchResult;

public class WearSyncService {
    public static void sendMapRequestToWatch(@NonNull android.content.Context context, @NonNull String countryId) {}

    private static final String TAG = "WearSyncService";

    public static void syncPreferences(@NonNull Context context) { }
    public static void updateNavigation(@NonNull Context context, @NonNull RoutingInfo info, @Nullable Location location) { }
    public static void startNavigation(@NonNull Context context) { }
    public static void stopNavigation(@NonNull Context context) { }
    public static void sendSearchResults(@NonNull Context context, SearchResult[] results, boolean isSearching) { }
    public static void sendSearchState(@NonNull Context context, boolean isSearching) { }
    public static void sendSearchHistory(@NonNull Context context) { }
}
