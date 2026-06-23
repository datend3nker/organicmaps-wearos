# Organic Maps - Wear OS Companion

This module contains the Wear OS application for Organic Maps. It provides a rich, interactive map and navigation experience directly on your wrist, designed to work seamlessly with the Organic Maps phone application.

## Core Features

### 1. Map Rendering & Interaction
- **Native Rendering Engine**: The map is drawn by the **same Organic Maps C++ engine as the phone**, rendered onto a native `MapView`/`Surface` (not a Jetpack Compose re-implementation). This gives true vector maps at high frame rates with pixel-identical styling, POIs, and labels. Jetpack Compose is used only for the UI overlays on top of the map.
- **Dual Data Modes**: **Streaming** (map data fetched on demand from the phone) and **Local** (data resident on watch storage).
- **Manual Interaction**:
    - **Panning**: Drag on the map to explore the surrounding area.
    - **Zooming**: Use the **rotary crown** for smooth zooming.
    - **Interaction Lock**: Toggle between swiping panels and panning the map, to prevent accidental gestures.
    - **Re-center**: Snap back to your current position with the dedicated "My Location" button.
- **Location Marker Sync**: Real-time position and bearing kept in sync between phone and watch.
- **Track Recording (manage & control)**:
    - **Full control from the wrist**: **Start**, **Save** (stops and keeps the track, with an optional name), or **Discard** (stops and deletes) — from the dedicated **Track** screen (swipe to it like the Bookmarks screen). The phone runs the recording (its GPS + a foreground service); the watch drives it.
    - **Live stats**: A red-dot indicator plus live **distance** and **elapsed time** update on the watch every ~2 s while recording.
    - **Disconnect-resilient**: The recording runs in a sticky foreground service on the phone, so it keeps going if the watch disconnects; on reconnect the phone re-pushes the current recording status and the watch reconciles automatically.
    - **Saved as a track**: A saved recording becomes a track in your bookmark categories (track counts sync in bookmark metadata).
- **Points of Interest**: POIs (food, fuel, ATMs, transit, …) are rendered by the native engine using the official Organic Maps styles; which categories appear is controlled by **Map Details** (see §5).
- **Place Interaction**:
    - **Companion Mode**: Tapping a place opens it instantly on the connected phone.
    - **Standalone Mode**: Tapping a place opens a detailed "Place Page" with info and navigation options.
- **Auto-Zoom**: Map scale adapts to your speed and proximity to the next turn.

### 2. Navigation Experience
- **Real-time Guidance**: Syncs perfectly with the phone's navigation session.
- **Roundabout Support**: Clear turn icons with integrated exit numbers for all roundabout variants.
- **Turn Guidance Overlay**: A large maneuver icon plus distance-to-turn and next-street overlay sits above the native map during navigation.
- **Accurate Icons**: Turn indicators match the phone app's logic (slight turns, highway exits, pedestrian vs. car directions).
- **Guidance Info**: Displays distance to turn, street names, and total ETA.
- **Trip Stats**: A dedicated stats panel (swipe during navigation) shows current **Speed**, remaining **Distance**, and **ETA**, plus elapsed time.
- **Heading-Aware Map**: The device compass (rotation-vector sensor) orients the map to your facing direction.
- **Multiple Profiles**: Build routes for **Car, Pedestrian, Bicycle, or Transit**, selectable per destination.
- **Initiate Anywhere**: Start navigation from a search result, a bookmark, or a tapped place — on the watch or by handing off to the phone.

### 3. Independent Search & Management
- **Search Everywhere**: Find destinations directly on the watch using voice input or the keyboard.
- **Region Search**: Easily find map regions to download via a substring-search bar in the Map Manager.
- **Map Management**: Downloaded regions are automatically sorted to the top for easy removal or updates.
- **Auto-availability**: A region downloaded on the phone becomes streamable on the watch without restarting the watch app — the phone announces its downloaded-maps set on every connect and whenever a download completes, and the watch re-scans immediately.
- **History**: View search history synced from the phone.
- **Instant Start**: Launch navigation immediately from any search result.

### 4. Bookmarks
- **Add From the Wrist**: Drop a bookmark at the current map centre with the **star button** on the map.
- **Per-bookmark sync**: Bookmarks sync as individual, content-addressed records (identity = category + name + position), reconciled last-writer-wins — not whole-category files. Both devices derive the same id for the same pin, so repeated syncs never duplicate and the old category-name "My Places → My Places1 → 11 → …" explosion is structurally impossible.
- **Edits, moves & renames converge**: Renaming a bookmark, recolouring it, editing its note, or moving it between categories propagates and converges on both devices without leaving duplicates or losing the pin.
- **Deletions stick**: Deleting a bookmark on one device propagates via tombstones instead of being resurrected on the next sync; a later re-create of the same pin correctly overrides an old tombstone.
- **Show on Phone / Show on Watch**: From a bookmark on the watch, recentre the **phone** map on it (brings the phone map to the front) or recentre the **watch** map.
- **Visibility & Management**: Create categories, toggle visibility, rename, move, and delete from the watch.

### 5. Map Customization
- **Map Layers**: Toggle **Subway/Underground**, **Cycling routes**, **Hiking routes**, and **Contour lines** overlays.
- **POI Filtering (Map Details)**: Granular control over which Points of Interest are shown — Eat & Drink, Fuel, ATMs, Hotels, Parking, Transit, Attractions, Health, Shopping, Toilets, Wi-Fi, and more — via a compact POI mask.
- **Map Style**: Choose **Auto**, **Day**, or **Night** rendering; Auto follows the system/ambient state.
- **3D Perspective & Buildings**: Toggle 3D tilt and extruded 3D buildings.
- **Routing Options**: Avoid **Tolls**, **Motorways**, **Ferries**, and **Unpaved** roads.
- **Measurement Units**: Switch between metric and imperial.

