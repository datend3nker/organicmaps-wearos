# Code Analysis Issues Cleanup

This plan addresses a large number of code analysis warnings (unused imports, unused parameters, redundant qualifiers, legacy overloads, etc.) across multiple files in the Organic Maps WearOS project.

## User Review Required

> [!IMPORTANT]
> Some "Condition is always true/false" warnings are due to `BuildConfig.FLAVOR` checks in specific build variants. I will simplify these checks where appropriate, but I will be careful not to break cross-flavor compatibility if the code is intended to be shared.

> [!NOTE]
> I will replace legacy `Long` overloads for `delay` with `Duration` equivalents where applicable, following modern Kotlin coroutine practices.

## Proposed Changes

The changes are grouped by file and follow the warnings provided in the code analysis report.

### [Component] Wear OS App (`:app:omaps` and `:app`)

#### [MODIFY] [BluetoothWearDataListenerService.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/BluetoothWearDataListenerService.kt)
- Remove unused import `android.app.Service` (or whichever is unused).
- Use `_` for unused parameters in `catch` blocks.
- Remove redundant qualifiers for `StoragePathManager`, `this@BluetoothWearDataListenerService`, `Build.PRODUCT`, and `java.net.Socket`.
- Convert `delay` calls to use `Duration`.
- Use `_` for unused Exception variable `e`.

#### [MODIFY] [MapManagerScreen.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/downloads/MapManagerScreen.kt)
- Remove unused Compose imports.
- Remove redundant qualifiers for `DownloadState` and `WearMapDownloader`.

