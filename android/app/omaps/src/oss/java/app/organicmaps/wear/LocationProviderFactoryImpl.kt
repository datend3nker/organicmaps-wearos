package app.organicmaps.wear

import android.content.Context
import app.organicmaps.sdk.location.BaseLocationProvider
import app.organicmaps.sdk.location.LocationProviderFactory

/**
 * Pure OSS implementation that uses only standard Android APIs.
 */
object LocationProviderFactoryImpl {
    fun create(): LocationProviderFactory = object : LocationProviderFactory {
        override fun isGoogleLocationAvailable(context: Context): Boolean = false
        override fun getProvider(context: Context, listener: BaseLocationProvider.Listener): BaseLocationProvider {
            return app.organicmaps.sdk.location.AndroidNativeProvider(context, listener)
        }
    }
}