### 6. Wear OS System Integration
- **Tile**: An Organic Maps tile for one-tap access to "Where to?" search and your last destination.
- **Watch-Face Complication**: A navigation complication surfaces the live distance-to-turn on supported watch faces (short & long text).
- **Ambient (Always-On) Support**: The UI adapts to the low-power ambient state (the live map pauses to save battery).
- **Hardware Controls**: The rotary crown zooms; side button 2 recenters / cycles the map-follow mode.
- **Smart Power Management**: GPS and networking are managed automatically based on connectivity and navigation state to preserve battery.

---

## Settings & Synchronization Logic

Settings can be managed on both the phone and watch. They are designed to be intuitive, with "Standalone" and "Local" modes taking priority to ensure reliability.

All settings below — plus every **Map Customization** option in §5 (3D, auto-zoom, map style, units, map layers, POI mask, routing avoidances) — are reconciled with a per-setting **version + timestamp last-writer-wins** scheme. They sync on the very first connection (the phone seeds its defaults at startup and the watch adopts them), and every later change on either device propagates and is applied live on the other.

### Settings Reference

| Setting | UI Name | Primary Use Case | Sync Behavior |
| :--- | :--- | :--- | :--- |
| **Map Enabled** | `Enable Map on Watch` | Toggle to save battery or simplify the watch UI. | Phone → Watch |
| **Watch Local Mode** | `Watch Local Mode` | Use maps installed on the watch for speed and stability. | Bi-directional |
| **Standalone Mode** | `Watch Standalone Mode` | Independent operation (e.g., hiking with only the watch). | Phone → Watch |
| **Backend** | `Communication Backend` | Choice between GMS (Play Services) or raw Bluetooth. Syncs across devices. | Bi-directional (Coordinated) |
| **Download Policy**| `Watch Download Policy` | Decide if watch pulls maps from phone memory or Wi-Fi. | Phone → Watch |
| **Notifications** | `Sync Notifications` | Toggle "Serving Map" progress visibility on the phone. | Watch → Phone |

### Mode Hierarchy & Interactions
- **Phone-Link (Streaming)**: The default behavior. The phone does the heavy lifting of extracting and compressing map data, then sends it to the watch.
- **Watch Local Mode**: Overrides streaming. If the watch has the required map files, it extracts data locally. This is significantly more responsive and stable than streaming.
- **Watch Standalone Mode**: The "highest" priority mode. It forces **Map Enabled** and **Local Mode** to TRUE, and instructs the watch to ignore the phone's GPS, using its own internal GPS sensor instead.

---

## Connection States (Visual Indicators)

The app displays status icons in the **top-right corner** of the map (watch) and **top-center** (phone) to communicate connectivity:

- **Green Cloud Icon**: Connected via Google Play Services (GMS).
- **Green Bluetooth Icon**: Connected via direct Bluetooth RFCOMM (OSS).
- **Red Disconnected Icon**: The watch is currently disconnected from the phone.
- **Green SD Card Icon**: **Watch Local Mode** is active. Rendering from local watch storage.
- **Airplane Icon**: **Watch Standalone Mode** is active. Using internal watch GPS.

---

## Technical Architecture

The watch is not a re-implementation of the map — it drives the **same C++ engine** as the phone over a
JNI bridge, and talks to the phone through a transport-agnostic message protocol.

- **Backend Agnostic**: The `IWearSyncBackend` (watch) / `ISyncLayer` (phone) interfaces let the app switch
  between Google Play Services and raw Bluetooth (RFCOMM, or TCP on emulators) sockets at runtime. The
  selected backend is the single source of truth; control-plane messages flow over either transport.
- **One protocol, two framings**: Every message has a string *path* (GMS) and a numeric *type* (Bluetooth);
  `WearProtocol` keeps the bijective registry so both transports converge on the same `WearMessageRouter`.
- **Unidirectional UI state**: The Compose UI renders a single `NavigationStateHolder` `StateFlow`; inbound
  handlers mutate that one state object.
- **Bounded map streaming**: In Companion mode the watch mounts a **sparse** `.mwm` and faults 64 KB blocks
  on demand via the native `VirtualModelReader`; a free-space-derived LRU cache evicts cold blocks
  (`fallocate(PUNCH_HOLE)`) so disk use stays bounded.
- **Per-bookmark LWW sync**: Bookmarks sync as individual content-addressed records via `BookmarkSyncCore`
  (identity = `category|name|latQ|lonQ`), reconciled last-writer-wins per identity, with **tombstones** so
  deletions propagate instead of being resurrected. This replaced the old category-grained KMZ/KML
  union-merge, whose same-name import collisions caused an exponential category-uniquify cascade.
- **Efficient Data Transfer**: Large payloads are Gzip-compressed before transmission to stay within message
  size limits and reduce latency.
- **Native Power**: Complex map feature extraction is handled in C++ using the same core engine as the phone
  app, ensuring data consistency.

### Documentation
- **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** — modules, components on each device, threading model, data flows.
- **[PROTOCOL.md](docs/PROTOCOL.md)** — message framing, the full message catalogue, and the sync algorithms
  (settings LWW, bookmark union-merge + tombstones, map streaming, navigation, backend switching).
