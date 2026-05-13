# Implementation Plan - Modernize Map Visuals

This plan improves the map's visual quality by implementing a phone-style location arrow and morphed route turn markers.

## User Review Required

> [!IMPORTANT]
> - **Morphed Turn Markers**: These will follow the actual road geometry. For corners, the arrow will "bend" at the vertex. For roundabouts, it will follow the circular path.
> - **Location Arrow**: Switching to a single 3D-style blue arrow that accurately represents heading, removing the "stacked circles" look.

## Proposed Changes

### 1. Modernized Location Indicator

#### [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)
- **Unified Arrow**: Replace the current circles + arrow stack with a single `DrawScope` function that draws a high-contrast blue arrow with a white outline and a subtle shadow (matching the phone app).
- **Heading**: Ensure the arrow rotates with the compass in Explore mode and points up (stabilized) in Navigation mode.

---

### 2. Morphed Route Turn Markers

#### [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)
- **Route Segment Extraction**: Find the index in `routePoints` closest to `navState.turnLat/Lon`.
- **Path-Aligned Arrow Body**: Draw a thick blue `Path` using points from `index - 4` to `index + 4` on the route. This ensures the arrow "bends" with corners and roundabouts.
- **Dynamic Arrowhead**: Draw an arrowhead (triangle) at the final point of this extracted segment, aligned to the segment's tangent.

---

### 3. Cleanup

#### [MapPanel.kt](file:///home/lettner/git/organicmaps-wearos/android/app/omaps/src/main/java/app/organicmaps/wear/presentation/MapPanel.kt)
- **Delete `drawRouteTurnMarker`**: Remove the old circular-badge implementation.

## Verification Plan

### Automated Tests
- Build verification: `./gradlew :app:omaps:assembleDebug`

### Manual Verification
1. **Location Arrow**: Verify the user marker is now a clean blue arrow without redundant circles.
2. **Turn Marker (Corner)**: Start a route with a sharp turn; verify the turn arrow "hugs" the curve of the road.
3. **Turn Marker (Roundabout)**: Navigate through a roundabout; verify the arrow follows the circular exit path.
