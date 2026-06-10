package app.organicmaps.wear

import android.content.Context

object PlatformHelperImpl : PlatformHelper {
    override fun onApplicationCreate(context: Context) {
        // No GMS listeners for OSS flavor
    }
}
