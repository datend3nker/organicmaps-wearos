## Plan: Organic Maps Wear OS Companion App

This plan details the implementation of a full-featured Wear OS companion app for Organic Maps, focusing on search decoupling, vector streaming, open-source sync (for F-Droid), and standalone map rendering, while minimizing impact on the main device app.

**Steps**

**Phase 1: Decoupling Search (Headless Approach)**
1. Extract the native search call from `SearchActivity`/`SearchFragment` into a reusable `SearchInteractor` or headless service.
2. Modify `WearMessageListenerService` to intercept the watch's `/search/query` and call the headless `SearchInteractor` instead of launching `SearchActivity`.
3. Return search results to the watch via the existing `WearSyncService` without polluting the phone's UI state.

**Phase 2: Vector Map Streaming & Rendering**
1. *Phone side (Data Extraction):* Implement a background service that accepts a geographic bounding box from the watch. To ease development and reviewing, all new Java logic for this will be isolated in the `app.organicmaps.wear.map` package so it's clear it relates only to the companion app. 
   - **Java Entry Point**: Build `MapFeaturesExtractor.java` in the `app.organicmaps.wear.map` package.
   - **C++ Interface Hook**: Augment `Framework.cpp` and `Framework.java` with a custom JNI method (e.g., `nativeGetWearMapFeatures(bbox, scale)`). 
   - **C++ Data Extraction**: Query `indexer::FeaturesFetcher` using the bounding box, loop over the features (`ForEachInRect`), extract simplified geometry (Points, Line segments for roads/buildings/text), and serialize it into a lightweight binary format (e.g., byte array).
   - **Android Service Binding**: Update `WearMessageListenerService.java` to listen for a watch "tile request", hit `nativeGetWearMapFeatures`, and stream the data over `WearSyncService`.
2. *Watch side:* Implement a lightweight vector renderer. Since creating an entirely new engine for `.mwm` formatting is difficult, the watch will use an Android `Canvas` or lightweight OpenGL ES view to render the simplified vector stream coming from the phone.
3. Add a tile-caching layer on the watch to preserve battery (only request new bounding boxes when panning outside the cached area).

**Phase 3: Open-Source Sync (MicroG & Pure Bluetooth)**
1. *Abstraction:* Create a generic `ISyncLayer` interface for device-to-device communication (messages and data maps).
2. Refactor existing `WearSyncService` and `WearMessageListenerService` to implement this interface.
3. *Backend Selection Settings:* Add settings to both the phone and watch apps allowing the user to select their preferred sync backend (e.g., GMS/MicroG vs. Pure Bluetooth). Include informative descriptions for each option explaining the requirements and benefits.
4. *F-Droid implementation:* The `fdroid` flavor is allowed to utilize MicroG (GMS-compatible layer).
5. *OSS implementation:* The `oss` flavor operates purely via standard Android `BluetoothSocket` (RFCOMM/BLE) to pass the same Protobuf payloads without relying on any Google Play Services or MicroG.
6. Leverage the existing product flavors (`google`, `fdroid`, `oss`, etc.) to provide the correct default sync method while allowing user configuration.

**Phase 4: Standalone Watch Mode**
1. Modify the build system (`CMakeLists.txt` / `build.gradle`) to compile a minimal, headless version of the Organic Maps C++ core (`drape` UI excluded, only `mwm` data access and routing) natively for the Wear OS module.
2. Build an offline downloader UI for the watch to download small `.mwm` region files over Wi-Fi when the phone is absent.
3. Fallback logic: When the `ISyncLayer` detects a disconnected phone, switch the watch data source to the locally stored MWM files and run the query/rendering loop locally on the watch CPU.
4. **Enhanced Map Management**: The phone-side Downloader UI fully supports pushing individual `.mwm` map tiles to the watch via the "Send to Watch" option. The phone's configuration settings (e.g., `Standalone Offline Maps`) instantly sync to the watch, seamlessly toggling the watch between live vector streaming and autonomous local map rendering. Explict push commands bypass auto-download blocks and force the watch to sync the chosen regions.

**Relevant files**
- `android/app/src/main/java/app/organicmaps/wear/WearMessageListenerService.java` — Needs refactoring to stop launching UI for `SearchActivity`.
- `android/app/src/main/java/app/organicmaps/wear/WearSyncService.java` — Will become an implementation of the new `ISyncLayer` abstraction.
- Create `android/app/src/main/java/app/organicmaps/sync/` — New package for the generic sync layer, handling the logic separation for F-Droid socket alternatives.
- Create `android/wear/` — A new gradle module holding the watch app UI and lightweight rendering code.

**Verification**
1. Send a test text search from the watch and monitor the phone screen; confirm the phone's Organic Maps UI does not launch or change.
2. Monitor battery and CPU usage using Android Studio Profiler during continuous map panning on the watch (to ensure map streaming is energy efficient).
3. Build the `fdroid` variant and test map syncing using direct Bluetooth without Google Play Services installed.
4. Disable Bluetooth on the watch and verify it can serve pre-downloaded map data natively.

**Decisions**
- The search pipeline will be cleanly abstracted as a headless module rather than hacking around the UI lifecycle.
- To maintain energy efficiency, the phone will initially do the heavy lifting of parsing `.mwm` data, sending simplified vectors to the watch, removing the need for the watch to unpack heavy map structures.
- Inherit the already existing product flavors (`google`, `fdroid`, `web`, `huawei`) rather than inventing completely new variants to isolate the sync logic.
- Dont use git commands. Commits and pushed are only allowed to be used be the developper. Git is abolutely not allowed to be used by agents!

**Further Considerations**
1. Map Engine: Since the user requested a lightweight visual representation, rendering simplified vectors via an Android `Canvas` stream or a basic OpenGL surface on the watch is vastly more energy-efficient than compiling the full OM `Drape` OpenGL engine for the watch. Is this minimal visual representation acceptable?
2. Bluetooth Sync: While an existing library like FreeWear could work, standard standard Bluetooth Sockets might be significantly simpler to maintain given the straightforward message format (Map Tiles + Text Search).
