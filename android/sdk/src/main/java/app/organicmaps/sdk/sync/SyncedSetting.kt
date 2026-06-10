package app.organicmaps.sdk.sync

/**
 * Metadata for a setting that is synchronized between devices.
 */
data class SyncedSetting(
    /**
     * The key used in the synchronization protocol.
     */
    val canonicalKey: String,

    /**
     * The local SharedPreferences key used in the Phone app.
     */
    val phoneLocalKey: String,

    /**
     * The local SharedPreferences key used in the Watch app.
     */
    val watchLocalKey: String
)
