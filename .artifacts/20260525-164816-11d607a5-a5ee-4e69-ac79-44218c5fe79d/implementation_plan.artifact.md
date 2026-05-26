# Wear OS Fixes: POI Loading & Connection Heartbeat

This plan addresses the "red cloud" (false-positive disconnection) and the issue where POIs disappear when zooming in.

## User Review Required

- **Dynamic Tiling**: The map will now dynamically choose the tile size based on your zoom level. This ensures that when you zoom in, the map loads smaller, more detailed tiles for that specific area, instead of running out of data.
- **Heartbeat Reliability**: Disconnection detection is now much more robust, preventing the "red cloud" icon from appearing when the app is actually connected.

## Proposed Changes

### Wear App Core (`:app:omaps`)

#### [NavigationStateHolder.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/NavigationStateHolder.kt)

- Add logic to track the last successful message from the phone app.

#### [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)

- **Dynamic Tile Zoom**: Replace the hardcoded tile zoom (16) with a dynamic value calculated from `clampedViewSpan`.
- **Cache Invalidation**: Automatically clear the tile cache when the tile zoom level changes to ensure LOD consistency.
- **Spiral Loading**: Update the pre-fetching logic to use the dynamic zoom level.

#### [WearApplication.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/WearApplication.kt)

- Update `onPongReceived` to be called on **any** successful communication from the phone app.
- Refine the ping loop to be less sensitive to minor network jitters.

#### [WearDataListenerService.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/gms/java/app/organicmaps/wear/WearDataListenerService.kt) & [BluetoothWearDataListenerService.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/BluetoothWearDataListenerService.kt)

- Ensure `onPongReceived` is triggered for every received message to keep the connection state "Alive".

---

## Verification Plan

### Manual Verification
1. **POI FOV Test**: Zoom in to maximum detail. Verify that POIs (like benches/shops) remain visible and cover the entire screen, rather than shrinking into a small area.
2. **Connection Heartbeat**: Keep the app running for several minutes while connected. Verify the "red cloud" icon does not appear intermittently.
3. **Disconnection Test**: Turn off the phone's Bluetooth. Verify the red cloud appearing within 15-20 seconds.
