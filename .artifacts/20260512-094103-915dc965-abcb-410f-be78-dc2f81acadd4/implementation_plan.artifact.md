# Implementation Plan - Wear OS App Fixes and Improvements

This plan addresses five reported issues in the Organic Maps Wear OS application, ranging from stability and UI improvements to settings sync and standalone routing fixes.

## Proposed Changes

### 1. Stability and Flickering Fixes

#### [Omaps.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/Omaps.kt)
- **Fix Pager Flickering**: Remove the `isNavigating` and `isMapEnabled` keys from the `remember` block for `PagerState`. The `pageCount` lambda already handles dynamic updates, so re-creating the whole state is unnecessary and causes the UI to reset/flicker.
- **Stabilize Navigation State**: Ensure `isNavigating` is correctly reset when navigation stops.

#### [BluetoothWearDataListenerService.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/BluetoothWearDataListenerService.kt)
- **Optimize State Updates**: Move `lastMessageTimestamp` out of `NavigationState` to avoid triggering recompositions on every single message (e.g., every GPS update).
- **Fix Navigation Stop State**: Explicitly set `isNavigating = false` when `active = 0` is received.

---

### 2. Turn Overlays and Graphics

#### [NavigationIcons.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/NavigationIcons.kt)
- **Switch to SDK Drawables**: Change `getTurnIcon` to return a drawable resource ID (`@DrawableRes Int`) instead of `ImageVector`. Use the rich set of icons from the SDK (`app.organicmaps.sdk.R.drawable.ic_turn_...` and `ic_roundabout_exit_...`).

#### [NavigationScreen.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/navigation/NavigationScreen.kt)
- **Update Icon Rendering**: Update to use `painterResource` for the turn icon instead of `ImageVector`.

#### [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)
- **Improve Route Turn Marker**: Update `drawRouteTurnMarker` to use the same rich icons or better path graphics to highlight the turn on the map.

---

### 3. Settings Sync

#### [WearMessageListenerService.java](file:///home/lettner/git/organicmaps-wearos/android/app/src/gms/java/app/organicmaps/wear/WearMessageListenerService.java)
- **Implement Settings Sync Handler**: Add a handler for the `/preferences/watch` path in `onMessageReceived`. This is currently missing, preventing settings changed on the watch from being applied to the phone when using the Bluetooth/OSS backend.
- **Payload Parsing**: Parse the binary settings payload received from the watch and apply it to `SharedPreferences`.

---

### 4. Route Calculation UI

#### [NavigationStateHolder.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/NavigationStateHolder.kt)
- **Add Destination Info**: Add `destinationName` and `routerType` fields to `NavigationState` to display more info during calculation.

#### [Omaps.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/Omaps.kt)
- **Improve Calculation UI**: Replace the simple button overlay with a more informative card showing the destination, router type, and a progress indicator.

---

### 5. Standalone Routing Fixes

#### [WearApplication.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/WearApplication.kt)
- **Improve Native Initialization**: Ensure all required assets for routing (classificator, types, etc.) are correctly accessible to the native SDK in standalone mode.
- **Enhance Logging and Error Handling**: Add detailed logging for route building failures on the watch to help diagnose "not compiled" issues.

## Verification Plan

### Automated Tests
- I will run the existing unit tests in the `:sdk` and `:app` modules if applicable.
- Since most changes are in the Wear UI and sync logic, manual verification on a device/emulator is preferred.

### Manual Verification
- **Flickering**: Deploy to a Wear OS emulator, start navigation from the phone, and observe if the UI is stable during updates.
- **Turn Overlays**: Verify that turn icons (including roundabout exits) match the phone app's style.
- **Settings Sync**: Change a setting on the watch (e.g., "Map UI" toggle) and verify it reflects in the phone's "Wear OS" settings.
- **Route Calculation**: Select a destination on the watch in standalone mode and verify the calculation progress and final route display.
- **Standalone Mode**: Disconnect from phone and verify that route calculation still works using local maps.
