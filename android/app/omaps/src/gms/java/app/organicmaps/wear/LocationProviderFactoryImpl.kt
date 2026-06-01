package app.organicmaps.wear

import android.content.Context
import app.organicmaps.sdk.location.BaseLocationProvider
import app.organicmaps.sdk.location.LocationProviderFactory

/**
 * GMS/F-Droid implementation that supports Google Play Services location.
 */
object LocationProviderFactoryImpl {
    fun create(): LocationProviderFactory = object : LocationProviderFactory {
        override fun isGoogleLocationAvailable(context: Context): Boolean {
            return try {
                val result = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                result == com.google.android.gms.common.ConnectionResult.SUCCESS
            } catch (_: Exception) {
                false
            }
        }
        override fun getProvider(context: Context, listener: BaseLocationProvider.Listener): BaseLocationProvider {
            if (isGoogleLocationAvailable(context)) {
                try {
                    val cls = Class.forName("app.organicmaps.location.GoogleFusedLocationProvider")
                    val ctor = cls.getDeclaredConstructor(Context::class.java, BaseLocationProvider.Listener::class.java)
                    ctor.isAccessible = true
                    return ctor.newInstance(context, listener) as BaseLocationProvider
                } catch (_: Exception) {}
            }
            return app.organicmaps.sdk.location.AndroidNativeProvider(context, listener)
        }
    }
}
