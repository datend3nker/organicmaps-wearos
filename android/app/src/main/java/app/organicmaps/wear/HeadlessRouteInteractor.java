package app.organicmaps.wear;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;

public final class HeadlessRouteInteractor implements RoutingController.RouteEventListener {
    private static final String TAG = "HeadlessRoute";

    private static HeadlessRouteInteractor sInstance;

    @NonNull
    private final Context mContext;
    private String mDestinationName;

    private HeadlessRouteInteractor(@NonNull Context context) {
        mContext = context.getApplicationContext();
        // Register as a non-exclusive observer rather than the single UI Container. Attaching as the
        // Container would steal route callbacks from the foreground map UI (MwmActivity) and, because
        // this is a once-built singleton, would also lose the slot forever once MwmActivity detaches —
        // breaking navigation on BOTH the phone and the watch.
        RoutingController.get().addRouteEventListener(this);
    }

    @NonNull
    public static synchronized HeadlessRouteInteractor getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new HeadlessRouteInteractor(context);
        }
        return sInstance;
    }

    @Override
    public void onBuiltRoute() {
        Log.d(TAG, "Route built successfully");
        WearSyncService.sendRouteBuildProgress(mContext, 100);
        WearSyncService.startNavigation(mContext);
        WearCompanionNotificationManager.hideNotification(mContext, WearCompanionNotificationManager.NOTIFICATION_ID_ROUTE);
    }

    @Override
    public void onCommonBuildError(int lastRouteError, @NonNull String[] lastMissingMaps) {
        Log.e(TAG, "Route build error: " + lastRouteError);
        WearSyncService.sendRouteBuildProgress(mContext, -1); // Indicate error
        WearCompanionNotificationManager.hideNotification(mContext, WearCompanionNotificationManager.NOTIFICATION_ID_ROUTE);
    }

    @Override
    public void onRouteCancelled() {
        Log.d(TAG, "Route cancelled on phone — notifying watch");
        WearSyncService.stopNavigation(mContext);
        WearCompanionNotificationManager.hideNotification(mContext, WearCompanionNotificationManager.NOTIFICATION_ID_ROUTE);
    }

    @Override
    public void onStartRouteBuilding() {
        Log.d(TAG, "Route building started");
        WearSyncService.sendRouteBuildProgress(mContext, 0);
        if (mDestinationName != null) {
            WearCompanionNotificationManager.showRouteCalculationNotification(mContext, mDestinationName);
        }
    }

    @Override
    public void updateBuildProgress(int progress, @NonNull app.organicmaps.sdk.Router router) {
        Log.d(TAG, "Route build progress: " + progress + "%");
        WearSyncService.sendRouteBuildProgress(mContext, progress);
    }

    public void planRoute(double lat, double lon, int routerType, @NonNull String name) {
        mDestinationName = name;
        try {
            if (MwmApplication.from(mContext).getOrganicMaps().arePlatformAndCoreInitialized()) {
                performPlanning(lat, lon, routerType, name);
                return;
            }
            boolean asyncInit = MwmApplication.from(mContext).initOrganicMaps(() ->
                performPlanning(lat, lon, routerType, name));
            if (!asyncInit) {
                performPlanning(lat, lon, routerType, name);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to init Organic Maps for headless route planning", e);
        }
    }

    private void performPlanning(double lat, double lon, int routerType, @NonNull String name) {
        RoutingController controller = RoutingController.get();
        controller.cancel(); // Clear any previous route
        
        MapObject endPoint = MapObject.createMapObject(MapObject.POI, name, "", lat, lon);
        MapObject startPoint = resolveStartPoint();
        if (startPoint == null) {
            Log.w(TAG, "Skipping route planning: no start location available");
            return;
        }

        Router router = resolveRouter(routerType);
        controller.prepare(startPoint, endPoint, router);
        controller.checkAndBuildRoute();

        // Navigation is started from onBuiltRoute() once the async build actually completes; calling
        // it here (right after the async checkAndBuildRoute) is premature and races the build.
        Log.d(TAG, "Headless route planning requested for '" + name + "' via " + router);
    }

    @NonNull
    private Router resolveRouter(int routerType) {
        try {
            return Router.valueOf(routerType);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unknown router type " + routerType + ", falling back to Vehicle");
            return Router.Vehicle;
        }
    }

    @Nullable
    private MapObject resolveStartPoint() {
        MapObject myPosition = MwmApplication.from(mContext).getLocationHelper().getMyPosition();
        if (myPosition != null) {
            return myPosition;
        }

        Location location = MwmApplication.from(mContext).getLocationHelper().getSavedLocation();
        if (location == null) {
            location = getLastKnownLocation();
        }
        if (location == null) {
            return null;
        }

        return MapObject.createMapObject(MapObject.MY_POSITION, "", "", location.getLatitude(), location.getLongitude());
    }

    @Nullable
    private Location getLastKnownLocation() {
        try {
            LocationManager locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                return null;
            }

            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null) {
                return gps;
            }

            return locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            Log.w(TAG, "No permission for fallback location lookup");
            return null;
        }
    }
}
