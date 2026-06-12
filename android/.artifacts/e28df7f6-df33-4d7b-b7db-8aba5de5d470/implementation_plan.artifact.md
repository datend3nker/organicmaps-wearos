# Fix Overlapping Map Missing Overlays

The goal is to prevent two overlapping overlays ("No Local Map Data" and "Map not on phone") from being displayed simultaneously on the Wear OS map screen. Instead, we will merge their logic into a single unified control that prioritizes the most specific information.

## User Review Required

> [!IMPORTANT]
> The "Map not on phone" overlay currently doesn't have buttons, while "No Local Map Data" has "Manage" and "Sync Local". I plan to keep the buttons visible even when the "Map not on phone" message is shown, so the user can still choose to download it directly or manage maps.

## Proposed Changes

### [Component Name]

#### [MODIFY] [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos-vibe/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)

- Merge the logic of `MapMissingOnPhoneControl` into `MapMissingControl`.
- Remove `MapMissingOnPhoneControl` composable.
- Update `MapMissingControl` to:
    - Determine if a specific map is missing on the phone (using `NavigationStateHolder.state.missingMapId` and `VirtualMwmManager.isMounted`).
    - Use a hierarchical priority for the displayed message:
        1. **Missing World Map** (Critical, needed for rendering).
        2. **Map not on phone** (Specific failure case for the current location).
        3. **No Local Map Data** (Generic case when neither above is true but map data is still missing).
    - Update the icon and text dynamically based on the state.
    - Ensure action buttons ("Manage", "Sync Local") remain available for all states.

## Verification Plan

### Automated Tests
- None, as this is a UI change in a Composable.

### Manual Verification
- Deploy to a Wear OS device/emulator.
- Trigger "Missing World Map" state (delete World map).
- Trigger "Map not on phone" state (try to mount a map that isn't on the phone).
- Trigger "No Local Map Data" state (pan to an area with no downloaded map on watch and no mount active).
- Verify that in all cases, only one overlay is visible and it shows the correct information.
