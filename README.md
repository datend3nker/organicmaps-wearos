<div align="center">
  <img src="qt/res/logo.png" height="100"/>
</div>
<h1 align="center">Organic Maps</h1>

**Organic Maps** is a privacy-first offline maps & GPS app for hiking, cycling, biking, and driving. Absolutely free. No ads. No tracking. Developed with love by the open-source community. Powered by [OpenStreetMap](https://www.openstreetmap.org) data.

[<img src="docs/badges/apple-appstore.png" alt="App Store" width="160">](https://apps.apple.com/app/organic-maps/id1567437057)
[<img src="docs/badges/google-play.png" alt="Google Play" width="160">](https://play.google.com/store/apps/details?id=app.organicmaps)
[<img src="docs/badges/huawei-appgallery.png" alt="AppGallery" width="160">](https://appgallery.huawei.com/#/app/C104325611)
[<img src="docs/badges/obtainium.png" alt="Obtainium" width="160">](https://github.com/organicmaps/organicmaps/wiki/Installing-Organic-Maps-from-GitHub-using-Obtainium)
[<img src="docs/badges/fdroid.png" alt="F-Droid" width="160">](https://f-droid.org/en/packages/app.organicmaps/)

## Wear OS Features

Organic Maps brings high-performance, privacy-focused navigation to your wrist.

### Key Capabilities
- **Native Rendering Engine**: 60 FPS fluid map interaction using the core C++ engine.
- **Standalone & Companion Modes**: Use local GPS/maps or stream everything from your phone to save watch battery.
  - **Companion Mode**: Phone handles heavy lifting (route calculation, map data), watch acts as an extended screen.
  - **Standalone Mode**: Watch works 100% independently with its own GPS and downloaded maps.
- **Route & Track Recording**: Start, stop, and monitor route recording directly from your watch. Recording is synced with the phone in real-time with a visual recording indicator (red dot) on the watch face.
- **Location Marker Sync**: Your current position, bearing, and movement are synchronized in real-time between devices, ensuring both the phone and watch show your exact location perfectly in sync.
- **Bidirectional Bookmark Sync**: Access and toggle visibility of your bookmark lists from the watch. Changes are mirrored between phone and watch instantly.
- **Unified Backend Switching**: Seamlessly toggle between Google Play Services (GMS) and raw Bluetooth (OSS) transport layers. Devices automatically coordinate the switch to maintain connectivity.
- **Optimized for Round Screens**: Map UI elements and buttons are intelligently positioned to prevent clipping on circular displays, ensuring all controls remain visible and accessible.
- **Unified Settings**: Configure map layers, routing options, and view data credits directly from the watch settings.
- **Direct Sync**: Real-time mirroring of routes, search results, and settings. Changes on one device are immediately reflected on the other.
- **Map Streaming & Sync**: 
  - **Sync from Phone**: Watch pulls map regions directly from the phone's storage—no internet required on the watch.
  - **Direct Download**: Watch uses its own internet connection (Wi-Fi or LTE) to download map data.
- **Smart Power Management**: Automatically manages GPS and network based on connectivity and navigation state.

### Phone Integration
Organic Maps on the phone acts as a hub for the Wear OS experience:
- **Connection Status**: A dedicated status indicator on the main map screen shows when a watch is connected and the specific connection type (Bluetooth or GSM/Cloud).
- **Sync Notifications**: View map serving progress directly in the phone's notification shade.

### Interface Guide
#### Watch Status Icons (Top Bar)
- **Lock Icon**: Map is locked to your current position. Gestures are disabled to prevent accidental clicks while swiping panels.
- **Open Lock**: "Interactive Mode" enabled (via long-press or hardware button). You can pan and zoom freely. Tap **Recenter** to lock back.
- **Cloud (Green)**: Successfully connected to phone via Google Play Services (GMS).
- **Bluetooth (Green)**: Successfully connected to phone via direct Bluetooth (OSS).
- **Red Icon**: Phone disconnected. App automatically switches to local GPS/Offline mode.
- **SD Card**: "Local Maps" mode active. Using map data stored on the watch.
- **Red Dot**: Track recording is currently active.

#### Interaction
- **Main Pager**: Swipe horizontally between Map, Search, Bookmarks, Map Manager, and Settings.
- **Long Press Map**: Unlocks the map for free roaming and opens the Quick Menu.
- **Recenter Button**: Re-locks the camera to your position and enables panel swiping.
- **Quick Menu**: Toggle layers, zoom, add a bookmark at the map centre, or stop navigation directly from the map.
- **Rotary Crown**: Zoom in and out of the map smoothly.
- **Hardware Button 1**: Open Quick Menu.
- **Hardware Button 2**: Toggle between Locked and Interactive modes.

### Developer Documentation
- **[Wear OS module README](android/app/omaps/README.md)** — features, settings, and connection states.
- **[Architecture](android/app/omaps/docs/ARCHITECTURE.md)** — modules, components, threading, and data flows.
- **[Companion Protocol](android/app/omaps/docs/PROTOCOL.md)** — phone↔watch message framing, catalogue, and sync algorithms.

---

## Why Organic?
- **Privacy First**: No tracking, no ads, no data collection.
- **Battery Efficient**: Intelligent hardware use.
- **Truly Offline**: Maps work without any internet connection.

[**Give Organic Maps a try!**](#install)

## License
Organic Maps is licensed under the [Apache License 2.0](LICENSE).
Binary map data files (`.mwm`) are provided under a separate license. See `DATA_LICENSE.txt`.
