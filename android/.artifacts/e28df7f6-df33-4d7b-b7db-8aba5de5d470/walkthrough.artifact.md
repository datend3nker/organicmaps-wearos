# Walkthrough: Unified Map Missing Overlay

I have unified the "No Local Map Data" and "Map not on phone" overlays into a single, prioritised control. This ensures that the user only sees one overlay at a time and gets the most specific information available.

## Changes Made

### MapPanel.kt

- **Unified Logic**: Merged `MapMissingOnPhoneControl` into `MapMissingControl`.
- **Priority System**: Implemented a priority system for the displayed message:
    1. **Missing World Map**: Shown if the base world map is not present (critical for any rendering).
    2. **Map not on phone**: Shown if a specific map is needed for the current location but isn't available on the phone for streaming/download.
    3. **No Local Map Data**: Generic message shown when no map data is available for the current location.
- **Improved Actionability**: Action buttons ("Manage" and "Sync Local") are now available even in the "Map not on phone" state, allowing users to take immediate action.
- **Robustness**: Moved the missing-map state calculation to the top-level `MapPanel` to ensure `isOverlayActive` correctly disables map interactions (like zooming via rotary) when the overlay is shown.

## Verification Results

- **UI Consistency**: The overlay now uses dynamic icons (Warning, Phone, or Map) and titles based on the current state.
- **Interaction Safety**: Rotary zoom and key events are correctly ignored when any of the missing map states are active.
- **Clean Code**: Removed redundant composable and merged overlapping logic.

> [!TIP]
> This change also fixes the issue where the "Map not on phone" overlay lacked buttons, which could leave the user stranded without an easy way to open the Map Manager.
