# Organic Maps - Wear OS Companion

This module contains the Wear OS application for Organic Maps. It provides a rich, interactive map and navigation experience directly on your wrist, designed to work seamlessly with the Organic Maps phone application.

## Core Features

### 1. Advanced Map Rendering & Interaction
- **Vector Graphics**: High-performance vector rendering using Jetpack Compose Canvas.
- **Dual Rendering Modes**: Supports **Streaming** (data from phone) and **Local** (data from watch storage) modes.
- **Manual Interaction**:
    - **Panning**: Drag on the map to explore the surrounding area.
    - **Zooming**: Use the **rotary crown** or physical **volume buttons** for smooth, responsive zooming.
    - **Interaction Lock**: A toggle to switch between swiping for tabs and panning the map, ensuring safe navigation.
    - **Re-center**: Quickly snap back to your current position with a dedicated "My Location" button.
- **Smart Filtering**: In Car/Vehicle mode, the map automatically hides pedestrian-only paths.
- **POI Support**: Renders Points of Interest (Food, Fuel, ATMs, etc.) with high-contrast colored markers and official Organic Maps icons.
- **Improved Labels**: Road names follow the path of the road for better legibility and a cleaner look.
- **Dynamic POI Interaction**: 
    - **Companion Mode**: Tapping a POI opens it instantly on the connected phone.
    - **Standalone Mode**: Tapping a POI opens a detailed "Place Page" with info and navigation options.
- **Auto-Zoom**: Intelligent map scaling that adapts to your current speed and proximity to turns.

### 2. Navigation Experience
- **Real-time Guidance**: Syncs perfectly with the phone's navigation session.
- **Roundabout Support**: Clear turn icons with integrated exit numbers for all roundabout variants.
- **Precise Turn Arrows**: Visual arrows that attach exactly to junction points for unmistakable guidance.
- **Accurate Icons**: Detailed turn indicators that match the phone app's sophisticated logic (slight turns, highway exits, etc.).
- **Guidance Info**: Displays distance to turn, street names, and total ETA.

### 3. Independent Search & Management
- **Search Everywhere**: Find destinations directly on the watch using voice input or the keyboard.
- **Region Search**: Easily find map regions to download via a substring-search bar in the Map Manager.
- **Map Management**: Downloaded regions are automatically sorted to the top for easy removal or updates.
- **History**: View search history synced from the phone.
- **Instant Start**: Launch navigation immediately from any search result.

---

## Settings & Synchronization Logic

Settings can be managed on both the phone and watch. They are designed to be intuitive, with "Standalone" and "Local" modes taking priority to ensure reliability.

### Settings Reference

| Setting | UI Name | Primary Use Case | Sync Behavior |
| :--- | :--- | :--- | :--- |
| **Map Enabled** | `Enable Map on Watch` | Toggle to save battery or simplify the watch UI. | Phone → Watch |
| **Watch Local Mode** | `Watch Local Mode` | Use maps installed on the watch for speed and stability. | Bi-directional |
| **Standalone Mode** | `Watch Standalone Mode` | Independent operation (e.g., hiking with only the watch). | Phone → Watch |
| **Backend** | `Communication Backend` | Choice between GMS (Play Services) or raw Bluetooth. | Phone → Watch |
| **Download Policy**| `Watch Download Policy` | Decide if watch maps download via Phone-Link or Wi-Fi. | Phone → Watch |

### Mode Hierarchy & Interactions
- **Phone-Link (Streaming)**: The default behavior. The phone does the heavy lifting of extracting and compressing map data, then sends it to the watch.
- **Watch Local Mode**: Overrides streaming. If the watch has the required map files, it extracts data locally. This is significantly more responsive and stable than streaming.
- **Watch Standalone Mode**: The "highest" priority mode. It forces **Map Enabled** and **Local Mode** to TRUE, and instructs the watch to ignore the phone's GPS, using its own internal GPS sensor instead.

---

## Connection States (Visual Indicators)

The watch displays small, non-intrusive icons in the **top-right corner** of the map to communicate its current state:

- **Red Bluetooth Icon**: The watch is currently disconnected from the phone. If not in Standalone mode, map streaming and search will be unavailable.
- **Green SD Card Icon**: **Watch Local Mode** is active. The map is being rendered from local watch storage.
- **Airplane Icon**: **Watch Standalone Mode** is active. The watch is operating independently of the phone.

---

## Technical Architecture

- **Backend Agnostic**: The `IWearSyncBackend` interface allows the app to switch between Google Play Services and raw Bluetooth sockets at runtime.
- **Efficient Data Transfer**: Map tiles are Gzip-compressed before transmission to stay within message size limits and reduce latency.
- **Native Power**: Complex map feature extraction is handled in C++ using the same core engine as the phone app, ensuring data consistency.
