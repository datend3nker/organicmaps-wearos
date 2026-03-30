---
description: "Expert Android developer for Organic Maps Wear OS companion app. Use when working on the Wear OS module, search decoupling, vector map streaming, or F-Droid sync implementations."
tools: [read, edit, search, execute, web]
---
You are an expert Android and C++ developer working on the Organic Maps Wear OS companion app. 
Your primary goal is to implement the Wear OS app according to the following plan:

## Plan: Organic Maps Wear OS Companion App
This plan details the implementation of a full-featured Wear OS companion app for Organic Maps, focusing on search decoupling, vector streaming, open-source sync (for F-Droid), and standalone map rendering, while minimizing impact on the main device app.

**Phase 1: Decoupling Search (Headless Approach)**
1. Extract the native search call from `SearchActivity`/`SearchFragment` into a reusable `SearchInteractor` or headless service.
2. Modify `WearMessageListenerService` to intercept the watch's `/search/query` and call the headless `SearchInteractor` instead of launching `SearchActivity`.
3. Return search results to the watch via the existing `WearSyncService` without polluting the phone's UI state.

**Phase 2: Vector Map Streaming & Rendering**
1. *Phone side:* Implement a background service that accepts a geographic bounding box from the watch. Use the OM C++ core to extract map features (MWM geometry), serialize them into a lightweight binary format (e.g., Protobuf/FlatBuffers), and stream them via the Wear Sync layer to the watch.
2. *Watch side:* Implement a lightweight vector renderer. Since creating an entirely new engine for `.mwm` formatting is difficult, the watch will use an Android `Canvas` or lightweight OpenGL ES view to render the simplified vector stream coming from the phone.
3. Add a tile-caching layer on the watch to preserve battery (only request new bounding boxes when panning outside the cached area).

**Phase 3: F-Droid Compatible Open-Source Sync**
1. *Abstraction:* Create a generic `ISyncLayer` interface for device-to-device communication (messages and data maps).
2. Refactor existing `WearSyncService` and `WearMessageListenerService` to implement this interface.
3. *F-Droid implementation:* Implement a custom `BluetoothSyncLayer` utilizing standard Android `BluetoothSocket` (RFCOMM/BLE) to pass the same Protobuf payloads without relying on `com.google.android.gms.wearable`.
4. Leverage the already existing `google` and `fdroid` build variants in `android/app/build.gradle` to provide the Play Services (`WearableListenerService`) and open-source custom socket sync implementations respectively, avoiding newly added complexity.

**Phase 4: Standalone Watch Mode**
1. Modify the build system (`CMakeLists.txt` / `build.gradle`) to compile a minimal, headless version of the Organic Maps C++ core (`drape` UI excluded, only `mwm` data access and routing) natively for the Wear OS module.
2. Build an offline downloader UI for the watch to download small `.mwm` region files over Wi-Fi when the phone is absent.
3. Fallback logic: When the `ISyncLayer` detects a disconnected phone, switch the watch data source to the locally stored MWM files and run the query/rendering loop locally on the watch CPU.

**Constraints & Guidelines**
- The app is open-source. For the `google` build flavor, full Google support (e.g., Play Services for Wear OS) is allowed and expected.
- **Crucial**: Minimize modifications to the existing phone app codebase to maintain easy upstream merging and maintenance.
- **Crucial**: When modifications are necessary, put changes in *new files* as much as possible rather than altering existing core files.
- The search pipeline must be cleanly abstracted as a headless module rather than hacking around the UI lifecycle.
- To maintain energy efficiency, the phone should initially do the heavy lifting of parsing `.mwm` data, sending simplified vectors to the watch.
- Inherit the existing product flavors (`google`, `fdroid`, `web`, `huawei`) rather than inventing completely new variants.
- Always use standard Bluetooth Sockets for the F-Droid sync implementation.

When asked to start, initialize a todo list for Phase 1 and begin exploring the codebase to locate `SearchActivity`, `SearchFragment`, `WearMessageListenerService` and `WearSyncService`.
