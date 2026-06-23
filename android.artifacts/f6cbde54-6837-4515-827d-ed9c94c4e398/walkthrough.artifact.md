# Walkthrough - Fix Show on Phone and Bookmark Sync

I have fixed the "Show on Phone" feature and addressed inconsistencies when moving bookmarks between folders.

## Changes Made

### 1. Show on Phone Fix
- **Problem**: The watch was sending local bookmark IDs which the phone didn't recognize.
- **Solution**:
    - Updated `IWearSyncBackend` and its implementations to send the full bookmark identity (name, category, and coordinates) instead of raw IDs.
    - Updated the phone's `WearMessageRouter.java` to resolve these incoming identities to local IDs using the new `BookmarkSyncCore.findBookmarkId` method.
    - Implemented `PATH_POI_SHOW` on the phone to handle showing search results on the map.
    - Added a safety check to `MwmActivity.java` to prevent crashes if a bookmark ID is passed before the manager is ready.

### 2. Bookmark Move/Sync Fix
- **Problem**: Moving a bookmark generated a "tombstone" for the old location, which sometimes caused the bookmark to be deleted from its new location if applied incorrectly.
- **Solution**:
    - **`BookmarkTombstoneStore.java`**: Added a safety check to `applyToCategory`. It now verifies that a bookmark is actually in the category being processed before deleting it. If a bookmark was already moved locally, the tombstone for its old location will no longer delete it from its new location.
    - **`WatchBookmarkSyncManager.kt` & `WearSyncService.java`**: Improved logging for bookmark deletions to help track move operations.

## Verification Results

### Automated Tests
- Executed `:app:assembleFdroidDebug` and `:app:omaps:assembleDebug` - **Build Successful**.

### Manual Verification Required
> [!IMPORTANT]
> Please re-deploy **both** the watch and phone apps.
> 1. Verify "Show on Phone" for bookmarks and search results.
> 2. Verify moving a bookmark to a new folder on the watch. It should move on both devices without disappearing.
