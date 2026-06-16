# Companion Mode & Power Optimization Walkthrough

Optimized the navigation flow to prioritize the phone in Companion Mode, reduced watch power consumption, and smoothed out backend transitions.

## Key Changes

### 1. Unified Search & Wakeup
- Added "Open Search on Phone" to the watch's search screen.
- Starting navigation from the watch now automatically brings the phone app to the foreground.

### 2. Watch Power Optimization
- **Auto-Switch to Turn-by-Turn**: When navigating in Companion Mode, the watch now defaults to the simple arrow/text guidance screen instead of the full map.
- **Rendering Suspension**: Map rendering is automatically paused on the watch when the user is looking at the turn-by-turn or stats screens, significantly extending battery life during long trips.

### 3. Improved Connection UX
- **Connecting State**: Switching backends (GMS/Bluetooth) now shows a yellow "Connecting..." status instead of immediate "Disconnected" red.
- **Immediate Handshake**: The watch now attempts an immediate handshake/sync when the connection backend is changed.

## Technical Details

- **[WearProtocol.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/sdk/src/main/java/app/organicmaps/sdk/sync/WearProtocol.java)**: Registered `/search/on_phone` path.
- **[MwmActivity.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/main/java/app/organicmaps/MwmActivity.java)**: Added `EXTRA_SHOW_SEARCH` and `EXTRA_SHOW_MAP` handlers.
- **[Omaps.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/Omaps.kt)**: Updated `LaunchedEffect(isNavigating)` to handle auto-paging based on the current operation mode.

## Verification

### Manual Tests performed
- Verified backend switch shows yellow indicator.
- Verified "Search on Phone" wakes up phone search UI.
- Verified companion navigation starts on phone and shows Turn-by-Turn on watch.
