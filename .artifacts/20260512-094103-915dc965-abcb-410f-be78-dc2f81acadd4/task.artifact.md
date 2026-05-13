# Task Management

- [x] Research Project Structure and Issues
- [/] Fix Route Flickering (Issue 1)
	- [ ] Stabilize PagerState in Omaps.kt
	- [ ] Move lastMessageTimestamp out of NavigationState
	- [ ] Explicitly handle navigation stop in Bluetooth service
- [ ] Improve Turn Overlays (Issue 2)
	- [ ] Update NavigationIcons.kt to use SDK drawables
	- [ ] Update NavigationScreen.kt to use painterResource
	- [ ] Improve MapPanel.kt turn markers
- [ ] Fix Settings Sync (Issue 3)
	- [ ] Add /preferences/watch handler to WearMessageListenerService.java
	- [ ] Implement binary payload parsing for settings
- [ ] Improve Route Calculation UI (Issue 4)
	- [ ] Add destination info to NavigationState
	- [ ] Create a more informative calculation overlay in Omaps.kt
- [ ] Fix Standalone Route Calculation (Issue 5)
	- [ ] Verify native SDK initialization on watch
	- [ ] Add logging and error handling for route building