#### [MODIFY] [WearDataListenerService.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/gms/java/app/organicmaps/wear/WearDataListenerService.kt)
- Remove unused imports.
- Convert `delay` to `Duration`.
- Remove unnecessary Elvis operator.
- Remove redundant qualifier.
- Remove unused `launchOmaps` function.
- Suppress or address `FLAG_ACTIVITY_NEW_TASK` warning (it's required for starting activity from service).

#### [MODIFY] [WearApplication.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/WearApplication.kt)
- Remove unused imports.
- Remove redundant qualifiers.
- Simplify/suppress `BuildConfig.FLAVOR` checks.
- Use `_` for unused parameter `e`.

#### [MODIFY] [WearMapDownloader.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/gms/java/app/organicmaps/wear/WearMapDownloader.kt)
- Remove unused imports.
- Remove redundant qualifiers.
- Convert `delay` to `Duration`.
- Use `_` for unused parameter `e`.

#### [MODIFY] [WearSyncService.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/gms/java/app/organicmaps/wear/WearSyncService.java)
- Remove unused imports.
- Use try-with-resources for `FileOutputStream`.
- Remove unused methods/fields (`sPendingMerges`, `isSilentSyncInProgress`, redundant `updateNavigation`, etc.).
- Simplify `BuildConfig.FLAVOR` conditions.

#### [MODIFY] [WearMessageRouter.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/main/java/app/organicmaps/wear/WearMessageRouter.java)
- Remove unused imports.
- Remove unused `onMessageReceived` overload.
- Use expression lambdas.

#### [MODIFY] [SettingsSyncManager.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/SettingsSyncManager.kt)
- Fix potential memory leak warning for static context reference.

#### [MODIFY] [PreferenceHandler.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/message/PreferenceHandler.kt)
- Use `_` for unused parameters `i` and `e`.

#### [MODIFY] [WearCommandService.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/WearCommandService.kt)
- Simplify `BuildConfig.FLAVOR` conditions.
- Remove unnecessary Elvis operator.
- Convert `delay` to `Duration`.
- Remove unused parameter `context`.
- Remove unused function `checkConnection`.

#### [MODIFY] [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)
- Remove unused imports.
- Use `_` for unused parameters `onSearchClick`, `onSettingsClick`, `e`.
- Convert `delay` to `Duration`.
- Remove redundant qualifiers and unnecessary non-null assertions.

#### [MODIFY] [SearchScreen.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/search/SearchScreen.kt)
- Remove unused imports.
- Use `_` for unused parameters `mainViewModel`, `e`.
- Address resource-by-name warnings by using `R` references if possible.

#### [MODIFY] [PlatformHelperImpl.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/gms/java/app/organicmaps/wear/PlatformHelperImpl.kt)
- Simplify constant conditions.
- Remove unnecessary safe call.

#### [MODIFY] [WatchBookmarkSyncManager.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/WatchBookmarkSyncManager.kt)
- Remove unused functions/parameters.
- Use KTX `SharedPreferences.edit`.

#### [MODIFY] [WearMessageRouter.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/WearMessageRouter.kt)
- Use `_` for unused parameters.
- Use KTX `SharedPreferences.edit`.
- Simplify `BuildConfig.FLAVOR` check.
- Suppress or address `FLAG_ACTIVITY_NEW_TASK` warning.

#### [MODIFY] [GmsWearSyncBackend.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/gms/java/app/organicmaps/wear/GmsWearSyncBackend.kt)
- Remove redundant qualifiers.
- Convert `delay` to `Duration`.

#### [MODIFY] [VirtualMwmManager.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/VirtualMwmManager.kt)
- Remove unused functions/parameters.

#### [MODIFY] [NavStatusHandler.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/message/NavStatusHandler.kt)
- Suppress or address `FLAG_ACTIVITY_NEW_TASK` warning.

#### [MODIFY] [BluetoothWearSyncBackend.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/BluetoothWearSyncBackend.kt)
- Use `_` for unused parameter `e`.

### [Component] SDK (`:sdk`)

#### [MODIFY] [BookmarkSyncCore.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/sdk/src/main/java/app/organicmaps/sdk/sync/BookmarkSyncCore.java)
- Remove unused import.
- Remove unused method `stampLocalChange` (if confirmed unused).
- Remove unused `live` collection.

#### [MODIFY] [Utils.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/main/java/app/organicmaps/util/Utils.java)
- Remove unused imports.
- Remove unused methods/parameters.
- Remove redundant suppression.
- Simplify constant conditions.

#### [MODIFY] [RoutingController.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/sdk/src/main/java/app/organicmaps/sdk/routing/RoutingController.java)
- Remove unused methods.
- Handle potential null argument.

#### [MODIFY] [BluetoothSyncLayer.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/main/java/app/organicmaps/sync/BluetoothSyncLayer.java)
- Handle ignored skip result.
- Address busy-waiting in `Thread.sleep` (add yield or explanation).
- Simplify conditions.

#### [MODIFY] [BaseSettingsSyncManager.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/sdk/src/main/java/app/organicmaps/sdk/sync/BaseSettingsSyncManager.kt)
- Use `apply()` instead of `commit()`.

#### [MODIFY] [BookmarkManager.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/sdk/src/main/java/app/organicmaps/sdk/bookmarks/data/BookmarkManager.java)
- Handle ignored `File.delete()` result.
- Remove unused methods.
- Simplify conditions.
- Address potential NullPointerExceptions.
- Remove unnecessary semicolon.

#### [MODIFY] [WearProtocol.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/sdk/src/main/java/app/organicmaps/sdk/sync/WearProtocol.java)
- Use enhanced switch statement.

#### [MODIFY] [EditBookmarkFragment.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/main/java/app/organicmaps/widget/placepage/EditBookmarkFragment.java)
- Address potential NullPointerExceptions for `getClassLoader`.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:omaps:lintDebug` to check if warnings are gone.
- Run `./gradlew :app:lintDebug` to check if warnings are gone.
- Run `./gradlew :sdk:lintDebug` to check if warnings are gone.
- Run unit tests to ensure no regressions: `./gradlew test`.

### Manual Verification
- Deploy to a Wear OS device (or emulator) to ensure connectivity and synchronization still work.
- Verify bookmark sync and map management functionality.
