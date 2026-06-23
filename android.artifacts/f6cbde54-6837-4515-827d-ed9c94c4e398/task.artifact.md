# Task: Fix Show on Phone and Bookmark Sync Inconsistencies

- [x] Expose identity resolution in `BookmarkSyncCore.java`
- [x] Update `IWearSyncBackend` and implementations for `showBookmarkOnPhone`
- [x] Update `WearCommandService.kt` to pass identity for "Show on Phone"
- [x] Implement identity-based `PATH_BOOKMARK_SHOW` in phone's `WearMessageRouter.java`
- [x] Implement `PATH_POI_SHOW` in phone's `WearMessageRouter.java`
- [x] Prevent spurious tombstones in `WatchBookmarkSyncManager.kt` on moves
- [x] Verify builds
