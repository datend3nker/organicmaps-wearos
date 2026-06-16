# Companion Mode & Power Optimization Plan

Optimize the cross-device flow to prioritize the phone for heavy tasks in Companion Mode and save battery on the watch. Address backend switching disconnects.

## User Review Required

> [!IMPORTANT]
> - Switching backends will now show a "Connecting..." state instead of an immediate "Disconnected" state.
> - In Companion Mode, starting navigation on the watch will automatically bring the phone app to the foreground and show the map/navigation there.
> - To save power, the watch will default to the Turn-by-Turn (arrows/text) screen during companion navigation and suspend map rendering.

## Proposed Changes

### [Component] Shared Protocol

#### [MODIFY] [WearProtocol.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/sdk/src/main/java/app/organicmaps/sdk/sync/WearProtocol.java)
- Add `PATH_SEARCH_ON_PHONE = "/search/on_phone"` to trigger the full Search UI on the phone.

---

### [Component] Phone App (OMaps)

#### [MODIFY] [MwmActivity.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/main/java/app/organicmaps/MwmActivity.java)
- Handle `EXTRA_SHOW_SEARCH` and `EXTRA_SHOW_MAP` intents in `processIntent()`.
- Ensure `showSearchToolbar()` is called when `EXTRA_SHOW_SEARCH` is received.

#### [MODIFY] [WearMessageRouter.java](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/src/main/java/app/organicmaps/wear/WearMessageRouter.java)
- Handle `PATH_SEARCH_ON_PHONE`: Launch `MwmActivity` with `EXTRA_SHOW_SEARCH`.
- Update `PATH_START_NAVIGATION_REQUEST`: Force launch `MwmActivity` to ensure the user sees the navigation.

---

### [Component] Wear OS App (Watch)

#### [MODIFY] [WearCommandService.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/WearCommandService.kt)
- Update `initBackend`: Trigger an immediate `sendPing()` and `sendHandshake()` after switching backends.
- Implement `searchOnPhone()` method.

#### [MODIFY] [NavigationStateHolder.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/NavigationStateHolder.kt)
- Add `isConnecting` flag to `NavigationState` to handle the transition during backend switches.

#### [MODIFY] [SearchScreen.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/search/SearchScreen.kt)
- Add an "Open Search on Phone" button at the top of the search screen (in companion mode).

#### [MODIFY] [Omaps.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/Omaps.kt)
- In `WearApp` Composable: If navigation starts and we are in Companion Mode, default the pager to the `NavigationPanel` (Turn-by-Turn) instead of the Map.
- Update `StatusIndicators` to show a yellow "Connecting..." icon if `isConnecting` is true.

#### [MODIFY] [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)
- Optimization: Skip rendering the map if navigation is active in Companion Mode and the map is not currently visible on the watch (to save power).

## Verification Plan

### Manual Verification
1. **Backend Switch**: Change backend in settings. Verify the status icon shows yellow "Connecting" and then green "Connected" without a long "Red" timeout.
2. **Search on Phone**: Click "Search on Phone" on watch. Verify phone opens the search keyboard.
3. **Companion Nav Start**: Start a route from the watch. Verify phone wakes up and starts navigation.
4. **Watch Power Saving**: Verify watch shows Turn-by-Turn UI by default when companion navigation is active.
