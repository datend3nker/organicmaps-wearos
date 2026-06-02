package app.organicmaps.wear.message

import android.content.Context
import android.content.Intent
import android.util.Log
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.presentation.Omaps
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class NavStatusHandler : WearMessageHandler {
    override fun handle(buffer: ByteBuffer, data: ByteArray, context: Context) {
        val wearApp = context.applicationContext as WearApplication
        wearApp.onPongReceived()
        
        val active = buffer.get().toInt() == 1
        val currentState = NavigationStateHolder.state.value
        
        val carDir = buffer.get().toInt()
        val pedDir = buffer.get().toInt()
        val exitNum = buffer.get().toInt()
        val progress = buffer.float.toDouble()
        val lat = buffer.double
        val lon = buffer.double
        val turnLat = buffer.double
        val turnLon = buffer.double
        
        val bearing = buffer.float
        val speed = buffer.float.toDouble()
        val speedLimit = buffer.float.toDouble()

        if (!active) {
            val newState = currentState.copy(
                isActive = false,
                isNavigating = false,
                isRouteBuilding = false,
                lat = if (lat != 0.0) lat else currentState.lat,
                lon = if (lon != 0.0) lon else currentState.lon,
                bearing = if (bearing != -1f) bearing else currentState.bearing,
                isPhoneConnected = true
            )
            NavigationStateHolder.update(newState)
            
            // Still update location in native core if we got a valid one
            if (!newState.standaloneMode && lat != 0.0) {
                try {
                    app.organicmaps.sdk.location.LocationState.nativeLocationUpdated(
                        System.currentTimeMillis(),
                        lat, lon, 5.0f, 0.0, speed.toFloat(), bearing
                    )
                    
                    // Automatically request/mount MWM even when not navigating
                    if (!newState.watchLocalMode && newState.isPhoneConnected) {
                        val countryId = app.organicmaps.sdk.downloader.MapManager.nativeFindCountry(lat, lon)
                        if (!countryId.isNullOrEmpty() &&
                            app.organicmaps.sdk.downloader.MapManager.nativeGetStatus(countryId) != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE &&
                            !app.organicmaps.wear.VirtualMwmManager.isMounted(countryId)
                        ) {
                            Log.d("NavStatusHandler", "Requesting metadata for virtual MWM (idle): $countryId")
                            app.organicmaps.wear.WearCommandService.requestMwmMetadata(context, countryId)
                        }
                    }
                } catch (_: Throwable) {}
            }
            return
        }

        val routeLen = if (buffer.remaining() >= 4) buffer.int else 0
        val streetLen = buffer.int
        val distLen = buffer.int
        
        val street = String(data, buffer.position(), streetLen, StandardCharsets.UTF_8)
        buffer.position(buffer.position() + streetLen)
        val dist = String(data, buffer.position(), distLen, StandardCharsets.UTF_8)
        buffer.position(buffer.position() + distLen)

        val routePoints = mutableListOf<Pair<Double, Double>>()
        if (routeLen > 0 && buffer.remaining() >= routeLen * 4 * 2) {
            val lats = FloatArray(routeLen)
            val lons = FloatArray(routeLen)
            for (i in 0 until routeLen) lats[i] = buffer.float
            for (i in 0 until routeLen) lons[i] = buffer.float
            for (i in 0 until routeLen) {
                routePoints.add(Pair(lats[i].toDouble(), lons[i].toDouble()))
            }
        }
        
        val newState = currentState.copy(
            isActive = true,
            isNavigating = true,
            isRouteBuilding = false,
            carDirection = carDir,
            pedestrianDirection = pedDir,
            exitNum = exitNum,
            completionPercent = progress,
            lat = lat,
            lon = lon,
            turnLat = turnLat,
            turnLon = turnLon,
            bearing = bearing,
            speedMps = speed,
            speedLimitMps = speedLimit,
            nextStreet = street,
            distToTurn = dist,
            routePoints = if (routePoints.isNotEmpty()) routePoints else currentState.routePoints,
            isPhoneConnected = true
        )
        NavigationStateHolder.update(newState)

        // Pass location to native core only if NOT in standalone mode
        if (!newState.standaloneMode) {
            try {
                // Removed System.loadLibrary("organicmaps") as it should be in WearApplication
                app.organicmaps.sdk.location.LocationState.nativeLocationUpdated(
                    System.currentTimeMillis(),
                    lat, lon,
                    5.0f, // hAcc
                    0.0, // alt
                    speed.toFloat(),
                    bearing
                )

                // COMPANION MODE: Automatically request/mount MWM for current location
                if (!newState.watchLocalMode && newState.isPhoneConnected) {
                    val countryId = app.organicmaps.sdk.downloader.MapManager.nativeFindCountry(lat, lon)
                    if (!countryId.isNullOrEmpty() &&
                        app.organicmaps.sdk.downloader.MapManager.nativeGetStatus(countryId) != app.organicmaps.sdk.downloader.CountryItem.STATUS_DONE &&
                        !app.organicmaps.wear.VirtualMwmManager.isMounted(countryId)
                    ) {
                        Log.d("NavStatusHandler", "Requesting metadata for virtual MWM: $countryId")
                        app.organicmaps.wear.WearCommandService.requestMwmMetadata(context, countryId)
                    }
                }
            } catch (_: Throwable) {}
        }

        if (!currentState.isActive) {
            val intent = Intent(context, Omaps::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}
