package app.organicmaps.wear

object BackendProvider {
    fun getGmsBackend(): IWearSyncBackend = GmsWearSyncBackend()
}
